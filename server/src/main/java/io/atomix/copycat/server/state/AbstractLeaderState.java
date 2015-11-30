/*
 * Copyright 2015 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.atomix.copycat.server.state;

import io.atomix.catalyst.transport.Connection;
import io.atomix.catalyst.util.concurrent.ComposableFuture;
import io.atomix.catalyst.util.concurrent.Scheduled;
import io.atomix.copycat.client.Command;
import io.atomix.copycat.client.Query;
import io.atomix.copycat.client.error.RaftError;
import io.atomix.copycat.client.error.RaftException;
import io.atomix.copycat.client.request.*;
import io.atomix.copycat.client.response.*;
import io.atomix.copycat.client.session.Session;
import io.atomix.copycat.server.cluster.CopycatMemberType;
import io.atomix.copycat.server.cluster.Member;
import io.atomix.copycat.server.controller.ServerStateController;
import io.atomix.copycat.server.request.*;
import io.atomix.copycat.server.response.*;
import io.atomix.copycat.server.session.ServerSession;
import io.atomix.copycat.server.storage.entry.*;

import java.time.Duration;
import java.util.Collection;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * Leader state.
 *
 * @author <a href="http://github.com/kuujo">Jordan Halterman</a>
 */
public abstract class AbstractLeaderState extends AbstractActiveState {
  private final LeaderAppender appender;
  private Scheduled appendTimer;
  private long configuring;

  public AbstractLeaderState(ServerStateController controller) {
    super(controller);
    this.appender = new LeaderAppender(this);
  }

  @Override
  public synchronized CompletableFuture<ServerState> open() {
    // Append initial entries to the log, including an initial no-op entry and the server's configuration.
    appendInitialEntries();

    // Schedule the initial entries commit to occur after the state is opened. Attempting any communication
    // within the open() method will result in a deadlock since RaftProtocol calls this method synchronously.
    // What is critical about this logic is that the heartbeat timer not be started until a no-op entry has been committed.
    controller.context().getThreadContext().execute(this::commitInitialEntries).whenComplete((result, error) -> {
      if (isOpen() && error == null) {
        startAppendTimer();
      }
    });

    return super.open()
      .thenRun(this::takeLeadership)
      .thenApply(v -> this);
  }

  /**
   * Sets the current node as the cluster leader.
   */
  private void takeLeadership() {
    controller.context().setLeader(controller.context().getCluster().getMember().id());
    controller.context().getCluster().getVotingMemberStates().forEach(m -> m.resetState(controller.context().getLog()));
  }

  /**
   * Appends initial entries to the log to take leadership.
   */
  private void appendInitialEntries() {
    final long term = controller.context().getTerm();

    // Append a no-op entry to reset session timeouts and commit entries from prior terms.
    try (NoOpEntry entry = controller.context().getLog().create(NoOpEntry.class)) {
      entry.setTerm(term)
        .setTimestamp(appender.time());
      assert controller.context().getLog().append(entry) == appender.index();
    }

    // Append a configuration entry to propagate the leader's cluster configuration.
    configure(controller.context().getCluster().getMembers());
  }

  /**
   * Commits a no-op entry to the log, ensuring any entries from a previous term are committed.
   */
  private CompletableFuture<Void> commitInitialEntries() {
    // The Raft protocol dictates that leaders cannot commit entries from previous terms until
    // at least one entry from their current term has been stored on a majority of servers. Thus,
    // we force entries to be appended up to the leader's no-op entry. The LeaderAppender will ensure
    // that the commitIndex is not increased until the no-op entry (appender.index()) is committed.
    CompletableFuture<Void> future = new CompletableFuture<>();
    appender.appendEntries(appender.index()).whenComplete((resultIndex, error) -> {
      controller.context().checkThread();
      if (isOpen()) {
        if (error == null) {
          applyEntries(resultIndex);
          future.complete(null);
        } else {
          controller.context().setLeader(0);
          controller.reset();
        }
      }
    });
    return future;
  }

