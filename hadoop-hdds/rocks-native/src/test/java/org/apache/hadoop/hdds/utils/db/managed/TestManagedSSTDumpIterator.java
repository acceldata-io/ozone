/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hadoop.hdds.utils.db.managed;

import com.google.common.primitives.Bytes;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.hadoop.hdds.StringUtils;
import org.apache.hadoop.hdds.utils.NativeLibraryLoader;
import org.apache.hadoop.hdds.utils.TestUtils;
import org.apache.ozone.test.tag.Native;
import org.apache.ozone.test.tag.Unhealthy;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Matchers;
import org.mockito.Mockito;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.apache.hadoop.hdds.utils.NativeConstants.ROCKS_TOOLS_NATIVE_LIBRARY_NAME;

/**
 * Test for ManagedSSTDumpIterator.
 */
class TestManagedSSTDumpIterator {

  private File createSSTFileWithKeys(
      TreeMap<Pair<String, Integer>, String> keys) throws Exception {
    File file = File.createTempFile("tmp_sst_file", ".sst");
    file.deleteOnExit();
    try (ManagedEnvOptions envOptions = new ManagedEnvOptions();
         ManagedOptions managedOptions = new ManagedOptions();
         ManagedSstFileWriter sstFileWriter = new ManagedSstFileWriter(
             envOptions, managedOptions)) {
      sstFileWriter.open(file.getAbsolutePath());
      for (Map.Entry<Pair<String, Integer>, String> entry : keys.entrySet()) {
        if (entry.getKey().getValue() == 0) {
          sstFileWriter.delete(entry.getKey().getKey()
              .getBytes(StandardCharsets.UTF_8));
        } else {
          sstFileWriter.put(entry.getKey().getKey()
                  .getBytes(StandardCharsets.UTF_8),
              entry.getValue().getBytes(StandardCharsets.UTF_8));
        }
      }
      sstFileWriter.finish();
    }
    return file;
  }

  private static Stream<? extends Arguments> keyValueFormatArgs() {
    return Stream.of(
        Arguments.of(
            Named.of("Key starting with a single quote",
                "'key%1$d=>"),
            Named.of("Value starting with a number ending with a" +
                " single quote", "%1$dvalue'")
        ),
        Arguments.of(
            Named.of("Key ending with a number", "key%1$d"),
            Named.of("Value starting & ending with a number", "%1$dvalue%1$d")
        ),
        Arguments.of(
            Named.of("Key starting with a single quote & ending" +
                " with a number", "'key%1$d"),
            Named.of("Value starting & ending with a number " +
                "& elosed within quotes", "%1$d'value%1$d'")),
        Arguments.of(
            Named.of("Key starting with a single quote & ending" +
                " with a number", "'key%1$d"),
            Named.of("Value starting & ending with a number " +
                "& elosed within quotes", "%1$d'value%1$d'")
        ),
        Arguments.of(
            Named.of("Key ending with a number", "key%1$d"),
            Named.of("Value starting & ending with a number " +
                    "& containing null character & new line character",
                "%1$dvalue\n\0%1$d")
        ),
        Arguments.of(
            Named.of("Key ending with a number & containing" +
                " a null character", "key\0%1$d"),
            Named.of("Value starting & ending with a number " +
                "& elosed within quotes", "%1$dvalue\r%1$d")
        )
    );
  }

  private static byte[] getBytes(Integer val) {
    ByteBuffer destByteBuffer = ByteBuffer.allocate(4);
    destByteBuffer.order(ByteOrder.BIG_ENDIAN);
    destByteBuffer.putInt(val);
    return destByteBuffer.array();
  }

  private static byte[] getBytes(Long val) {
    ByteBuffer destByteBuffer = ByteBuffer.allocate(8);
    destByteBuffer.order(ByteOrder.BIG_ENDIAN);
    destByteBuffer.putLong(val);
    return destByteBuffer.array();
  }

  private static byte[] getBytes(String val) {
    byte[] b = new byte[val.length()];
    for (int i = 0; i < val.length(); i++) {
      b[i] = (byte) val.charAt(i);
    }
    return b;
  }

  private static Stream<? extends Arguments> invalidPipeInputStreamBytes() {
    return Stream.of(
        Arguments.of(Named.of("Invalid 3 byte integer",
            new byte[]{0, 0, 0})),
        Arguments.of(Named.of("Invalid 2 byte integer",
            new byte[]{0, 0})),
        Arguments.of(Named.of("Invalid 1 byte integer",
            new byte[]{0, 0})),
        Arguments.of(Named.of("Invalid key name length",
            Bytes.concat(getBytes(4), getBytes("key")))),
        Arguments.of(Named.of("Invalid Unsigned Long length",
            Bytes.concat(getBytes(4), getBytes("key1"),
                new byte[]{0, 0}))),
        Arguments.of(Named.of("Invalid Sequence number",
            Bytes.concat(getBytes(4), getBytes("key1")))),
        Arguments.of(Named.of("Invalid Type",
            Bytes.concat(getBytes(4), getBytes("key1"),
                getBytes(4L)))),
        Arguments.of(Named.of("Invalid Value",
            Bytes.concat(getBytes(4), getBytes("key"),
                getBytes(4L), getBytes(0)))),
        Arguments.of(Named.of("Invalid Value length",
            Bytes.concat(getBytes(4), getBytes("key"),
                getBytes(4L), getBytes(1), getBytes(6),
                getBytes("val"))))
    );
  }

