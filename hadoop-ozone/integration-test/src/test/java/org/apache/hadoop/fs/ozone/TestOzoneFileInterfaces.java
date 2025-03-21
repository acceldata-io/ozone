/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hadoop.fs.ozone;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.apache.hadoop.conf.StorageUnit;
import org.apache.hadoop.fs.BlockLocation;
import org.apache.hadoop.fs.FSDataInputStream;
import org.apache.hadoop.fs.FSDataOutputStream;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.GlobalStorageStatistics;
import org.apache.hadoop.fs.LocatedFileStatus;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.StorageStatistics;
import org.apache.hadoop.fs.permission.FsPermission;
import org.apache.hadoop.hdds.conf.OzoneConfiguration;
import org.apache.hadoop.ozone.MiniOzoneCluster;
import org.apache.hadoop.ozone.OzoneConfigKeys;
import org.apache.hadoop.ozone.OzoneConsts;
import org.apache.hadoop.ozone.TestDataUtil;
import org.apache.hadoop.ozone.client.BucketArgs;
import org.apache.hadoop.ozone.client.ObjectStore;
import org.apache.hadoop.ozone.client.OzoneClient;
import org.apache.hadoop.ozone.client.OzoneVolume;
import org.apache.hadoop.ozone.om.OMConfigKeys;
import org.apache.hadoop.ozone.om.OMMetadataManager;
import org.apache.hadoop.ozone.om.OMMetrics;
import org.apache.hadoop.ozone.om.helpers.BucketLayout;
import org.apache.hadoop.ozone.om.helpers.OmKeyArgs;
import org.apache.hadoop.ozone.om.helpers.OzoneFileStatus;
import org.apache.hadoop.security.UserGroupInformation;
import org.apache.hadoop.util.Time;

import static java.nio.charset.StandardCharsets.UTF_8;
import org.apache.hadoop.hdds.utils.IOUtils;
import org.apache.commons.lang3.RandomStringUtils;

import static org.apache.hadoop.fs.CommonConfigurationKeysPublic.FS_DEFAULT_NAME_KEY;
import static org.apache.hadoop.fs.ozone.Constants.OZONE_DEFAULT_USER;

import org.junit.After;
import org.junit.AfterClass;

import static org.apache.hadoop.ozone.om.helpers.BucketLayout.FILE_SYSTEM_OPTIMIZED;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.junit.Assume.assumeFalse;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestRule;
import org.junit.rules.Timeout;
import org.apache.ozone.test.JUnit5AwareTimeout;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

/**
 * Test OzoneFileSystem Interfaces.
 *
 * This test will test the various interfaces i.e.
 * create, read, write, getFileStatus
 */
@RunWith(Parameterized.class)
public class TestOzoneFileInterfaces {

  /**
    * Set a timeout for each test.
    */
  @Rule
  public TestRule timeout = new JUnit5AwareTimeout(Timeout.seconds(300));

  private String rootPath;

  /**
   * Parameter class to set absolute url/defaultFS handling.
   * <p>
   * Hadoop file systems could be used in multiple ways: Using the defaultfs
   * and file path without the schema, or use absolute url-s even with
   * different defaultFS. This parameter matrix would test both the use cases.
   */
  @Parameters
  public static Collection<Object[]> data() {
    return Arrays.asList(new Object[][] {{false, true, true},
        {true, false, false}});
  }

  private static boolean setDefaultFs;

  private static boolean useAbsolutePath;

  private static MiniOzoneCluster cluster = null;

  private FileSystem fs;

  private OzoneFileSystem o3fs;

  private String volumeName;

  private String bucketName;

  private OzoneFSStorageStatistics statistics;

  private OMMetrics omMetrics;

  private static boolean enableFileSystemPaths;

  @SuppressWarnings("checkstyle:VisibilityModifier")
  protected boolean enableFileSystemPathsInstance;

