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

import io.atomix.copycat.server.CopycatServer;
import io.atomix.copycat.server.controller.ServerStateController;

/**
 * Passive state for Copycat.
 *
 * @author <a href="http://github.com/kuujo>Jordan Halterman</a>
 */
public final class CopycatPassiveState extends AbstractPassiveState {

  public CopycatPassiveState(ServerStateController controller) {
    super(controller);
  }

  @Override
  public Type type() {
    return CopycatServer.State.PASSIVE;
  }

}