  /**
   * Applies all unapplied entries to the log.
   */
  private void applyEntries(long index) {
    if (!controller.context().getLog().isEmpty()) {
      int count = 0;
      for (long lastApplied = Math.max(controller.context().getStateMachine().getLastApplied(), controller.context().getLog().firstIndex()); lastApplied <= index; lastApplied++) {
        Entry entry = controller.context().getLog().get(lastApplied);
        if (entry != null) {
          controller.context().getStateMachine().apply(entry).whenComplete((result, error) -> {
            if (isOpen() && error != null) {
              LOGGER.info("{} - An application error occurred: {}", controller.context().getCluster().getMember().serverAddress(), error.getMessage());
            }
            entry.release();
          });
        }
        count++;
      }

      LOGGER.debug("{} - Applied {} entries to log", controller.context().getCluster().getMember().serverAddress(), count);
      controller.context().getLog().compactor().minorIndex(controller.context().getStateMachine().getLastCompleted());
    }
  }

  /**
   * Starts sending AppendEntries requests to all cluster members.
   */
  private void startAppendTimer() {
    // Set a timer that will be used to periodically synchronize with other nodes
    // in the cluster. This timer acts as a heartbeat to ensure this node remains
    // the leader.
    LOGGER.debug("{} - Starting append timer", controller.context().getCluster().getMember().serverAddress());
    appendTimer = controller.context().getThreadContext().schedule(Duration.ZERO, controller.context().getHeartbeatInterval(), this::appendMembers);
  }

  /**
   * Sends AppendEntries requests to members of the cluster that haven't heard from the leader in a while.
   */
  private void appendMembers() {
    controller.context().checkThread();
    if (isOpen()) {
      appender.appendEntries().whenComplete((result, error) -> {
        controller.context().getLog().compactor().minorIndex(controller.context().getStateMachine().getLastCompleted());
      });
    }
  }

  /**
   * Checks to determine whether any sessions have expired.
   * <p>
   * Copycat allows only leaders to explicitly unregister sessions due to expiration. This ensures
   * that sessions cannot be expired by lengthy election periods or other disruptions to time.
   * To do so, the leader periodically iterates through registered sessions and checks for sessions
   * that have been marked suspicious. The internal state machine marks sessions as suspicious when
   * keep alive entries are not committed for longer than the session timeout. Once the leader marks
   * a session as suspicious, it will log and replicate an {@link UnregisterEntry} to unregister the session.
   */
  private void checkSessions() {
    long term = controller.context().getTerm();

    // Iterate through all currently registered sessions.
    for (Session session : controller.context().getStateMachine().executor().context().sessions()) {
      // If the session isn't already being unregistered by this leader and a keep-alive entry hasn't
      // been committed for the session in some time, log and commit a new UnregisterEntry.
      ServerSession serverSession = (ServerSession) session;
      if (!serverSession.isUnregistering() && serverSession.isSuspect()) {
        LOGGER.debug("{} - Detected expired session: {}", controller.context().getCluster().getMember().serverAddress(), session.id());

        // Log the unregister entry, indicating that the session was explicitly unregistered by the leader.
        // This will result in state machine expire() methods being called when the entry is applied.
        final long index;
        try (UnregisterEntry entry = controller.context().getLog().create(UnregisterEntry.class)) {
          entry.setTerm(term)
            .setSession(session.id())
            .setExpired(true)
            .setTimestamp(System.currentTimeMillis());
          index = controller.context().getLog().append(entry);
          LOGGER.debug("{} - Appended {}", controller.context().getCluster().getMember().serverAddress(), entry);
        }

        // Commit the unregister entry and apply it to the state machine.
        appender.appendEntries(index).whenComplete((result, error) -> {
          if (isOpen()) {
            UnregisterEntry entry = controller.context().getLog().get(index);
            LOGGER.debug("{} - Applying {}", controller.context().getCluster().getMember().serverAddress(), entry);
            controller.context().getStateMachine().apply(entry, true).whenComplete((unregisterResult, unregisterError) -> entry.release());
          }
        });

        // Mark the session as being unregistered in order to ensure this leader doesn't attempt
        // to unregister it again.
        serverSession.unregister();
      }
    }
  }

