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

package org.apache.ozone.test;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.UnsupportedEncodingException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;

import com.google.common.base.Preconditions;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.log4j.Layout;
import org.apache.log4j.Level;
import org.apache.log4j.LogManager;
import org.apache.log4j.Logger;
import org.junit.jupiter.api.Assertions;
import org.mockito.Mockito;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.apache.commons.lang3.RandomStringUtils.randomAlphanumeric;
import static org.apache.logging.log4j.util.StackLocatorUtil.getCallerClass;

/**
 * Provides some very generic helpers which might be used across the tests.
 */
public abstract class GenericTestUtils {

  public static final String SYSPROP_TEST_DATA_DIR = "test.build.data";
  public static final String DEFAULT_TEST_DATA_DIR;
  public static final String DEFAULT_TEST_DATA_PATH = "target/test/data/";
  /**
   * Error string used in
   * {@link GenericTestUtils#waitFor(BooleanSupplier, int, int)}.
   */
  public static final String ERROR_MISSING_ARGUMENT =
      "Input supplier interface should be initialized";
  public static final String ERROR_INVALID_ARGUMENT =
      "Total wait time should be greater than check interval time";

  public static final boolean WINDOWS =
      System.getProperty("os.name").startsWith("Windows");

  private static final long NANOSECONDS_PER_MILLISECOND = 1_000_000;

  static {
    DEFAULT_TEST_DATA_DIR =
        "target" + File.separator + "test" + File.separator + "data";
  }

  /**
   * Get the (created) base directory for tests.
   *
   * @return the absolute directory
   */
  public static File getTestDir() {
    String prop =
        System.getProperty(SYSPROP_TEST_DATA_DIR, DEFAULT_TEST_DATA_DIR);
    if (prop.isEmpty()) {
      // corner case: property is there but empty
      prop = DEFAULT_TEST_DATA_DIR;
    }
    File dir = new File(prop).getAbsoluteFile();
    assertDirCreation(dir);
    return dir;
  }

  /**
   * Get an uncreated directory for tests.
   *
   * @return the absolute directory for tests. Caller is expected to create it.
   */
  public static File getTestDir(String subdir) {
    return new File(getTestDir(), subdir).getAbsoluteFile();
  }

  /**
   * Get an uncreated directory for tests with a randomized alphanumeric
   * name. This is likely to provide a unique path for tests run in parallel
   *
   * @return the absolute directory for tests. Caller is expected to create it.
   */
  public static File getRandomizedTestDir() {
    return new File(getRandomizedTempPath());
  }

  /**
   * Get a temp path. This may or may not be relative; it depends on what the
   * {@link #SYSPROP_TEST_DATA_DIR} is set to. If unset, it returns a path
   * under the relative path {@link #DEFAULT_TEST_DATA_PATH}
   *
   * @param subpath sub path, with no leading "/" character
   * @return a string to use in paths
   */
  public static String getTempPath(String subpath) {
    String prop = WINDOWS ? DEFAULT_TEST_DATA_PATH
        : System.getProperty(SYSPROP_TEST_DATA_DIR, DEFAULT_TEST_DATA_PATH);

    if (prop.isEmpty()) {
      // corner case: property is there but empty
      prop = DEFAULT_TEST_DATA_PATH;
    }
    if (!prop.endsWith("/")) {
      prop = prop + "/";
    }
    return prop + subpath;
  }

  /**
   * Get a temp path. This may or may not be relative; it depends on what the
   * {@link #SYSPROP_TEST_DATA_DIR} is set to. If unset, it returns a path
   * under the relative path {@link #DEFAULT_TEST_DATA_PATH}
   *
   * @return a string to use in paths
   */
  @SuppressWarnings("java:S2245") // no need for secure random
  public static String getRandomizedTempPath() {
    return getTempPath(getCallerClass(GenericTestUtils.class).getSimpleName()
        + "-" + randomAlphanumeric(10));
  }

  /**
   * Assert that a given file exists.
   */
  public static void assertExists(File f) {
    Assertions.assertTrue(f.exists(), "File " + f + " should exist");
  }

  /**
   * Assert that a given dir can be created or it already exists.
   */
  public static void assertDirCreation(File f) {
    Assertions.assertTrue(f.mkdirs() || f.exists(),
        "Could not create dir " + f + ", nor does it exist");
  }

  public static void assertExceptionContains(String expectedText, Throwable t) {
    assertExceptionContains(expectedText, t, "");
  }

