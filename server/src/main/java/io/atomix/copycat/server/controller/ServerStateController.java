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
 * limitations under the License
 */
package io.atomix.copycat.server.controller;

import io.atomix.catalyst.transport.Connection;
import io.atomix.catalyst.util.Assert;
import io.atomix.catalyst.util.Listeners;
import io.atomix.catalyst.util.Managed;
import io.atomix.copycat.client.request.*;
import io.atomix.copycat.server.request.*;
import io.atomix.copycat.server.state.ServerContext;
import io.atomix.copycat.server.state.ServerState;
import io.atomix.copycat.server.state.ServerType;
import io.atomix.copycat.server.state.StateModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

/**
 * Server state controller.
 *
 * @author <a href="http://github.com/kuujo>Jordan Halterman</a>
 */
public class ServerStateController implements Managed<ServerStateController> {
  private final Logger LOGGER = LoggerFactory.getLogger(getClass());
  private final Listeners<ServerState.Type> stateChangeListeners = new Listeners<>();
  private final ServerType serverType;
  private final StateModel stateModel;
  protected final ServerContext context;
  protected ServerState state;
  private boolean open;

  protected ServerStateController(ServerType type, ServerContext context) {
    this.serverType = Assert.notNull(type, "type");
    this.stateModel = type.model();
    this.context = Assert.notNull(context, "context");
  }

  /**
   * Returns the controller member type.
   *
   * @return The controller member type.
   */
  public ServerType type() {
    return serverType;
  }

  /**
   * Returns the controller state.
   *
   * @return The current controller state.
   */
  public ServerState state() {
    return state;
  }

  /**
   * Returns the server state context.
   *
   * @return The server state context.
   */
  public ServerContext context() {
    return context;
  }

  /**
   * Connects a client connection.
   */
  public void connectClient(Connection connection) {
    connection.handler(RegisterRequest.class, request -> state.register(request));
    connection.handler(ConnectRequest.class, request -> state.connect(request, connection));
    connection.handler(KeepAliveRequest.class, request -> state.keepAlive(request));
    connection.handler(UnregisterRequest.class, request -> state.unregister(request));
    connection.handler(CommandRequest.class, request -> state.command(request));
    connection.handler(QueryRequest.class, request -> state.query(request));
  }

  /**
   * Disconnects a client connection.
   */
  public void disconnectClient(Connection connection) {
    connection.handler(RegisterRequest.class, null);
    connection.handler(ConnectRequest.class, null);
    connection.handler(KeepAliveRequest.class, null);
    connection.handler(UnregisterRequest.class, null);
    connection.handler(CommandRequest.class, null);
    connection.handler(QueryRequest.class, null);
  }

  /**
   * Connects a server connection.
   */
  public void connectServer(Connection connection) {
    // Handlers for all request types are registered since requests can be proxied between servers.
    // Note we do not use method references here because the "state" variable changes over time.
    // We have to use lambdas to ensure the request handler points to the current state.
    connection.handler(RegisterRequest.class, request -> state.register(request));
    connection.handler(ConnectRequest.class, request -> state.connect(request, connection));
    connection.handler(AcceptRequest.class, request -> state.accept(request));
    connection.handler(KeepAliveRequest.class, request -> state.keepAlive(request));
    connection.handler(UnregisterRequest.class, request -> state.unregister(request));
    connection.handler(PublishRequest.class, request -> state.publish(request));
    connection.handler(ConfigureRequest.class, request -> state.configure(request));
    connection.handler(JoinRequest.class, request -> state.join(request));
    connection.handler(LeaveRequest.class, request -> state.leave(request));
    connection.handler(AppendRequest.class, request -> state.append(request));
    connection.handler(PollRequest.class, request -> state.poll(request));
    connection.handler(VoteRequest.class, request -> state.vote(request));
    connection.handler(CommandRequest.class, request -> state.command(request));
    connection.handler(QueryRequest.class, request -> state.query(request));
  }

  /**
   * Disconnects a server connection.
   */
  public void disconnectServer(Connection connection) {
    connection.handler(RegisterRequest.class, null);
    connection.handler(ConnectRequest.class, null);
    connection.handler(AcceptRequest.class, null);
    connection.handler(KeepAliveRequest.class, null);
    connection.handler(UnregisterRequest.class, null);
    connection.handler(PublishRequest.class, null);
    connection.handler(ConfigureRequest.class, null);
    connection.handler(JoinRequest.class, null);
    connection.handler(LeaveRequest.class, null);
    connection.handler(AppendRequest.class, null);
    connection.handler(PollRequest.class, null);
    connection.handler(VoteRequest.class, null);
    connection.handler(CommandRequest.class, null);
    connection.handler(QueryRequest.class, null);
  }

  /**
   * Resets the state to the initial state.
   */
  public final void reset() {
    transition(stateModel.reset());
  }

  /**
   * Transitions the state to the next state.
   */
  public final void next() {
    transition(nextState());
  }

  /**
   * Transitions the server state.
   */
  protected final void transition(ServerState.Type state) {
    context.checkThread();

    // If the state has not changed, return.
    if (this.state != null && state == this.state.type())
      return;

    LOGGER.info("{} - Transitioning to {}", context.getCluster().getMember().serverAddress(), state);

    // Close the current state.
    if (this.state != null) {
      try {
        this.state.close().get();
      } catch (InterruptedException | ExecutionException e) {
        throw new IllegalStateException("failed to close Raft state", e);
      }
    }

    // Force state transitions to occur synchronously in order to prevent race conditions.
    try {
      this.state = state.factory().createState(this);
      if (this.state != null) {
        this.state.open().get();
        stateChangeListeners.forEach(l -> l.accept(this.state.type()));
      }
    } catch (InterruptedException | ExecutionException e) {
      throw new IllegalStateException("failed to initialize Raft state", e);
    }
  }

  @Override
  public CompletableFuture<ServerStateController> open() {
    open = true;
    reset();
    return CompletableFuture.completedFuture(this);
  }

  @Override
  public boolean isOpen() {
    return open;
  }

  @Override
  public CompletableFuture<Void> close() {
    open = false;
    transition(null);
    return CompletableFuture.completedFuture(null);
  }

  @Override
  public boolean isClosed() {
    return !open;
  }

}