  /**
   * Returns a boolean value indicating whether a configuration is currently being committed.
   *
   * @return Indicates whether a configuration is currently being committed.
   */
  boolean configuring() {
    return configuring > 0;
  }

  /**
   * Commits the given configuration.
   */
  protected CompletableFuture<Long> configure(Collection<Member> members) {
    final long index;
    try (ConfigurationEntry entry = controller.context().getLog().create(ConfigurationEntry.class)) {
      entry.setTerm(controller.context().getTerm())
        .setMembers(members);
      index = controller.context().getLog().append(entry);
      LOGGER.debug("{} - Appended {} to log at index {}", controller.context().getCluster().getMember().serverAddress(), entry, index);

      // Store the index of the configuration entry in order to prevent other configurations from
      // being logged and committed concurrently. This is an important safety property of Raft.
      configuring = index;
      controller.context().getCluster().configure(entry.getIndex(), entry.getMembers());
    }

    return appender.appendEntries(index).whenComplete((commitIndex, commitError) -> {
      controller.context().checkThread();
      if (isOpen()) {
        // Reset the configuration index to allow new configuration changes to be committed.
        configuring = 0;
      }
    });
  }

  @Override
  public CompletableFuture<JoinResponse> join(final JoinRequest request) {
    controller.context().checkThread();
    logRequest(request);

    // If another configuration change is already under way, reject the configuration.
    if (configuring()) {
      return CompletableFuture.completedFuture(logResponse(JoinResponse.builder()
        .withStatus(Response.Status.ERROR)
        .build()));
    }

    // If the leader index is 0 or is greater than the commitIndex, reject the join requests.
    // Configuration changes should not be allowed until the leader has committed a no-op entry.
    // See https://groups.google.com/forum/#!topic/raft-dev/t4xj6dJTP6E
    if (appender.index() == 0 || controller.context().getCommitIndex() < appender.index()) {
      return CompletableFuture.completedFuture(logResponse(JoinResponse.builder()
        .withStatus(Response.Status.ERROR)
        .build()));
    }

    // If the member is already a known member of the cluster, complete the join successfully.
    Member existingMember = controller.context().getCluster().getRemoteMember(request.member().id());
    if (existingMember != null && existingMember.clientAddress() != null && existingMember.clientAddress().equals(request.member().clientAddress())) {
      return CompletableFuture.completedFuture(logResponse(JoinResponse.builder()
        .withStatus(Response.Status.OK)
        .withVersion(controller.context().getCluster().getVersion())
        .withMembers(controller.context().getCluster().getMembers())
        .build()));
    }

    Member member = request.member();

    // If the member is a joining member, set its type to passive.
    if (member.type() == null) {
      member.update(CopycatMemberType.PASSIVE);
    }

    Collection<Member> members = controller.context().getCluster().getMembers();
    members.add(member);

    CompletableFuture<JoinResponse> future = new CompletableFuture<>();
    configure(members).whenComplete((index, error) -> {
      controller.context().checkThread();
      if (isOpen()) {
        if (error == null) {
          future.complete(logResponse(JoinResponse.builder()
            .withStatus(Response.Status.OK)
            .withVersion(index)
            .withMembers(members)
            .build()));
        } else {
          future.complete(logResponse(JoinResponse.builder()
            .withStatus(Response.Status.ERROR)
            .withError(RaftError.Type.INTERNAL_ERROR)
            .build()));
        }
      }
    });
    return future;
  }

