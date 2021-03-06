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
package io.atomix.copycat.server.storage;

import io.atomix.catalyst.buffer.Buffer;
import io.atomix.catalyst.buffer.FileBuffer;
import io.atomix.catalyst.buffer.HeapBuffer;

import java.io.File;

/**
 * Persists server state via the {@link Storage} module.
 * <p>
 * The server metastore is responsible for persisting server state according to the configured
 * {@link Storage#level() storage level}. Each server persists their current {@link #loadTerm() term}
 * and last {@link #loadVote() vote} as is dictated by the Raft consensus algorithm.
 *
 * @author <a href="http://github.com/kuujo>Jordan Halterman</a>
 */
public class MetaStore implements AutoCloseable {
  private final Buffer buffer;

  MetaStore(String name, Storage storage) {
    if (storage.level() == StorageLevel.MEMORY) {
      buffer = HeapBuffer.allocate(12);
    } else {
      storage.directory().mkdirs();
      File file = new File(storage.directory(), String.format("%s.meta", name));
      buffer = FileBuffer.allocate(file, 12);
    }
  }

  /**
   * Stores the current server term.
   *
   * @param term The current server term.
   * @return The metastore.
   */
  public MetaStore storeTerm(long term) {
    buffer.writeLong(0, term);
    return this;
  }

  /**
   * Loads the stored server term.
   *
   * @return The stored server term.
   */
  public long loadTerm() {
    return buffer.readLong(0);
  }

  /**
   * Stores the last voted server.
   *
   * @param vote The server vote.
   * @return The metastore.
   */
  public MetaStore storeVote(int vote) {
    buffer.writeInt(8, vote);
    return this;
  }

  /**
   * Loads the last vote for the server.
   *
   * @return The last vote for the server.
   */
  public int loadVote() {
    return buffer.readInt(8);
  }

  @Override
  public void close() {
    buffer.close();
  }

  /**
   * Deletes the metastore.
   */
  public void delete() {
    if (buffer instanceof FileBuffer) {
      ((FileBuffer) buffer).delete();
    }
  }

  @Override
  public String toString() {
    if (buffer instanceof FileBuffer) {
      return String.format("%s[%s]", getClass().getSimpleName(), ((FileBuffer) buffer).file());
    } else {
      return getClass().getSimpleName();
    }
  }

}
