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
import io.atomix.copycat.server.state.CopycatInactiveState;
import io.atomix.copycat.server.state.ServerContext;
import io.atomix.copycat.server.state.ServerState;

/**
 * Inactive state controller.
 *
 * @author <a href="http://github.com/kuujo>Jordan Halterman</a>
 */
public class InactiveStateController extends ServerStateController {

  public InactiveStateController(ServerContext context) {
    super(context);
  }

  @Override
  public Member.Type type() {
    return CopycatMemberType.INACTIVE;
  }

  @Override
  protected ServerState initialState() {
    return new CopycatInactiveState(this);
  }

  @Override
  protected ServerState nextState() {
    throw new IllegalStateException("cannot transition inactive state");
  }

}