  @Override
  public CompletableFuture<LeaveResponse> leave(final LeaveRequest request) {
    controller.context().checkThread();
    logRequest(request);

    // If another configuration change is already under way, reject the configuration.
    if (configuring()) {
      return CompletableFuture.completedFuture(logResponse(LeaveResponse.builder()
        .withStatus(Response.Status.ERROR)
        .build()));
    }

    // If the leader index is 0 or is greater than the commitIndex, reject the join requests.
    // Configuration changes should not be allowed until the leader has committed a no-op entry.
    // See https://groups.google.com/forum/#!topic/raft-dev/t4xj6dJTP6E
    if (appender.index() == 0 || controller.context().getCommitIndex() < appender.index()) {
      return CompletableFuture.completedFuture(logResponse(LeaveResponse.builder()
        .withStatus(Response.Status.ERROR)
        .build()));
    }

    // If the leaving member is not a known member of the cluster, complete the leave successfully.
    if (controller.context().getCluster().getMember(request.member().id()) == null) {
      return CompletableFuture.completedFuture(logResponse(LeaveResponse.builder()
        .withStatus(Response.Status.OK)
        .withMembers(controller.context().getCluster().getMembers())
        .build()));
    }

    Member member = request.member();

    Collection<Member> members = controller.context().getCluster().getMembers();
    members.remove(member);

    CompletableFuture<LeaveResponse> future = new CompletableFuture<>();
    configure(members).whenComplete((index, error) -> {
      controller.context().checkThread();
      if (isOpen()) {
        if (error == null) {
          future.complete(logResponse(LeaveResponse.builder()
            .withStatus(Response.Status.OK)
            .withVersion(index)
            .withMembers(members)
            .build()));
        } else {
          future.complete(logResponse(LeaveResponse.builder()
            .withStatus(Response.Status.ERROR)
            .withError(RaftError.Type.INTERNAL_ERROR)
            .build()));
        }
      }
    });
    return future;
  }

  @Override
  public CompletableFuture<PollResponse> poll(final PollRequest request) {
    return CompletableFuture.completedFuture(logResponse(PollResponse.builder()
      .withStatus(Response.Status.OK)
      .withTerm(controller.context().getTerm())
      .withAccepted(false)
      .build()));
  }

  @Override
  public CompletableFuture<VoteResponse> vote(final VoteRequest request) {
    if (request.term() > controller.context().getTerm()) {
      LOGGER.debug("{} - Received greater term", controller.context().getCluster().getMember().serverAddress());
      controller.context().setLeader(0);
      controller.reset();
      return super.vote(request);
    } else {
      return CompletableFuture.completedFuture(logResponse(VoteResponse.builder()
        .withStatus(Response.Status.OK)
        .withTerm(controller.context().getTerm())
        .withVoted(false)
        .build()));
    }
  }

  @Override
  public CompletableFuture<AppendResponse> append(final AppendRequest request) {
    controller.context().checkThread();
    if (request.term() > controller.context().getTerm()) {
      return super.append(request);
    } else if (request.term() < controller.context().getTerm()) {
      return CompletableFuture.completedFuture(logResponse(AppendResponse.builder()
        .withStatus(Response.Status.OK)
        .withTerm(controller.context().getTerm())
        .withSucceeded(false)
        .withLogIndex(controller.context().getLog().lastIndex())
        .build()));
    } else {
      controller.context().setLeader(request.leader());
      controller.reset();
      return super.append(request);
    }
  }