  @Native(ROCKS_TOOLS_NATIVE_LIBRARY_NAME)
  @ParameterizedTest
  @MethodSource("keyValueFormatArgs")
  @Unhealthy("HDDS-9274")
  public void testSSTDumpIteratorWithKeyFormat(String keyFormat,
                                               String valueFormat)
      throws Exception {
    Assumptions.assumeTrue(NativeLibraryLoader.getInstance()
        .loadLibrary(ROCKS_TOOLS_NATIVE_LIBRARY_NAME));

    TreeMap<Pair<String, Integer>, String> keys =
        IntStream.range(0, 100).boxed().collect(
            Collectors.toMap(
                i -> Pair.of(String.format(keyFormat, i), i % 2),
                i -> i % 2 == 0 ? "" : String.format(valueFormat, i),
                (v1, v2) -> v2,
                TreeMap::new));
    File file = createSSTFileWithKeys(keys);
    ExecutorService executorService =
        new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(1),
            new ThreadPoolExecutor.CallerRunsPolicy());
    ManagedSSTDumpTool tool = new ManagedSSTDumpTool(executorService, 8192);
    List<Optional<String>> testBounds = TestUtils.getTestingBounds(
        keys.keySet().stream().collect(Collectors.toMap(Pair::getKey,
            Pair::getValue, (v1, v2) -> v1, TreeMap::new)));
    for (Optional<String> keyStart : testBounds) {
      for (Optional<String> keyEnd : testBounds) {
        Map<Pair<String, Integer>, String> expectedKeys = keys.entrySet()
            .stream().filter(e -> keyStart.map(s -> e.getKey().getKey()
                .compareTo(s) >= 0).orElse(true))
            .filter(e -> keyEnd.map(s -> e.getKey().getKey().compareTo(s) < 0)
                .orElse(true))
            .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        Optional<ManagedSlice> lowerBound = keyStart
            .map(s -> new ManagedSlice(StringUtils.string2Bytes(s)));
        Optional<ManagedSlice> upperBound = keyEnd
            .map(s -> new ManagedSlice(StringUtils.string2Bytes(s)));
        try (ManagedOptions options = new ManagedOptions();
             ManagedSSTDumpIterator<ManagedSSTDumpIterator.KeyValue> iterator =
            new ManagedSSTDumpIterator<ManagedSSTDumpIterator.KeyValue>(tool,
                file.getAbsolutePath(), options, lowerBound.orElse(null),
                upperBound.orElse(null)) {
              @Override
              protected KeyValue getTransformedValue(
                  Optional<KeyValue> value) {
                return value.orElse(null);
              }
            }
        ) {
          while (iterator.hasNext()) {
            ManagedSSTDumpIterator.KeyValue r = iterator.next();
            String key = new String(r.getKey(), StandardCharsets.UTF_8);
            Pair<String, Integer> recordKey = Pair.of(key, r.getType());
            Assertions.assertTrue(expectedKeys.containsKey(recordKey));
            Assertions.assertEquals(Optional.ofNullable(expectedKeys
                    .get(recordKey)).orElse(""),
                new String(r.getValue(), StandardCharsets.UTF_8));
            expectedKeys.remove(recordKey);
          }
          Assertions.assertEquals(0, expectedKeys.size());
        } finally {
          lowerBound.ifPresent(ManagedSlice::close);
          upperBound.ifPresent(ManagedSlice::close);
        }
      }
    }
    executorService.shutdown();
  }


  @ParameterizedTest
  @MethodSource("invalidPipeInputStreamBytes")
  public void testInvalidSSTDumpIteratorWithKeyFormat(byte[] inputBytes)
      throws ExecutionException,
      InterruptedException, IOException {
    ByteArrayInputStream byteArrayInputStream =
        new ByteArrayInputStream(inputBytes);
    ManagedSSTDumpTool tool = Mockito.mock(ManagedSSTDumpTool.class);
    File file = File.createTempFile("tmp", ".sst");
    Future future = Mockito.mock(Future.class);
    Mockito.when(future.isDone()).thenReturn(false);
    Mockito.when(future.get()).thenReturn(0);
    Mockito.when(tool.run(Matchers.any(Map.class),
            Matchers.any(ManagedOptions.class)))
        .thenReturn(new ManagedSSTDumpTool.SSTDumpToolTask(future,
            byteArrayInputStream));
    try (ManagedOptions options = new ManagedOptions()) {
      Assertions.assertThrows(IllegalStateException.class,
          () -> new ManagedSSTDumpIterator<ManagedSSTDumpIterator.KeyValue>(
              tool, file.getAbsolutePath(), options) {
            @Override
            protected KeyValue getTransformedValue(
                Optional<KeyValue> value) {
              return value.orElse(null);
            }
          });
    }
  }
}
