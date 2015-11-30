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
package io.atomix.copycat.server.state;

import io.atomix.catalyst.util.Assert;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Copycat server state model.
 *
 * @author <a href="http://github.com/kuujo>Jordan Halterman</a>
 */
public final class StateModel implements AutoCloseable {

  /**
   * Returns a new state model builder.
   *
   * @return A new state model builder.
   */
  public static Builder builder() {
    return new Builder();
  }

  private final ServerState.Type initialState;
  private final List<ServerState.Type> states;
  private Iterator<ServerState.Type> statesIterator;
  private ServerState.Type currentState;

  private StateModel(ServerState.Type initialState, List<ServerState.Type> states) {
    this.initialState = Assert.notNull(initialState, "initialState");
    this.states = states;
    this.statesIterator = states.iterator();
    this.currentState = initialState;
  }

  public ServerState.Type state() {
    return currentState;
  }

  public ServerState.Type next() {
    if (!statesIterator.hasNext())
      throw new IllegalStateException("invalid next state");
    currentState = statesIterator.next();
    return currentState;
  }

  public ServerState.Type reset() {
    statesIterator = states.iterator();
    return next();
  }

  @Override
  public void close() {
    currentState = initialState;
    statesIterator = states.iterator();
  }

  /**
   * State model builder.
   */
  public static class Builder extends io.atomix.catalyst.util.Builder<StateModel> {
    private ServerState.Type initialState;
    private final List<ServerState.Type> states = new ArrayList<>();

    private Builder() {
    }

    public Builder withInitialState(ServerState.Type state) {
      Assert.state(initialState == null, "initialState already configured");
      initialState = Assert.notNull(state, "state");
      states.add(state);
      return this;
    }

    public Builder withNextState(ServerState.Type state) {
      Assert.state(initialState != null, "initialState not configured");
      states.add(Assert.notNull(state, "state"));
      return this;
    }

    @Override
    public StateModel build() {
      return new StateModel(initialState, states);
    }
  }

}