  @Override
  public CompletableFuture<CommandResponse> command(final CommandRequest request) {
    controller.context().checkThread();
    logRequest(request);

    // Get the client's server session. If the session doesn't exist, return an unknown session error.
    ServerSession session = controller.context().getStateMachine().executor().context().sessions().getSession(request.session());
    if (session == null) {
      return CompletableFuture.completedFuture(logResponse(CommandResponse.builder()
        .withStatus(Response.Status.ERROR)
        .withError(RaftError.Type.UNKNOWN_SESSION_ERROR)
        .build()));
    }

    ComposableFuture<CommandResponse> future = new ComposableFuture<>();

    Command command = request.command();

    // If the command is LINEARIZABLE and the session's current sequence number is less then one prior to the request
    // sequence number, queue this request for handling later. We want to handle command requests in the order in which
    // they were sent by the client. Note that it's possible for the session sequence number to be greater than the request
    // sequence number. In that case, it's likely that the command was submitted more than once to the
    // cluster, and the command will be deduplicated once applied to the state machine.
    if (request.sequence() > session.nextRequest()) {
      session.registerRequest(request.sequence(), () -> command(request).whenComplete(future));
      return future;
    }

    final long term = controller.context().getTerm();
    final long timestamp = System.currentTimeMillis();
    final long index;

    // Create a CommandEntry and append it to the log.
    try (CommandEntry entry = controller.context().getLog().create(CommandEntry.class)) {
      entry.setTerm(term)
        .setSession(request.session())
        .setTimestamp(timestamp)
        .setSequence(request.sequence())
        .setCommand(command);
      index = controller.context().getLog().append(entry);
      LOGGER.debug("{} - Appended {} to log at index {}", controller.context().getCluster().getMember().serverAddress(), entry, index);
    }

    appender.appendEntries(index).whenComplete((commitIndex, commitError) -> {
      controller.context().checkThread();
      if (isOpen()) {
        if (commitError == null) {
          CommandEntry entry = controller.context().getLog().get(index);

          LOGGER.debug("{} - Applying {}", controller.context().getCluster().getMember().serverAddress(), entry);
          controller.context().getStateMachine().apply(entry, true).whenComplete((result, error) -> {
            if (isOpen()) {
              if (error == null) {
                future.complete(logResponse(CommandResponse.builder()
                  .withStatus(Response.Status.OK)
                  .withVersion(entry.getIndex())
                  .withResult(result)
                  .build()));
              } else if (error instanceof RaftException) {
                future.complete(logResponse(CommandResponse.builder()
                  .withStatus(Response.Status.ERROR)
                  .withVersion(entry.getIndex())
                  .withError(((RaftException) error).getType())
                  .build()));
              } else {
                future.complete(logResponse(CommandResponse.builder()
                  .withStatus(Response.Status.ERROR)
                  .withVersion(entry.getIndex())
                  .withError(RaftError.Type.INTERNAL_ERROR)
                  .build()));
              }
              checkSessions();
            }
            entry.release();
          });
        } else {
          future.complete(logResponse(CommandResponse.builder()
            .withStatus(Response.Status.ERROR)
            .withError(RaftError.Type.INTERNAL_ERROR)
            .build()));
        }
      }
    });

    // Set the last processed request for the session. This will cause sequential command callbacks to be executed.
    session.setRequest(request.sequence());

    return future;
  }

  @Override
  public CompletableFuture<QueryResponse> query(final QueryRequest request) {

    Query query = request.query();

    final long timestamp = System.currentTimeMillis();
    final long index = controller.context().getCommitIndex();

    controller.context().checkThread();
    logRequest(request);

    QueryEntry entry = controller.context().getLog().create(QueryEntry.class)
      .setIndex(index)
      .setTerm(controller.context().getTerm())
      .setTimestamp(timestamp)
      .setSession(request.session())
      .setSequence(request.sequence())
      .setVersion(request.version())
      .setQuery(query);

    Query.ConsistencyLevel consistency = query.consistency();
    if (consistency == null)
      return submitQueryLinearizable(entry);

    switch (consistency) {
      case CAUSAL:
      case SEQUENTIAL:
        return submitQueryLocal(entry);
      case BOUNDED_LINEARIZABLE:
        return submitQueryBoundedLinearizable(entry);
      case LINEARIZABLE:
        return submitQueryLinearizable(entry);
      default:
        throw new IllegalStateException("unknown consistency level");
    }
  }

  /**
   * Submits a query with serializable consistency.
   */
  private CompletableFuture<QueryResponse> submitQueryLocal(QueryEntry entry) {
    return applyQuery(entry, new CompletableFuture<>());
  }

  /**
   * Submits a query with lease bounded linearizable consistency.
   */
  private CompletableFuture<QueryResponse> submitQueryBoundedLinearizable(QueryEntry entry) {
    if (System.currentTimeMillis() - appender.time() < controller.context().getElectionTimeout().toMillis()) {
      return submitQueryLocal(entry);
    } else {
      return submitQueryLinearizable(entry);
    }
  }

