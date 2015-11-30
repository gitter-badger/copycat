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
import io.atomix.catalyst.util.Managed;
import io.atomix.copycat.client.request.*;
import io.atomix.copycat.client.response.*;
import io.atomix.copycat.server.controller.ServerStateController;
import io.atomix.copycat.server.request.*;
import io.atomix.copycat.server.response.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;

/**
 * Abstract state.
 *
 * @author <a href="http://github.com/kuujo">Jordan Halterman</a>
 */
public abstract class ServerState implements Managed<ServerState> {

  /**
   * Server state type.
   */
  public interface Type {
  }

  protected final Logger LOGGER = LoggerFactory.getLogger(getClass());
  protected final ServerStateController controller;
  private boolean open = true;

  protected ServerState(ServerStateController controller) {
    this.controller = controller;
  }

  /**
   * Returns the Copycat state represented by this state.
   *
   * @return The Copycat state represented by this state.
   */
  public abstract Type type();

  /**
   * Logs a request.
   */
  protected final <R extends Request> R logRequest(R request) {
    LOGGER.debug("{} - Received {}", controller.context().getCluster().getMember().serverAddress(), request);
    return request;
  }

  /**
   * Logs a response.
   */
  protected final <R extends Response> R logResponse(R response) {
    LOGGER.debug("{} - Sent {}", controller.context().getCluster().getMember().serverAddress(), response);
    return response;
  }

  @Override
  public CompletableFuture<ServerState> open() {
    controller.context().checkThread();
    open = true;
    return CompletableFuture.completedFuture(null);
  }

  @Override
  public boolean isOpen() {
    return open;
  }

  /**
   * Handles a join request.
   */
  public abstract CompletableFuture<JoinResponse> join(JoinRequest request);

  /**
   * Handles a leave request.
   */
  public abstract CompletableFuture<LeaveResponse> leave(LeaveRequest request);

  /**
   * Handles a register request.
   */
  public abstract CompletableFuture<RegisterResponse> register(RegisterRequest request);

  /**
   * Handles a connect request.
   */
  public abstract CompletableFuture<ConnectResponse> connect(ConnectRequest request, Connection connection);

  /**
   * Handles an accept request.
   */
  public abstract CompletableFuture<AcceptResponse> accept(AcceptRequest request);

  /**
   * Handles a keep alive request.
   */
  public abstract CompletableFuture<KeepAliveResponse> keepAlive(KeepAliveRequest request);

  /**
   * Handles an unregister request.
   */
  public abstract CompletableFuture<UnregisterResponse> unregister(UnregisterRequest request);

  /**
   * Handles a publish request.
   */
  public abstract CompletableFuture<PublishResponse> publish(PublishRequest request);

  /**
   * Handles a configure request.
   */
  public abstract CompletableFuture<ConfigureResponse> configure(ConfigureRequest request);

  /**
   * Handles an append request.
   */
  public abstract CompletableFuture<AppendResponse> append(AppendRequest request);

  /**
   * Handles a poll request.
   */
  public abstract CompletableFuture<PollResponse> poll(PollRequest request);

  /**
   * Handles a vote request.
   */
  public abstract CompletableFuture<VoteResponse> vote(VoteRequest request);

  /**
   * Handles a command request.
   */
  public abstract CompletableFuture<CommandResponse> command(CommandRequest request);

  /**
   * Handles a query request.
   */
  public abstract CompletableFuture<QueryResponse> query(QueryRequest request);

  @Override
  public CompletableFuture<Void> close() {
    controller.context().checkThread();
    open = false;
    return CompletableFuture.completedFuture(null);
  }

  @Override
  public boolean isClosed() {
    return !open;
  }

  @Override
  public String toString() {
    return String.format("%s[context=%s]", getClass().getSimpleName(), controller.context());
  }

}
