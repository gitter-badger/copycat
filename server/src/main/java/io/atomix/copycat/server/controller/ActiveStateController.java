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

import io.atomix.copycat.server.cluster.CopycatMemberType;
import io.atomix.copycat.server.cluster.Member;
import io.atomix.copycat.server.state.*;

/**
 * Active state controller.
 *
 * @author <a href="http://github.com/kuujo>Jordan Halterman</a>
 */
public class ActiveStateController extends ServerStateController {
  private State state;

  private enum State {
    FOLLOWER,
    CANDIDATE,
    LEADER
  }

  public ActiveStateController(ServerContext context) {
    super(context);
  }

  @Override
  public Member.Type type() {
    return CopycatMemberType.ACTIVE;
  }

  @Override
  protected ServerState initialState() {
    this.state = State.FOLLOWER;
    return new CopycatFollowerState(this);
  }

  @Override
  protected ServerState nextState() {
    if (state == State.FOLLOWER) {
      state = State.CANDIDATE;
      return new CopycatCandidateState(this);
    } else if (state == State.CANDIDATE) {
      state = State.LEADER;
      return new CopycatLeaderState(this);
    } else {
      throw new IllegalStateException("cannot transition leader state");
    }
  }

}