  /**
   * Submits a query with strict linearizable consistency.
   */
  private CompletableFuture<QueryResponse> submitQueryLinearizable(QueryEntry entry) {
    CompletableFuture<QueryResponse> future = new CompletableFuture<>();
    appender.appendEntries().whenComplete((commitIndex, commitError) -> {
      controller.context().checkThread();
      if (isOpen()) {
        if (commitError == null) {
          entry.acquire();
          applyQuery(entry, future);
        } else {
          future.complete(logResponse(QueryResponse.builder()
            .withStatus(Response.Status.ERROR)
            .withError(RaftError.Type.COMMAND_ERROR)
            .build()));
        }
      }
      entry.release();
    });
    return future;
  }

  /**
   * Applies a query to the state machine.
   */
  private CompletableFuture<QueryResponse> applyQuery(QueryEntry entry, CompletableFuture<QueryResponse> future) {
    // In the case of the leader, the state machine is always up to date, so no queries will be queued and all query
    // versions will be the last applied index.
    final long version = controller.context().getStateMachine().getLastApplied();
    applyEntry(entry).whenComplete((result, error) -> {
      if (isOpen()) {
        if (error == null) {
          future.complete(logResponse(QueryResponse.builder()
            .withStatus(Response.Status.OK)
            .withVersion(version)
            .withResult(result)
            .build()));
        } else if (error instanceof RaftException) {
          future.complete(logResponse(QueryResponse.builder()
            .withStatus(Response.Status.ERROR)
            .withError(((RaftException) error).getType())
            .build()));
        } else {
          future.complete(logResponse(QueryResponse.builder()
            .withStatus(Response.Status.ERROR)
            .withError(RaftError.Type.INTERNAL_ERROR)
            .build()));
        }
        checkSessions();
      }
      entry.release();
    });
    return future;
  }

  @Override
  public CompletableFuture<RegisterResponse> register(RegisterRequest request) {
    final long timestamp = System.currentTimeMillis();
    final long index;
    final long timeout = controller.context().getSessionTimeout().toMillis();

    controller.context().checkThread();
    logRequest(request);

    try (RegisterEntry entry = controller.context().getLog().create(RegisterEntry.class)) {
      entry.setTerm(controller.context().getTerm())
        .setTimestamp(timestamp)
        .setClient(request.client())
        .setTimeout(timeout);
      index = controller.context().getLog().append(entry);
      LOGGER.debug("{} - Appended {}", controller.context().getCluster().getMember().serverAddress(), entry);
    }

    CompletableFuture<RegisterResponse> future = new CompletableFuture<>();
    appender.appendEntries(index).whenComplete((commitIndex, commitError) -> {
      controller.context().checkThread();
      if (isOpen()) {
        if (commitError == null) {
          RegisterEntry entry = controller.context().getLog().get(index);

          LOGGER.debug("{} - Applying {}", controller.context().getCluster().getMember().serverAddress(), entry);
          controller.context().getStateMachine().apply(entry, true).whenComplete((sessionId, sessionError) -> {
            if (isOpen()) {
              if (sessionError == null) {
                future.complete(logResponse(RegisterResponse.builder()
                  .withStatus(Response.Status.OK)
                  .withSession((Long) sessionId)
                  .withTimeout(timeout)
                  .withLeader(controller.context().getCluster().getMember().serverAddress())
                  .withMembers(controller.context().getCluster().getMembers().stream()
                    .map(Member::clientAddress)
                    .filter(m -> m != null)
                    .collect(Collectors.toList())).build()));
              } else if (sessionError instanceof RaftException) {
                future.complete(logResponse(RegisterResponse.builder()
                  .withStatus(Response.Status.ERROR)
                  .withError(((RaftException) sessionError).getType())
                  .build()));
              } else {
                future.complete(logResponse(RegisterResponse.builder()
                  .withStatus(Response.Status.ERROR)
                  .withError(RaftError.Type.INTERNAL_ERROR)
                  .build()));
              }
              checkSessions();
            }
            entry.release();
          });
        } else {
          future.complete(logResponse(RegisterResponse.builder()
            .withStatus(Response.Status.ERROR)
            .withError(RaftError.Type.INTERNAL_ERROR)
            .build()));
        }
      }
    });
    return future;
  }