  public static void assertExceptionContains(String expectedText, Throwable t,
      String message) {
    Assertions.assertNotNull(t, "Null Throwable");
    String msg = t.toString();
    if (msg == null) {
      throw new AssertionError("Null Throwable.toString() value", t);
    } else if (expectedText != null && !msg.contains(expectedText)) {
      String prefix = StringUtils.isEmpty(message) ? "" : message + ": ";
      throw new AssertionError(String
          .format("%s Expected to find '%s' %s: %s", prefix, expectedText,
              "but got unexpected exception",
              stringifyException(t)), t);
    }
  }

  /**
   * Make a string representation of the exception.
   * @param e The exception to stringify
   * @return A string with exception name and call stack.
   */
  public static String stringifyException(Throwable e) {
    StringWriter stm = new StringWriter();
    PrintWriter wrt = new PrintWriter(stm);
    e.printStackTrace(wrt);
    wrt.close();
    return stm.toString();
  }

  /**
   * Wait for the specified test to return true. The test will be performed
   * initially and then every {@code checkEveryMillis} until at least
   * {@code waitForMillis} time has expired. If {@code check} is null or
   * {@code waitForMillis} is less than {@code checkEveryMillis} this method
   * will throw an {@link IllegalArgumentException}.
   *
   * @param check            the test to perform
   * @param checkEveryMillis how often to perform the test
   * @param waitForMillis    the amount of time after which no more tests
   *                         will be
   *                         performed
   * @throws TimeoutException     if the test does not return true in the
   *                              allotted
   *                              time
   * @throws InterruptedException if the method is interrupted while waiting
   */
  public static void waitFor(BooleanSupplier check, int checkEveryMillis,
      int waitForMillis) throws TimeoutException, InterruptedException {
    Preconditions.checkNotNull(check, ERROR_MISSING_ARGUMENT);
    Preconditions.checkArgument(waitForMillis >= checkEveryMillis,
        ERROR_INVALID_ARGUMENT);

    long st = monotonicNow();
    boolean result = check.getAsBoolean();

    while (!result && (monotonicNow() - st < waitForMillis)) {
      Thread.sleep(checkEveryMillis);
      result = check.getAsBoolean();
    }

    if (!result) {
      throw new TimeoutException("Timed out waiting for condition. " +
          "Thread diagnostics:\n" +
          TimedOutTestsListener.buildThreadDiagnosticString());
    }
  }

  /**
   * @deprecated use sl4fj based version
   */
  @Deprecated
  public static void setLogLevel(Logger logger, Level level) {
    logger.setLevel(level);
  }

  public static void setLogLevel(org.slf4j.Logger logger,
      org.slf4j.event.Level level) {
    setLogLevel(toLog4j(logger), Level.toLevel(level.toString()));
  }

  public static void setRootLogLevel(org.slf4j.event.Level level) {
    setLogLevel(LogManager.getRootLogger(), Level.toLevel(level.toString()));
  }

  public static <T> T mockFieldReflection(Object object, String fieldName)
          throws NoSuchFieldException, IllegalAccessException {
    Field field = object.getClass().getDeclaredField(fieldName);
    boolean isAccessible = field.isAccessible();

    field.setAccessible(true);
    Field modifiersField = Field.class.getDeclaredField("modifiers");
    boolean modifierFieldAccessible = modifiersField.isAccessible();
    modifiersField.setAccessible(true);
    int modifierVal = modifiersField.getInt(field);
    modifiersField.setInt(field, field.getModifiers() & ~Modifier.FINAL);
    T value = (T) field.get(object);
    value = Mockito.spy(value);
    field.set(object, value);
    modifiersField.setInt(field, modifierVal);
    modifiersField.setAccessible(modifierFieldAccessible);
    field.setAccessible(isAccessible);
    return value;
  }

  public static <T> T getFieldReflection(Object object, String fieldName)
          throws NoSuchFieldException, IllegalAccessException {
    Field field = object.getClass().getDeclaredField(fieldName);
    boolean isAccessible = field.isAccessible();

    field.setAccessible(true);
    Field modifiersField = Field.class.getDeclaredField("modifiers");
    boolean modifierFieldAccessible = modifiersField.isAccessible();
    modifiersField.setAccessible(true);
    int modifierVal = modifiersField.getInt(field);
    modifiersField.setInt(field, field.getModifiers() & ~Modifier.FINAL);
    T value = (T) field.get(object);
    modifiersField.setInt(field, modifierVal);
    modifiersField.setAccessible(modifierFieldAccessible);
    field.setAccessible(isAccessible);
    return value;
  }

  public static <K, V> Map<V, K> getReverseMap(Map<K, List<V>> map) {
    return map.entrySet().stream().flatMap(entry -> entry.getValue().stream()
            .map(v -> Pair.of(v, entry.getKey())))
            .collect(Collectors.toMap(Pair::getKey, Pair::getValue));
  }