  public TestOzoneFileInterfaces(boolean setDefaultFs,
      boolean useAbsolutePath, boolean enabledFileSystemPaths)
      throws Exception {
    enableFileSystemPathsInstance = enabledFileSystemPaths;
    if (this.setDefaultFs != setDefaultFs
        || this.useAbsolutePath != useAbsolutePath
        || this.enableFileSystemPaths != enabledFileSystemPaths) {
      setParameters(setDefaultFs, useAbsolutePath, enabledFileSystemPaths);
      teardown();
      init();
    }
    GlobalStorageStatistics.INSTANCE.reset();
  }

  private static void setParameters(boolean defaultFs,
                                    boolean absolutePath,
                                    boolean fileSystemPaths) {
    setDefaultFs = defaultFs;
    useAbsolutePath = absolutePath;
    enableFileSystemPaths = fileSystemPaths;
  }

  private static void setCluster(MiniOzoneCluster newCluster) {
    cluster = newCluster;
  }

  public void init() throws Exception {
    OzoneConfiguration conf = getOzoneConfiguration();
    conf.set(OMConfigKeys.OZONE_DEFAULT_BUCKET_LAYOUT,
        BucketLayout.LEGACY.name());
    MiniOzoneCluster newCluster = MiniOzoneCluster.newBuilder(conf)
        .setNumDatanodes(3)
        .build();
    newCluster.waitForClusterToBeReady();
    setCluster(newCluster);
  }

  @Before
  public void setupTest() throws Exception {
    volumeName = RandomStringUtils.randomAlphabetic(10).toLowerCase();
    bucketName = RandomStringUtils.randomAlphabetic(10).toLowerCase();

    OzoneConfiguration conf = cluster.getConf();

    // create a volume and a bucket to be used by OzoneFileSystem
    try (OzoneClient client = cluster.newClient()) {
      TestDataUtil.createVolumeAndBucket(client, volumeName, bucketName,
          getBucketLayout());
    }

    rootPath = String
        .format("%s://%s.%s/", OzoneConsts.OZONE_URI_SCHEME, bucketName,
            volumeName);
    if (setDefaultFs) {
      // Set the fs.defaultFS and start the filesystem
      conf.set(FS_DEFAULT_NAME_KEY, rootPath);
      fs = FileSystem.get(conf);
    } else {
      fs = FileSystem.get(new URI(rootPath + "/test.txt"), conf);
    }
    o3fs = (OzoneFileSystem) fs;
    statistics = (OzoneFSStorageStatistics) o3fs.getOzoneFSOpsCountStatistics();
    omMetrics = cluster.getOzoneManager().getMetrics();
  }

  protected OzoneConfiguration getOzoneConfiguration() {
    OzoneConfiguration conf = new OzoneConfiguration();
    conf.setBoolean(OMConfigKeys.OZONE_OM_ENABLE_FILESYSTEM_PATHS,
        enableFileSystemPaths);
    return conf;
  }

  @After
  public void closeFs() throws IOException {
    IOUtils.closeQuietly(fs);
  }

  @AfterClass
  public static void teardown() throws IOException {
    if (cluster != null) {
      cluster.shutdown();
    }
  }

  @Test
  public void testFileSystemInit() throws IOException {
    assumeFalse(FILE_SYSTEM_OPTIMIZED.equals(getBucketLayout()));

    if (setDefaultFs) {
      assertTrue(
          "The initialized file system is not OzoneFileSystem but " +
              fs.getClass(),
          fs instanceof OzoneFileSystem);
      assertEquals(OzoneConsts.OZONE_URI_SCHEME, fs.getUri().getScheme());
      assertEquals(OzoneConsts.OZONE_URI_SCHEME, statistics.getScheme());
    }
  }

