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
package io.atomix.copycat.client.response;

import io.atomix.catalyst.serializer.SerializeWith;

/**
 * Protocol query response.
 *
 * @author <a href="http://github.com/kuujo">Jordan Halterman</a>
 */
@SerializeWith(id=198)
public class QueryResponse extends OperationResponse<QueryResponse> {

  /**
   * Returns a new query response builder.
   *
   * @return A new query response builder.
   */
  public static Builder builder() {
    return new Builder(new QueryResponse());
  }

  /**
   * Returns a query response builder for an existing request.
   *
   * @param response The response to build.
   * @return The query response builder.
   * @throws NullPointerException if {@code request} is null
   */
  public static Builder builder(QueryResponse response) {
    return new Builder(response);
  }

  /**
   * Query response builder.
   */
  public static class Builder extends OperationResponse.Builder<Builder, QueryResponse> {
    protected Builder(QueryResponse response) {
      super(response);
    }
  }

}