  /***
   * Removed all files and dirs in the given dir recursively.
   */
  public static boolean deleteDirectory(File dir) {
    File[] allContents = dir.listFiles();
    if (allContents != null) {
      for (File content : allContents) {
        if (!deleteDirectory(content)) {
          return false;
        }
      }
    }
    return dir.delete();
  }

  /**
   * Class to capture logs for doing assertions.
   */
  public abstract static class LogCapturer {
    private final StringWriter sw = new StringWriter();

    public static LogCapturer captureLogs(Logger logger) {
      return new Log4j1Capturer(logger);
    }

    public static LogCapturer captureLogs(Logger logger, Layout layout) {
      return new Log4j1Capturer(logger, layout);
    }

    public static LogCapturer captureLogs(org.slf4j.Logger logger) {
      return new Log4j1Capturer(toLog4j(logger));
    }

    // TODO: let Log4j2Capturer capture only specific logger's logs
    public static LogCapturer log4j2(String ignoredLoggerName) {
      return Log4j2Capturer.getInstance();
    }

    public String getOutput() {
      return writer().toString();
    }

    public abstract void stopCapturing();

    protected StringWriter writer() {
      return sw;
    }

    public void clearOutput() {
      writer().getBuffer().setLength(0);
    }
  }
  @Deprecated
  public static Logger toLog4j(org.slf4j.Logger logger) {
    return LogManager.getLogger(logger.getName());
  }

  private static long monotonicNow() {
    return System.nanoTime() / NANOSECONDS_PER_MILLISECOND;
  }

  /**
   * Capture output printed to {@link System#err}.
   * <p>
   * Usage:
   * <pre>
   *   try (SystemErrCapturer capture = new SystemErrCapturer()) {
   *     ...
   *     // Call capture.getOutput() to get the output string
   *   }
   * </pre>
   * <p>
   * TODO: Add lambda support once Java 8 is common.
   * <pre>
   *   SystemErrCapturer.withCapture(capture -> {
   *     ...
   *   })
   * </pre>
   */
  public static class SystemErrCapturer implements AutoCloseable {
    private final ByteArrayOutputStream bytes;
    private final PrintStream bytesPrintStream;
    private final PrintStream oldErr;

    public SystemErrCapturer() throws UnsupportedEncodingException {
      bytes = new ByteArrayOutputStream();
      bytesPrintStream = new PrintStream(bytes, false, UTF_8.name());
      oldErr = System.err;
      System.setErr(new TeePrintStream(oldErr, bytesPrintStream));
    }

    public String getOutput() throws UnsupportedEncodingException {
      return bytes.toString(UTF_8.name());
    }

    @Override
    public void close() throws Exception {
      IOUtils.closeQuietly(bytesPrintStream);
      System.setErr(oldErr);
    }
  }

  /**
   * Capture output printed to {@link System#out}.
   * <p>
   * Usage:
   * <pre>
   *   try (SystemOutCapturer capture = new SystemOutCapturer()) {
   *     ...
   *     // Call capture.getOutput() to get the output string
   *   }
   * </pre>
   * <p>
   * TODO: Add lambda support once Java 8 is common.
   * <pre>
   *   SystemOutCapturer.withCapture(capture -> {
   *     ...
   *   })
   * </pre>
   */
  public static class SystemOutCapturer implements AutoCloseable {
    private final ByteArrayOutputStream bytes;
    private final PrintStream bytesPrintStream;
    private final PrintStream oldOut;

    public SystemOutCapturer() throws
        UnsupportedEncodingException {
      bytes = new ByteArrayOutputStream();
      bytesPrintStream = new PrintStream(bytes, false, UTF_8.name());
      oldOut = System.out;
      System.setOut(new TeePrintStream(oldOut, bytesPrintStream));
    }

    public String getOutput() throws UnsupportedEncodingException {
      return bytes.toString(UTF_8.name());
    }

    @Override
    public void close() throws Exception {
      IOUtils.closeQuietly(bytesPrintStream);
      System.setOut(oldOut);
    }
  }

  /**
   * Prints output to one {@link PrintStream} while copying to the other.
   * <p>
   * Closing the main {@link PrintStream} will NOT close the other.
   */
  public static class TeePrintStream extends PrintStream {
    private final PrintStream other;

    public TeePrintStream(OutputStream main, PrintStream other)
        throws UnsupportedEncodingException {
      super(main, false, UTF_8.name());
      this.other = other;
    }

    @Override
    public void flush() {
      super.flush();
      other.flush();
    }

    @Override
    public void write(byte[] buf, int off, int len) {
      super.write(buf, off, len);
      other.write(buf, off, len);
    }
  }

}