  @Test
  public void testOzFsReadWrite() throws IOException {
    assumeFalse(FILE_SYSTEM_OPTIMIZED.equals(getBucketLayout()));

    long currentTime = Time.now();
    int stringLen = 20;
    OMMetadataManager metadataManager = cluster.getOzoneManager()
        .getMetadataManager();
    String lev1dir = "l1dir";
    Path lev1path = createPath("/" + lev1dir);
    String lev1key = metadataManager.getOzoneDirKey(volumeName, bucketName,
        o3fs.pathToKey(lev1path));
    String lev2dir = "l2dir";
    Path lev2path = createPath("/" + lev1dir + "/" + lev2dir);
    String lev2key = metadataManager.getOzoneDirKey(volumeName, bucketName,
        o3fs.pathToKey(lev2path));

    String data = RandomStringUtils.randomAlphanumeric(stringLen);
    String filePath = RandomStringUtils.randomAlphanumeric(5);

    Path path = createPath("/" + lev1dir + "/" + lev2dir + "/" + filePath);
    String fileKey = metadataManager.getOzoneDirKey(volumeName, bucketName,
        o3fs.pathToKey(path));

    // verify prefix directories and the file, do not already exist
    assertTrue(
        metadataManager.getKeyTable(getBucketLayout()).get(lev1key) == null);
    assertTrue(
        metadataManager.getKeyTable(getBucketLayout()).get(lev2key) == null);
    assertTrue(
        metadataManager.getKeyTable(getBucketLayout()).get(fileKey) == null);

    try (FSDataOutputStream stream = fs.create(path)) {
      stream.writeBytes(data);
    }

    assertEquals(statistics.getLong(
        StorageStatistics.CommonStatisticNames.OP_CREATE).longValue(), 1);
    assertEquals(statistics.getLong("objects_created").longValue(), 1);

    FileStatus status = fs.getFileStatus(path);
    assertEquals(statistics.getLong(
        StorageStatistics.CommonStatisticNames.OP_GET_FILE_STATUS).longValue(),
        1);
    assertEquals(statistics.getLong("objects_query").longValue(), 1);
    // The timestamp of the newly created file should always be greater than
    // the time when the test was started
    assertTrue("Modification time has not been recorded: " + status,
        status.getModificationTime() > currentTime);

    assertFalse(status.isDirectory());
    assertEquals(FsPermission.getFileDefault(), status.getPermission());
    verifyOwnerGroup(status);

    FileStatus lev1status;
    FileStatus lev2status;

    // verify prefix directories got created when creating the file.
    assertTrue(
        metadataManager.getKeyTable(getBucketLayout()).get(lev1key).getKeyName()
            .equals("l1dir/"));
    assertTrue(
        metadataManager.getKeyTable(getBucketLayout()).get(lev2key).getKeyName()
            .equals("l1dir/l2dir/"));
    lev1status = getDirectoryStat(lev1path);
    lev2status = getDirectoryStat(lev2path);
    assertTrue((lev1status != null) && (lev2status != null));

    try (FSDataInputStream inputStream = fs.open(path)) {
      byte[] buffer = new byte[stringLen];
      // This read will not change the offset inside the file
      int readBytes = inputStream.read(0, buffer, 0, buffer.length);
      String out = new String(buffer, 0, buffer.length, UTF_8);
      assertEquals(data, out);
      assertEquals(readBytes, buffer.length);
      assertEquals(0, inputStream.getPos());

      // The following read will change the internal offset
      readBytes = inputStream.read(buffer, 0, buffer.length);
      assertEquals(data, out);
      assertEquals(readBytes, buffer.length);
      assertEquals(buffer.length, inputStream.getPos());
    }
    assertEquals(statistics.getLong(
        StorageStatistics.CommonStatisticNames.OP_OPEN).longValue(), 1);
    assertEquals(statistics.getLong("objects_read").longValue(), 1);
  }

  @Test
  public void testReplication() throws IOException {
    assumeFalse(FILE_SYSTEM_OPTIMIZED.equals(getBucketLayout()));

    int stringLen = 20;
    String data = RandomStringUtils.randomAlphanumeric(stringLen);
    String filePath = RandomStringUtils.randomAlphanumeric(5);

    Path pathIllegal = createPath("/" + filePath + "illegal");
    try (FSDataOutputStream streamIllegal = fs.create(pathIllegal, (short)2)) {
      streamIllegal.writeBytes(data);
    }
    assertEquals(3, fs.getFileStatus(pathIllegal).getReplication());

    Path pathLegal = createPath("/" + filePath + "legal");
    try (FSDataOutputStream streamLegal = fs.create(pathLegal, (short)1)) {
      streamLegal.writeBytes(data);
    }
    assertEquals(1, fs.getFileStatus(pathLegal).getReplication());
  }

