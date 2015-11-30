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

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Copycat server model.
 *
 * @author <a href="http://github.com/kuujo>Jordan Halterman</a>
 */
public final class ServerModel {

  /**
   * Returns a new server model builder.
   *
   * @return A new server model builder.
   */
  public static Builder builder() {
    return new Builder();
  }

  private ServerType initialType;
  private final Map<Integer, ServerType> serverTypes;
  private ServerType finalType;
  private ServerType currentType;

  private ServerModel(ServerType initialType, Set<ServerType> serverTypes, ServerType finalType) {
    this.initialType = Assert.notNull(initialType, "initialType");
    this.serverTypes = new HashMap<>();
    for (ServerType serverType : serverTypes) {
      this.serverTypes.put(serverType.id(), serverType);
    }
    this.finalType = Assert.notNull(finalType, "finalType");
  }

  public ServerType initialize() {
    currentType = initialType;
    return currentType;
  }

  public ServerType transition(int type) {
    ServerType serverType = serverTypes.get(type);
    if (serverType == null)
      throw new IllegalArgumentException("unknown server type: " + type);
    currentType = serverType;
    return currentType;
  }

  public ServerType close() {
    currentType = finalType;
    return currentType;
  }

  /**
   * Server model builder.
   */
  public static final class Builder extends io.atomix.catalyst.util.Builder<ServerModel> {
    private ServerType initialType;
    private final Set<ServerType> serverTypes = new HashSet<>();
    private ServerType finalType;

    private Builder() {
    }

    public Builder withInitialServerType(ServerType type) {
      this.initialType = Assert.notNull(type, "type");
      return this;
    }

    /**
     * Adds a server type to the server model.
     *
     * @param type The server type.
     * @return The server model builder.
     */
    public Builder addTransitionalServerType(ServerType type) {
      serverTypes.add(Assert.notNull(type, "type"));
      return this;
    }

    public Builder withFinalServerType(ServerType type) {
      this.finalType = Assert.notNull(type, "type");
      return this;
    }

    @Override
    public ServerModel build() {
      return new ServerModel(initialType, serverTypes, finalType);
    }
  }

}
