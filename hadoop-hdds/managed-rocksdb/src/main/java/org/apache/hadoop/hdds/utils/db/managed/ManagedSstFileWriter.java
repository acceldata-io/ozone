/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 *  with the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */
package org.apache.hadoop.hdds.utils.db.managed;

import org.rocksdb.EnvOptions;
import org.rocksdb.Options;
import org.rocksdb.SstFileWriter;

import javax.annotation.Nullable;

import static org.apache.hadoop.hdds.utils.db.managed.ManagedRocksObjectUtils.assertClosed;
import static org.apache.hadoop.hdds.utils.db.managed.ManagedRocksObjectUtils.formatStackTrace;
import static org.apache.hadoop.hdds.utils.db.managed.ManagedRocksObjectUtils.getStackTrace;

/**
 * Managed SstFileWriter.
 */
public class ManagedSstFileWriter extends SstFileWriter {

  @Nullable
  private final StackTraceElement[] elements = getStackTrace();

  public ManagedSstFileWriter(EnvOptions envOptions,
                              Options options) {
    super(envOptions, options);
  }

  @Override
  protected void finalize() throws Throwable {
    assertClosed(this, formatStackTrace(elements));
    super.finalize();
  }
}