  private void verifyOwnerGroup(FileStatus fileStatus) {
    String owner = getCurrentUser();
    assertEquals(owner, fileStatus.getOwner());
    assertEquals(owner, fileStatus.getGroup());
  }


  @Test
  public void testDirectory() throws IOException {
    assumeFalse(FILE_SYSTEM_OPTIMIZED.equals(getBucketLayout()));

    String leafName = RandomStringUtils.randomAlphanumeric(5);
    OMMetadataManager metadataManager = cluster.getOzoneManager()
        .getMetadataManager();

    String lev1dir = "abc";
    Path lev1path = createPath("/" + lev1dir);
    String lev1key = metadataManager.getOzoneDirKey(volumeName, bucketName,
        o3fs.pathToKey(lev1path));
    String lev2dir = "def";
    Path lev2path = createPath("/" + lev1dir + "/" + lev2dir);
    String lev2key = metadataManager.getOzoneDirKey(volumeName, bucketName,
        o3fs.pathToKey(lev2path));

    FileStatus rootChild;
    FileStatus rootstatus;
    FileStatus leafstatus;

    Path leaf = createPath("/" + lev1dir + "/" + lev2dir + "/" + leafName);
    String leafKey = metadataManager.getOzoneDirKey(volumeName, bucketName,
        o3fs.pathToKey(leaf));

    // verify prefix directories and the leaf, do not already exist
    assertTrue(
        metadataManager.getKeyTable(getBucketLayout()).get(lev1key) == null);
    assertTrue(
        metadataManager.getKeyTable(getBucketLayout()).get(lev2key) == null);
    assertTrue(
        metadataManager.getKeyTable(getBucketLayout()).get(leafKey) == null);

    assertTrue("Makedirs returned with false for the path " + leaf,
        fs.mkdirs(leaf));

    // verify the leaf directory got created.
    leafstatus = getDirectoryStat(leaf);
    assertTrue(leafstatus != null);

    FileStatus lev1status;
    FileStatus lev2status;

    // verify prefix directories got created when creating the leaf directory.
    assertTrue(metadataManager
        .getKeyTable(getBucketLayout())
        .get(lev1key)
        .getKeyName().equals("abc/"));
    assertTrue(metadataManager
        .getKeyTable(getBucketLayout())
        .get(lev2key)
        .getKeyName().equals("abc/def/"));
    lev1status = getDirectoryStat(lev1path);
    lev2status = getDirectoryStat(lev2path);
    assertTrue((lev1status != null) && (lev2status != null));
    rootChild = lev1status;

    // check the root directory
    rootstatus = getDirectoryStat(createPath("/"));
    assertTrue(rootstatus != null);

    // root directory listing should contain the lev1 prefix directory
    FileStatus[] statusList = fs.listStatus(createPath("/"));
    assertEquals(1, statusList.length);
    assertEquals(rootChild, statusList[0]);
  }

  @Test
  public void testListStatus() throws IOException {
    List<Path> paths = new ArrayList<>();
    String dirPath = RandomStringUtils.randomAlphanumeric(5);
    Path path = createPath("/" + dirPath);
    paths.add(path);

    long mkdirs = statistics.getLong(
        StorageStatistics.CommonStatisticNames.OP_MKDIRS);
    assertTrue("Makedirs returned with false for the path " + path,
        fs.mkdirs(path));
    assertCounter(++mkdirs, StorageStatistics.CommonStatisticNames.OP_MKDIRS);

    long listObjects = statistics.getLong(Statistic.OBJECTS_LIST.getSymbol());
    long omListStatus = omMetrics.getNumListStatus();
    FileStatus[] statusList = fs.listStatus(createPath("/"));
    assertEquals(1, statusList.length);
    assertCounter(++listObjects, Statistic.OBJECTS_LIST.getSymbol());
    assertEquals(++omListStatus, omMetrics.getNumListStatus());
    assertEquals(fs.getFileStatus(path), statusList[0]);

    dirPath = RandomStringUtils.randomAlphanumeric(5);
    path = createPath("/" + dirPath);
    paths.add(path);
    assertTrue("Makedirs returned with false for the path " + path,
        fs.mkdirs(path));
    assertCounter(++mkdirs, StorageStatistics.CommonStatisticNames.OP_MKDIRS);

    statusList = fs.listStatus(createPath("/"));
    assertEquals(2, statusList.length);
    assertCounter(++listObjects, Statistic.OBJECTS_LIST.getSymbol());
    assertEquals(++omListStatus, omMetrics.getNumListStatus());
    for (Path p : paths) {
      assertTrue(Arrays.asList(statusList).contains(fs.getFileStatus(p)));
    }
  }