  @Override
  public CompletableFuture<ConnectResponse> connect(ConnectRequest request, Connection connection) {
    controller.context().checkThread();
    logRequest(request);

    controller.context().getStateMachine().executor().context().sessions().registerConnection(request.session(), connection);

    AcceptRequest acceptRequest = AcceptRequest.builder()
      .withSession(request.session())
      .withAddress(controller.context().getCluster().getMember().serverAddress())
      .build();
    return accept(acceptRequest)
      .thenApply(acceptResponse -> ConnectResponse.builder().withStatus(Response.Status.OK).build())
      .thenApply(this::logResponse);
  }

  @Override
  public CompletableFuture<AcceptResponse> accept(AcceptRequest request) {
    final long timestamp = System.currentTimeMillis();
    final long index;

    controller.context().checkThread();
    logRequest(request);

    try (ConnectEntry entry = controller.context().getLog().create(ConnectEntry.class)) {
      entry.setTerm(controller.context().getTerm())
        .setSession(request.session())
        .setTimestamp(timestamp)
        .setAddress(request.address());
      index = controller.context().getLog().append(entry);
      LOGGER.debug("{} - Appended {}", controller.context().getCluster().getMember().serverAddress(), entry);
    }

    controller.context().getStateMachine().executor().context().sessions().registerAddress(request.session(), request.address());

    CompletableFuture<AcceptResponse> future = new CompletableFuture<>();
    appender.appendEntries(index).whenComplete((commitIndex, commitError) -> {
      controller.context().checkThread();
      if (isOpen()) {
        if (commitError == null) {
          ConnectEntry entry = controller.context().getLog().get(index);
          applyEntry(entry).whenComplete((connectResult, connectError) -> {
            if (isOpen()) {
              if (connectError == null) {
                future.complete(logResponse(AcceptResponse.builder()
                  .withStatus(Response.Status.OK)
                  .build()));
              } else if (connectError instanceof RaftException) {
                future.complete(logResponse(AcceptResponse.builder()
                  .withStatus(Response.Status.ERROR)
                  .withError(((RaftException) connectError).getType())
                  .build()));
              } else {
                future.complete(logResponse(AcceptResponse.builder()
                  .withStatus(Response.Status.ERROR)
                  .withError(RaftError.Type.INTERNAL_ERROR)
                  .build()));
              }
              checkSessions();
            }
            entry.release();
          });
        } else {
          future.complete(logResponse(AcceptResponse.builder()
            .withStatus(Response.Status.ERROR)
            .withError(RaftError.Type.INTERNAL_ERROR)
            .build()));
        }
      }
    });
    return future;
  }