  @Test
  public void testOzoneManagerFileSystemInterface() throws IOException {
    String dirPath = RandomStringUtils.randomAlphanumeric(5);

    Path path = createPath("/" + dirPath);
    assertTrue("Makedirs returned with false for the path " + path,
        fs.mkdirs(path));

    long numFileStatus =
        cluster.getOzoneManager().getMetrics().getNumGetFileStatus();
    FileStatus status = fs.getFileStatus(path);

    assertEquals(numFileStatus + 1,
        cluster.getOzoneManager().getMetrics().getNumGetFileStatus());
    assertTrue(status.isDirectory());
    assertEquals(FsPermission.getDirDefault(), status.getPermission());
    verifyOwnerGroup(status);

    long currentTime = System.currentTimeMillis();
    OmKeyArgs keyArgs = new OmKeyArgs.Builder()
        .setVolumeName(volumeName)
        .setBucketName(bucketName)
        .setKeyName(o3fs.pathToKey(path))
        .build();
    OzoneFileStatus omStatus =
        cluster.getOzoneManager().getFileStatus(keyArgs);
    //Another get file status here, incremented the counter.
    assertEquals(numFileStatus + 2,
        cluster.getOzoneManager().getMetrics().getNumGetFileStatus());

    assertTrue("The created path is not directory.", omStatus.isDirectory());

    // For directories, the time returned is the current time when the dir key
    // doesn't actually exist on server; if it exists, it will be a fixed value.
    // In this case, the dir key exists.
    assertEquals(0, omStatus.getKeyInfo().getDataSize());
    assertTrue(omStatus.getKeyInfo().getModificationTime() <= currentTime);
    assertEquals(new Path(omStatus.getPath()).getName(),
        o3fs.pathToKey(path));
  }

  @Test
  public void testOzoneManagerLocatedFileStatus() throws IOException {
    String data = RandomStringUtils.randomAlphanumeric(20);
    String filePath = RandomStringUtils.randomAlphanumeric(5);
    Path path = createPath("/" + filePath);
    try (FSDataOutputStream stream = fs.create(path)) {
      stream.writeBytes(data);
    }
    FileStatus status = fs.getFileStatus(path);
    assertTrue(status instanceof LocatedFileStatus);
    LocatedFileStatus locatedFileStatus = (LocatedFileStatus) status;
    assertTrue(locatedFileStatus.getBlockLocations().length >= 1);

    for (BlockLocation blockLocation : locatedFileStatus.getBlockLocations()) {
      assertTrue(blockLocation.getNames().length >= 1);
      assertTrue(blockLocation.getHosts().length >= 1);
    }
  }