  @Override
  public CompletableFuture<KeepAliveResponse> keepAlive(KeepAliveRequest request) {
    final long timestamp = System.currentTimeMillis();
    final long index;

    controller.context().checkThread();
    logRequest(request);

    try (KeepAliveEntry entry = controller.context().getLog().create(KeepAliveEntry.class)) {
      entry.setTerm(controller.context().getTerm())
        .setSession(request.session())
        .setCommandSequence(request.commandSequence())
        .setEventVersion(request.eventVersion())
        .setTimestamp(timestamp);
      index = controller.context().getLog().append(entry);
      LOGGER.debug("{} - Appended {}", controller.context().getCluster().getMember().serverAddress(), entry);
    }

    CompletableFuture<KeepAliveResponse> future = new CompletableFuture<>();
    appender.appendEntries(index).whenComplete((commitIndex, commitError) -> {
      controller.context().checkThread();
      if (isOpen()) {
        if (commitError == null) {
          KeepAliveEntry entry = controller.context().getLog().get(index);
          applyEntry(entry).whenCompleteAsync((sessionResult, sessionError) -> {
            if (isOpen()) {
              if (sessionError == null) {
                future.complete(logResponse(KeepAliveResponse.builder()
                  .withStatus(Response.Status.OK)
                  .withLeader(controller.context().getCluster().getMember().serverAddress())
                  .withMembers(controller.context().getCluster().getMembers().stream()
                    .map(Member::clientAddress)
                    .filter(m -> m != null)
                    .collect(Collectors.toList())).build()));
              } else if (sessionError instanceof RaftException) {
                future.complete(logResponse(KeepAliveResponse.builder()
                  .withStatus(Response.Status.ERROR)
                  .withLeader(controller.context().getCluster().getMember().serverAddress())
                  .withError(((RaftException) sessionError).getType())
                  .build()));
              } else {
                future.complete(logResponse(KeepAliveResponse.builder()
                  .withStatus(Response.Status.ERROR)
                  .withLeader(controller.context().getCluster().getMember().serverAddress())
                  .withError(RaftError.Type.INTERNAL_ERROR)
                  .build()));
              }
              checkSessions();
            }
            entry.release();
          }, controller.context().getThreadContext().executor());
        } else {
          future.complete(logResponse(KeepAliveResponse.builder()
            .withStatus(Response.Status.ERROR)
            .withLeader(controller.context().getCluster().getMember().serverAddress())
            .withError(RaftError.Type.INTERNAL_ERROR)
            .build()));
        }
      }
    });
    return future;
  }

  @Override
  public CompletableFuture<UnregisterResponse> unregister(UnregisterRequest request) {
    final long timestamp = System.currentTimeMillis();
    final long index;

    controller.context().checkThread();
    logRequest(request);

    try (UnregisterEntry entry = controller.context().getLog().create(UnregisterEntry.class)) {
      entry.setTerm(controller.context().getTerm())
        .setSession(request.session())
        .setExpired(false)
        .setTimestamp(timestamp);
      index = controller.context().getLog().append(entry);
      LOGGER.debug("{} - Appended {}", controller.context().getCluster().getMember().serverAddress(), entry);
    }

    CompletableFuture<UnregisterResponse> future = new CompletableFuture<>();
    appender.appendEntries(index).whenComplete((commitIndex, commitError) -> {
      controller.context().checkThread();
      if (isOpen()) {
        if (commitError == null) {
          UnregisterEntry entry = controller.context().getLog().get(index);

          LOGGER.debug("{} - Applying {}", controller.context().getCluster().getMember().serverAddress(), entry);
          controller.context().getStateMachine().apply(entry, true).whenComplete((unregisterResult, unregisterError) -> {
            if (isOpen()) {
              if (unregisterError == null) {
                future.complete(logResponse(UnregisterResponse.builder()
                  .withStatus(Response.Status.OK)
                  .build()));
              } else if (unregisterError instanceof RaftException) {
                future.complete(logResponse(UnregisterResponse.builder()
                  .withStatus(Response.Status.ERROR)
                  .withError(((RaftException) unregisterError).getType())
                  .build()));
              } else {
                future.complete(logResponse(UnregisterResponse.builder()
                  .withStatus(Response.Status.ERROR)
                  .withError(RaftError.Type.INTERNAL_ERROR)
                  .build()));
              }
              checkSessions();
            }
            entry.release();
          });
        } else {
          future.complete(logResponse(UnregisterResponse.builder()
            .withStatus(Response.Status.ERROR)
            .withError(RaftError.Type.INTERNAL_ERROR)
            .build()));
        }
      }
    });
    return future;
  }

  /**
   * Cancels the append timer.
   */
  private void cancelAppendTimer() {
    if (appendTimer != null) {
      LOGGER.debug("{} - Cancelling append timer", controller.context().getCluster().getMember().serverAddress());
      appendTimer.cancel();
    }
  }

  @Override
  public synchronized CompletableFuture<Void> close() {
    return super.close().thenRun(appender::close).thenRun(this::cancelAppendTimer);
  }

}