  @Test
  public void testOzoneManagerLocatedFileStatusBlockOffsetsWithMultiBlockFile()
      throws Exception {
    // naive assumption: MiniOzoneCluster will not have larger than ~1GB
    // block size when running this test.
    int blockSize = (int) fs.getConf().getStorageSize(
        OzoneConfigKeys.OZONE_SCM_BLOCK_SIZE,
        OzoneConfigKeys.OZONE_SCM_BLOCK_SIZE_DEFAULT,
        StorageUnit.BYTES
    );
    String data = RandomStringUtils.randomAlphanumeric(2 * blockSize + 837);
    String filePath = RandomStringUtils.randomAlphanumeric(5);
    Path path = createPath("/" + filePath);
    try (FSDataOutputStream stream = fs.create(path)) {
      stream.writeBytes(data);
    }
    FileStatus status = fs.getFileStatus(path);
    assertTrue(status instanceof LocatedFileStatus);
    LocatedFileStatus locatedFileStatus = (LocatedFileStatus) status;
    BlockLocation[] blockLocations = locatedFileStatus.getBlockLocations();

    assertEquals(0, blockLocations[0].getOffset());
    assertEquals(blockSize, blockLocations[1].getOffset());
    assertEquals(2 * blockSize, blockLocations[2].getOffset());
    assertEquals(blockSize, blockLocations[0].getLength());
    assertEquals(blockSize, blockLocations[1].getLength());
    assertEquals(837, blockLocations[2].getLength());
  }

  @Test
  public void testPathToKey() throws Exception {
    assumeFalse(FILE_SYSTEM_OPTIMIZED.equals(getBucketLayout()));

    assertEquals("a/b/1", o3fs.pathToKey(new Path("/a/b/1")));

    assertEquals("user/" + getCurrentUser() + "/key1/key2",
        o3fs.pathToKey(new Path("key1/key2")));

    assertEquals("key1/key2",
        o3fs.pathToKey(new Path("o3fs://test1/key1/key2")));
  }


  /**
   * Verify that FS throws exception when trying to access bucket with
   * incompatible layout.
   * @throws IOException
   */
  @Test
  public void testFileSystemWithObjectStoreLayout() throws IOException {
    String obsVolume = UUID.randomUUID().toString();

    try (OzoneClient client = cluster.newClient()) {
      ObjectStore store = client.getObjectStore();

      // Create volume and bucket
      store.createVolume(obsVolume);
      OzoneVolume volume = store.getVolume(obsVolume);
      String obsBucket = UUID.randomUUID().toString();
      // create bucket with OBJECT_STORE bucket layout (incompatible with fs)
      volume.createBucket(obsBucket,
          BucketArgs.newBuilder().setBucketLayout(BucketLayout.OBJECT_STORE)
              .build());

      String obsRootPath = String.format("%s://%s.%s/",
          OzoneConsts.OZONE_URI_SCHEME, obsBucket, obsVolume);

      OzoneConfiguration config = (OzoneConfiguration) fs.getConf();
      config.set(FS_DEFAULT_NAME_KEY, obsRootPath);

      try {
        fs = FileSystem.get(fs.getConf());
        fail("Should throw Exception due incompatible bucket layout");
      } catch (IllegalArgumentException iae) {
        // Expected exception
        assertTrue(iae.getMessage().contains(
            "OBJECT_STORE, which does not support file system semantics"));
      }
    }
  }

  private String getCurrentUser() {
    try {
      return UserGroupInformation.getCurrentUser().getShortUserName();
    } catch (IOException e) {
      return OZONE_DEFAULT_USER;
    }
  }

  private Path createPath(String relativePath) {
    if (useAbsolutePath) {
      return new Path(
          rootPath + (relativePath.startsWith("/") ? "" : "/") + relativePath);
    } else {
      return new Path(relativePath);
    }
  }

  /**
   * verify that a directory exists and is initialized correctly.
   * @param path of the directory
   * @return null indicates FILE_NOT_FOUND, else the FileStatus
   * @throws IOException
   */
  private FileStatus getDirectoryStat(Path path) throws IOException {

    FileStatus status = null;

    try {
      status = fs.getFileStatus(path);
    } catch (FileNotFoundException e) {
      return null;
    }
    assertTrue("The created path is not directory.", status.isDirectory());

    assertEquals(FsPermission.getDirDefault(), status.getPermission());
    verifyOwnerGroup(status);

    assertEquals(0, status.getLen());

    return status;
  }

  private void assertCounter(long value, String key) {
    assertEquals(value, statistics.getLong(key).longValue());
  }

  public BucketLayout getBucketLayout() {
    return BucketLayout.DEFAULT;
  }
}
