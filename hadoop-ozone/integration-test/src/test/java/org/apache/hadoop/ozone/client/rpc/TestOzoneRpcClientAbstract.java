/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with this
 * work for additional information regarding copyright ownership.  The ASF
 * licenses this file to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package org.apache.hadoop.ozone.client.rpc;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.security.PrivilegedExceptionAction;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import org.apache.hadoop.conf.StorageUnit;
import org.apache.hadoop.hdds.client.DefaultReplicationConfig;
import org.apache.hadoop.hdds.client.ECReplicationConfig;
import org.apache.hadoop.hdds.client.ECReplicationConfig.EcCodec;
import org.apache.hadoop.hdds.client.OzoneQuota;
import org.apache.hadoop.hdds.client.RatisReplicationConfig;
import org.apache.hadoop.hdds.client.ReplicationConfig;
import org.apache.hadoop.hdds.client.ReplicationFactor;
import org.apache.hadoop.hdds.client.ReplicationType;
import org.apache.hadoop.hdds.client.StandaloneReplicationConfig;
import org.apache.hadoop.hdds.conf.OzoneConfiguration;
import org.apache.hadoop.hdds.protocol.DatanodeDetails;
import org.apache.hadoop.hdds.protocol.StorageType;
import org.apache.hadoop.hdds.protocol.datanode.proto.ContainerProtos;
import org.apache.hadoop.hdds.protocol.proto.HddsProtos;
import org.apache.hadoop.hdds.scm.OzoneClientConfig;
import org.apache.hadoop.hdds.scm.XceiverClientGrpc;
import org.apache.hadoop.hdds.scm.client.HddsClientUtils;
import org.apache.hadoop.hdds.scm.container.ContainerID;
import org.apache.hadoop.hdds.scm.container.ContainerInfo;
import org.apache.hadoop.hdds.scm.pipeline.Pipeline;
import org.apache.hadoop.hdds.scm.protocolPB.StorageContainerLocationProtocolClientSideTranslatorPB;
import org.apache.hadoop.hdds.scm.server.StorageContainerManager;
import org.apache.hadoop.hdds.utils.db.Table;
import org.apache.hadoop.ozone.HddsDatanodeService;
import org.apache.hadoop.ozone.MiniOzoneCluster;
import org.apache.hadoop.ozone.OmUtils;
import org.apache.hadoop.ozone.OzoneAcl;
import org.apache.hadoop.ozone.OzoneConsts;
import org.apache.hadoop.ozone.OzoneTestUtils;
import org.apache.hadoop.ozone.client.BucketArgs;
import org.apache.hadoop.ozone.client.ObjectStore;
import org.apache.hadoop.ozone.client.OzoneBucket;
import org.apache.hadoop.ozone.client.OzoneClient;
import org.apache.hadoop.ozone.client.OzoneClientException;
import org.apache.hadoop.ozone.client.OzoneClientFactory;
import org.apache.hadoop.ozone.client.OzoneKey;
import org.apache.hadoop.ozone.client.OzoneKeyDetails;
import org.apache.hadoop.ozone.client.OzoneKeyLocation;
import org.apache.hadoop.ozone.client.OzoneMultipartUploadPartListParts;
import org.apache.hadoop.ozone.client.OzoneSnapshot;
import org.apache.hadoop.ozone.client.OzoneVolume;
import org.apache.hadoop.ozone.client.VolumeArgs;
import org.apache.hadoop.ozone.client.io.OzoneInputStream;
import org.apache.hadoop.ozone.client.io.OzoneOutputStream;
import org.apache.hadoop.ozone.client.protocol.ClientProtocol;
import org.apache.hadoop.ozone.common.OzoneChecksumException;
import org.apache.hadoop.ozone.container.common.helpers.BlockData;
import org.apache.hadoop.ozone.container.common.interfaces.BlockIterator;
import org.apache.hadoop.ozone.container.common.interfaces.Container;
import org.apache.hadoop.ozone.container.common.interfaces.DBHandle;
import org.apache.hadoop.ozone.container.keyvalue.KeyValueContainerData;
import org.apache.hadoop.ozone.container.keyvalue.helpers.BlockUtils;
import org.apache.hadoop.ozone.container.keyvalue.helpers.KeyValueContainerLocationUtil;
import org.apache.hadoop.ozone.om.OMConfigKeys;
import org.apache.hadoop.ozone.om.OMMetadataManager;
import org.apache.hadoop.ozone.om.OmFailoverProxyUtil;
import org.apache.hadoop.ozone.om.OzoneManager;
import org.apache.hadoop.ozone.om.ResolvedBucket;
import org.apache.hadoop.ozone.om.exceptions.OMException;
import org.apache.hadoop.ozone.om.exceptions.OMException.ResultCodes;
import org.apache.hadoop.ozone.om.ha.HadoopRpcOMFailoverProxyProvider;
import org.apache.hadoop.ozone.om.ha.OMProxyInfo;
import org.apache.hadoop.ozone.om.helpers.OmKeyArgs;
import org.apache.hadoop.ozone.om.helpers.OmKeyInfo;
import org.apache.hadoop.ozone.om.helpers.OmKeyLocationInfo;
import org.apache.hadoop.ozone.om.helpers.OmKeyLocationInfoGroup;
import org.apache.hadoop.ozone.om.helpers.OmMultipartCommitUploadPartInfo;
import org.apache.hadoop.ozone.om.helpers.OmMultipartInfo;
import org.apache.hadoop.ozone.om.helpers.OmMultipartUploadCompleteInfo;
import org.apache.hadoop.ozone.om.helpers.OmVolumeArgs;
import org.apache.hadoop.ozone.om.helpers.OzoneFSUtils;
import org.apache.hadoop.ozone.om.helpers.QuotaUtil;
import org.apache.hadoop.ozone.om.helpers.RepeatedOmKeyInfo;
import org.apache.hadoop.ozone.om.helpers.BucketLayout;
import org.apache.hadoop.ozone.security.acl.IAccessAuthorizer.ACLType;
import org.apache.hadoop.ozone.security.acl.OzoneAclConfig;
import org.apache.hadoop.ozone.security.acl.OzoneObj;
import org.apache.hadoop.ozone.security.acl.OzoneObjInfo;
import org.apache.hadoop.security.UserGroupInformation;
import org.apache.ozone.test.GenericTestUtils;
import org.apache.ozone.test.tag.Flaky;

import static java.nio.charset.StandardCharsets.UTF_8;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.RandomStringUtils;
import org.apache.commons.lang3.RandomUtils;
import org.apache.commons.lang3.StringUtils;
import static org.apache.hadoop.hdds.StringUtils.string2Bytes;
import static org.apache.hadoop.hdds.client.ReplicationFactor.ONE;
import static org.apache.hadoop.hdds.client.ReplicationFactor.THREE;
import static org.apache.hadoop.hdds.client.ReplicationType.RATIS;
import static org.apache.hadoop.ozone.OmUtils.MAX_TRXN_ID;
import static org.apache.hadoop.ozone.OzoneAcl.AclScope.ACCESS;
import static org.apache.hadoop.ozone.OzoneAcl.AclScope.DEFAULT;
import static org.apache.hadoop.ozone.OzoneConfigKeys.OZONE_SCM_BLOCK_SIZE;
import static org.apache.hadoop.ozone.OzoneConfigKeys.OZONE_SCM_BLOCK_SIZE_DEFAULT;
import static org.apache.hadoop.ozone.OzoneConsts.DEFAULT_OM_UPDATE_ID;
import static org.apache.hadoop.ozone.OzoneConsts.GB;
import static org.apache.hadoop.ozone.om.exceptions.OMException.ResultCodes.KEY_NOT_FOUND;
import static org.apache.hadoop.ozone.om.exceptions.OMException.ResultCodes.NO_SUCH_MULTIPART_UPLOAD_ERROR;
import static org.apache.hadoop.ozone.om.exceptions.OMException.ResultCodes.PARTIAL_RENAME;
import static org.apache.hadoop.ozone.security.acl.IAccessAuthorizer.ACLIdentityType.GROUP;
import static org.apache.hadoop.ozone.security.acl.IAccessAuthorizer.ACLIdentityType.USER;
import static org.apache.hadoop.ozone.security.acl.IAccessAuthorizer.ACLType.READ;

import static org.apache.hadoop.ozone.security.acl.IAccessAuthorizer.ACLType.WRITE;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.slf4j.event.Level.DEBUG;

import org.apache.ozone.test.tag.Unhealthy;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * This is an abstract class to test all the public facing APIs of Ozone
 * Client, w/o OM Ratis server.
 * {@link TestOzoneRpcClient} tests the Ozone Client by submitting the
 * requests directly to OzoneManager. {@link TestOzoneRpcClientWithRatis}
 * tests the Ozone Client by submitting requests to OM's Ratis server.
 */
@TestMethodOrder(MethodOrderer.MethodName.class)
public abstract class TestOzoneRpcClientAbstract {

  private static MiniOzoneCluster cluster = null;
  private static OzoneClient ozClient = null;
  private static ObjectStore store = null;
  private static OzoneManager ozoneManager;
  private static StorageContainerLocationProtocolClientSideTranslatorPB
      storageContainerLocationClient;
  private static String remoteUserName = "remoteUser";
  private static String remoteGroupName = "remoteGroup";
  private static OzoneAcl defaultUserAcl = new OzoneAcl(USER, remoteUserName,
      READ, DEFAULT);
  private static OzoneAcl defaultGroupAcl = new OzoneAcl(GROUP, remoteGroupName,
      READ, DEFAULT);
  private static OzoneAcl inheritedUserAcl = new OzoneAcl(USER, remoteUserName,
      READ, ACCESS);
  private static OzoneAcl inheritedGroupAcl = new OzoneAcl(GROUP,
      remoteGroupName, READ, ACCESS);

  private static String scmId = UUID.randomUUID().toString();
  private static String clusterId;


  /**
   * Create a MiniOzoneCluster for testing.
   * @param conf Configurations to start the cluster.
   * @throws Exception
   */
  static void startCluster(OzoneConfiguration conf) throws Exception {
    // Reduce long wait time in MiniOzoneClusterImpl#waitForHddsDatanodesStop
    //  for testZReadKeyWithUnhealthyContainerReplica.
    clusterId = UUID.randomUUID().toString();
    conf.set("ozone.scm.stale.node.interval", "10s");
    cluster = MiniOzoneCluster.newBuilder(conf)
        .setNumDatanodes(14)
        .setTotalPipelineNumLimit(10)
        .setScmId(scmId)
        .setClusterId(clusterId)
        .setDataStreamMinPacketSize(1) // 1MB
        .build();
    cluster.waitForClusterToBeReady();
    ozClient = OzoneClientFactory.getRpcClient(conf);
    store = ozClient.getObjectStore();
    storageContainerLocationClient =
        cluster.getStorageContainerLocationClient();
    ozoneManager = cluster.getOzoneManager();
  }

  /**
   * Close OzoneClient and shutdown MiniOzoneCluster.
   */
  static void shutdownCluster() throws IOException {
    if (ozClient != null) {
      ozClient.close();
    }

    if (storageContainerLocationClient != null) {
      storageContainerLocationClient.close();
    }

    if (cluster != null) {
      cluster.shutdown();
    }
  }

  public static void setCluster(MiniOzoneCluster cluster) {
    TestOzoneRpcClientAbstract.cluster = cluster;
  }

  public static void setOzClient(OzoneClient ozClient) {
    TestOzoneRpcClientAbstract.ozClient = ozClient;
  }

  public static void setOzoneManager(OzoneManager ozoneManager) {
    TestOzoneRpcClientAbstract.ozoneManager = ozoneManager;
  }

  public static void setStorageContainerLocationClient(
      StorageContainerLocationProtocolClientSideTranslatorPB
          storageContainerLocationClient) {
    TestOzoneRpcClientAbstract.storageContainerLocationClient =
        storageContainerLocationClient;
  }

  public static void setStore(ObjectStore store) {
    TestOzoneRpcClientAbstract.store = store;
  }

  public static ObjectStore getStore() {
    return TestOzoneRpcClientAbstract.store;
  }

  public static void setClusterId(String clusterId) {
    TestOzoneRpcClientAbstract.clusterId = clusterId;
  }

  public static OzoneClient getClient() {
    return TestOzoneRpcClientAbstract.ozClient;
  }

  public static MiniOzoneCluster getCluster() {
    return TestOzoneRpcClientAbstract.cluster;
  }
  /**
   * Test OM Proxy Provider.
   */
  @Test
  public void testOMClientProxyProvider() {

    HadoopRpcOMFailoverProxyProvider omFailoverProxyProvider =
        OmFailoverProxyUtil.getFailoverProxyProvider(store.getClientProxy());

    List<OMProxyInfo> omProxies = omFailoverProxyProvider.getOMProxyInfos();

    // For a non-HA OM service, there should be only one OM proxy.
    assertEquals(1, omProxies.size());
    // The address in OMProxyInfo object, which client will connect to,
    // should match the OM's RPC address.
    assertEquals(omProxies.get(0).getAddress(),
        ozoneManager.getOmRpcServerAddr());
  }

  @Test
  public void testDefaultS3GVolumeExists() throws Exception {
    String s3VolumeName =
        HddsClientUtils.getDefaultS3VolumeName(cluster.getConf());
    OzoneVolume ozoneVolume = store.getVolume(s3VolumeName);
    assertEquals(ozoneVolume.getName(), s3VolumeName);
    OMMetadataManager omMetadataManager =
        cluster.getOzoneManager().getMetadataManager();
    long transactionID = MAX_TRXN_ID + 1;
    long objectID = OmUtils.addEpochToTxId(omMetadataManager.getOmEpoch(),
        transactionID);
    OmVolumeArgs omVolumeArgs =
        cluster.getOzoneManager().getMetadataManager().getVolumeTable().get(
            omMetadataManager.getVolumeKey(s3VolumeName));
    assertEquals(objectID, omVolumeArgs.getObjectID());
    assertEquals(DEFAULT_OM_UPDATE_ID, omVolumeArgs.getUpdateID());
  }

  @Test
  public void testVolumeSetOwner() throws IOException {
    String volumeName = UUID.randomUUID().toString();
    store.createVolume(volumeName);

    String ownerName = "someRandomUser1";

    ClientProtocol proxy = store.getClientProxy();
    proxy.setVolumeOwner(volumeName, ownerName);
    // Set owner again
    proxy.setVolumeOwner(volumeName, ownerName);
  }

  @Test
  public void testBucketSetOwner() throws IOException {
    String volumeName = UUID.randomUUID().toString();
    String bucketName = UUID.randomUUID().toString();
    store.createVolume(volumeName);
    store.getVolume(volumeName).createBucket(bucketName);

    String oldOwner = store.getVolume(volumeName).getBucket(bucketName)
        .getOwner();
    String ownerName = "testUser";

    ClientProtocol proxy = store.getClientProxy();
    proxy.setBucketOwner(volumeName, bucketName, ownerName);
    String newOwner = store.getVolume(volumeName).getBucket(bucketName)
        .getOwner();

    assertEquals(ownerName, newOwner);
    assertNotEquals(oldOwner, newOwner);
    store.getVolume(volumeName).deleteBucket(bucketName);
    store.deleteVolume(volumeName);
  }

  @Test
  public void testSetAndClrQuota() throws Exception {
    String volumeName = UUID.randomUUID().toString();
    String bucketName = UUID.randomUUID().toString();
    String bucketName2 = UUID.randomUUID().toString();
    String value = "sample value";
    int valueLength = value.getBytes(UTF_8).length;
    OzoneVolume volume = null;
    store.createVolume(volumeName);

    store.getVolume(volumeName).createBucket(bucketName);
    OzoneBucket bucket = store.getVolume(volumeName).getBucket(bucketName);
    assertEquals(OzoneConsts.QUOTA_RESET, bucket.getQuotaInBytes());
    assertEquals(OzoneConsts.QUOTA_RESET, bucket.getQuotaInNamespace());

    store.getVolume(volumeName).getBucket(bucketName).setQuota(
        OzoneQuota.parseQuota("1GB", "1000"));
    OzoneBucket ozoneBucket = store.getVolume(volumeName).getBucket(bucketName);
    assertEquals(1024 * 1024 * 1024,
        ozoneBucket.getQuotaInBytes());
    assertEquals(1000L, ozoneBucket.getQuotaInNamespace());

    store.getVolume(volumeName).createBucket(bucketName2);
    store.getVolume(volumeName).getBucket(bucketName2).setQuota(
        OzoneQuota.parseQuota("1024", "1000"));
    OzoneBucket ozoneBucket2 =
        store.getVolume(volumeName).getBucket(bucketName2);
    assertEquals(1024L, ozoneBucket2.getQuotaInBytes());

    store.getVolume(volumeName).setQuota(OzoneQuota.parseQuota(
        "10GB", "10000"));
    volume = store.getVolume(volumeName);
    assertEquals(10 * GB, volume.getQuotaInBytes());
    assertEquals(10000L, volume.getQuotaInNamespace());

    IOException ioException = assertThrows(IOException.class,
        ozoneBucket::clearSpaceQuota);
    assertEquals("Can not clear bucket spaceQuota because volume" +
        " spaceQuota is not cleared.", ioException.getMessage());

    writeKey(bucket, UUID.randomUUID().toString(), ONE, value, valueLength);
    assertEquals(1L,
        store.getVolume(volumeName).getBucket(bucketName).getUsedNamespace());
    assertEquals(valueLength,
        store.getVolume(volumeName).getBucket(bucketName).getUsedBytes());
    assertEquals(2L,
        store.getVolume(volumeName).getUsedNamespace());

    store.getVolume(volumeName).clearSpaceQuota();
    store.getVolume(volumeName).clearNamespaceQuota();
    OzoneVolume clrVolume = store.getVolume(volumeName);
    assertEquals(OzoneConsts.QUOTA_RESET, clrVolume.getQuotaInBytes());
    assertEquals(OzoneConsts.QUOTA_RESET,
        clrVolume.getQuotaInNamespace());

    ozoneBucket.clearSpaceQuota();
    ozoneBucket.clearNamespaceQuota();
    OzoneBucket clrBucket = store.getVolume(volumeName).getBucket(bucketName);
    assertEquals(OzoneConsts.QUOTA_RESET, clrBucket.getQuotaInBytes());
    assertEquals(OzoneConsts.QUOTA_RESET,
        clrBucket.getQuotaInNamespace());
    assertEquals(1L,
        store.getVolume(volumeName).getBucket(bucketName).getUsedNamespace());
    assertEquals(valueLength,
        store.getVolume(volumeName).getBucket(bucketName).getUsedBytes());
    assertEquals(2L,
        store.getVolume(volumeName).getUsedNamespace());
  }

  @Test
  public void testSetBucketQuotaIllegal() throws Exception {
    String volumeName = UUID.randomUUID().toString();
    String bucketName = UUID.randomUUID().toString();
    store.createVolume(volumeName);
    store.getVolume(volumeName).createBucket(bucketName);

    IllegalArgumentException exception =
        assertThrows(IllegalArgumentException.class,
            () -> store.getVolume(volumeName).getBucket(bucketName)
                .setQuota(OzoneQuota.parseQuota("0GB", "10")));
    assertTrue(exception.getMessage()
        .startsWith("Invalid value for space quota"));

    exception = assertThrows(IllegalArgumentException.class,
        () -> store.getVolume(volumeName).getBucket(bucketName).setQuota(
            OzoneQuota.parseQuota("10GB", "0")));
    assertTrue(exception.getMessage()
        .startsWith("Invalid value for namespace quota"));

    exception = assertThrows(IllegalArgumentException.class,
        () -> store.getVolume(volumeName).getBucket(bucketName).setQuota(
            OzoneQuota.parseQuota("1GB", "-100")));
    assertTrue(exception.getMessage()
        .startsWith("Invalid value for namespace quota"));

    exception = assertThrows(IllegalArgumentException.class,
        () -> store.getVolume(volumeName).getBucket(bucketName).setQuota(
            OzoneQuota.parseQuota("1TEST", "100")));
    assertTrue(exception.getMessage()
        .startsWith("1TEST is invalid."));

    exception = assertThrows(IllegalArgumentException.class,
        () -> store.getVolume(volumeName).getBucket(bucketName).setQuota(
            OzoneQuota.parseQuota("9223372036854775808 BYTES", "100")));
    assertTrue(exception.getMessage()
        .startsWith("9223372036854775808 BYTES is invalid."));

    exception = assertThrows(IllegalArgumentException.class,
        () -> store.getVolume(volumeName).getBucket(bucketName).setQuota(
            OzoneQuota.parseQuota("-10GB", "100")));
    assertTrue(exception.getMessage()
        .startsWith("Invalid value for space quota"));

  }

  @Test
  public void testSetVolumeQuota() throws IOException {
    String volumeName = UUID.randomUUID().toString();
    store.createVolume(volumeName);
    assertEquals(OzoneConsts.QUOTA_RESET,
        store.getVolume(volumeName).getQuotaInBytes());
    assertEquals(OzoneConsts.QUOTA_RESET,
        store.getVolume(volumeName).getQuotaInNamespace());
    store.getVolume(volumeName).setQuota(OzoneQuota.parseQuota("1GB", "1000"));
    OzoneVolume volume = store.getVolume(volumeName);
    assertEquals(1024 * 1024 * 1024,
        volume.getQuotaInBytes());
    assertEquals(1000L, volume.getQuotaInNamespace());
  }

  @Test
  public void testSetVolumeQuotaIllegal() throws Exception {
    String volumeName = UUID.randomUUID().toString();

    VolumeArgs volumeArgs = VolumeArgs.newBuilder()
        .addMetadata("key1", "val1")
        .setQuotaInNamespace(0)
        .setQuotaInBytes(0)
        .build();
    IllegalArgumentException exception = assertThrows(
        IllegalArgumentException.class,
        () -> store.createVolume(volumeName, volumeArgs));
    assertTrue(exception.getMessage()
        .startsWith("Invalid values for quota"));

    store.createVolume(volumeName);

    // test volume set quota 0
    exception = assertThrows(IllegalArgumentException.class,
        () -> store.getVolume(volumeName).setQuota(OzoneQuota.parseQuota(
            "0GB", "10")));
    assertTrue(exception.getMessage()
        .startsWith("Invalid value for space quota"));
    exception = assertThrows(IllegalArgumentException.class,
        () -> store.getVolume(volumeName).setQuota(OzoneQuota.parseQuota(
            "10GB", "0")));
    assertTrue(exception.getMessage()
        .startsWith("Invalid value for namespace quota"));

    // The unit should be legal.
    exception = assertThrows(IllegalArgumentException.class,
        () -> store.getVolume(volumeName).setQuota(OzoneQuota.parseQuota(
            "1TEST", "1000")));
    assertTrue(exception.getMessage()
        .startsWith("1TEST is invalid."));

    // The setting value cannot be greater than LONG.MAX_VALUE BYTES.
    exception = assertThrows(IllegalArgumentException.class,
        () -> store.getVolume(volumeName).setQuota(OzoneQuota.parseQuota(
            "9223372036854775808 B", "1000")));
    assertTrue(exception.getMessage()
        .startsWith("9223372036854775808 B is invalid."));

    // The value cannot be negative.
    exception = assertThrows(IllegalArgumentException.class,
        () -> store.getVolume(volumeName).setQuota(OzoneQuota.parseQuota(
            "-10GB", "1000")));
    assertTrue(exception.getMessage()
        .startsWith("Invalid value for space quota"));
  }

  @Test
  public void testDeleteVolume()
      throws Exception {
    String volumeName = UUID.randomUUID().toString();
    store.createVolume(volumeName);
    OzoneVolume volume = store.getVolume(volumeName);
    assertNotNull(volume);
    store.deleteVolume(volumeName);
    OzoneTestUtils.expectOmException(ResultCodes.VOLUME_NOT_FOUND,
        () -> store.getVolume(volumeName));

  }

  @Test
  public void testCreateVolumeWithMetadata()
      throws IOException, OzoneClientException {
    String volumeName = UUID.randomUUID().toString();
    VolumeArgs volumeArgs = VolumeArgs.newBuilder()
        .addMetadata("key1", "val1")
        .build();
    store.createVolume(volumeName, volumeArgs);
    OzoneVolume volume = store.getVolume(volumeName);
    assertEquals(OzoneConsts.QUOTA_RESET, volume.getQuotaInNamespace());
    assertEquals(OzoneConsts.QUOTA_RESET, volume.getQuotaInBytes());
    assertEquals("val1", volume.getMetadata().get("key1"));
    assertEquals(volumeName, volume.getName());
  }

  @Test
  public void testCreateBucketWithMetadata()
      throws IOException, OzoneClientException {
    String volumeName = UUID.randomUUID().toString();
    String bucketName = UUID.randomUUID().toString();
    store.createVolume(volumeName);
    OzoneVolume volume = store.getVolume(volumeName);
    assertEquals(OzoneConsts.QUOTA_RESET, volume.getQuotaInNamespace());
    assertEquals(OzoneConsts.QUOTA_RESET, volume.getQuotaInBytes());
    BucketArgs args = BucketArgs.newBuilder()
        .addMetadata("key1", "value1").build();
    volume.createBucket(bucketName, args);
    OzoneBucket bucket = volume.getBucket(bucketName);
    assertEquals(bucketName, bucket.getName());
    assertEquals(OzoneConsts.QUOTA_RESET, bucket.getQuotaInNamespace());
    assertEquals(OzoneConsts.QUOTA_RESET, bucket.getQuotaInBytes());
    assertNotNull(bucket.getMetadata());
    assertEquals("value1", bucket.getMetadata().get("key1"));

  }


  @Test
  public void testCreateBucket()
      throws IOException {
    Instant testStartTime = Instant.now();
    String volumeName = UUID.randomUUID().toString();
    String bucketName = UUID.randomUUID().toString();
    store.createVolume(volumeName);
    OzoneVolume volume = store.getVolume(volumeName);
    volume.createBucket(bucketName);
    OzoneBucket bucket = volume.getBucket(bucketName);
    assertEquals(bucketName, bucket.getName());
    assertFalse(bucket.getCreationTime().isBefore(testStartTime));
    assertFalse(volume.getCreationTime().isBefore(testStartTime));
  }

  @Test
  public void testCreateS3Bucket()
      throws IOException {
    Instant testStartTime = Instant.now();
    String bucketName = UUID.randomUUID().toString();
    store.createS3Bucket(bucketName);
    OzoneBucket bucket = store.getS3Bucket(bucketName);
    assertEquals(bucketName, bucket.getName());
    assertFalse(bucket.getCreationTime().isBefore(testStartTime));
    assertEquals(BucketLayout.OBJECT_STORE, bucket.getBucketLayout());
  }

  @Test
  public void testDeleteS3Bucket()
      throws Exception {
    Instant testStartTime = Instant.now();
    String bucketName = UUID.randomUUID().toString();
    store.createS3Bucket(bucketName);
    OzoneBucket bucket = store.getS3Bucket(bucketName);
    assertEquals(bucketName, bucket.getName());
    assertFalse(bucket.getCreationTime().isBefore(testStartTime));
    store.deleteS3Bucket(bucketName);

    OzoneTestUtils.expectOmException(ResultCodes.BUCKET_NOT_FOUND,
        () -> store.getS3Bucket(bucketName));
  }

  @Test
  public void testDeleteS3NonExistingBucket() {
    try {
      store.deleteS3Bucket(UUID.randomUUID().toString());
    } catch (IOException ex) {
      GenericTestUtils.assertExceptionContains("NOT_FOUND", ex);
    }
  }

  @Test
  public void testCreateBucketWithVersioning()
      throws IOException {
    String volumeName = UUID.randomUUID().toString();
    String bucketName = UUID.randomUUID().toString();
    store.createVolume(volumeName);
    OzoneVolume volume = store.getVolume(volumeName);
    BucketArgs.Builder builder = BucketArgs.newBuilder();
    builder.setVersioning(true);
    volume.createBucket(bucketName, builder.build());
    OzoneBucket bucket = volume.getBucket(bucketName);
    assertEquals(bucketName, bucket.getName());
    assertEquals(true, bucket.getVersioning());
  }

  @Test
  public void testCreateBucketWithStorageType()
      throws IOException {
    String volumeName = UUID.randomUUID().toString();
    String bucketName = UUID.randomUUID().toString();
    store.createVolume(volumeName);
    OzoneVolume volume = store.getVolume(volumeName);
    BucketArgs.Builder builder = BucketArgs.newBuilder();
    builder.setStorageType(StorageType.SSD);
    volume.createBucket(bucketName, builder.build());
    OzoneBucket bucket = volume.getBucket(bucketName);
    assertEquals(bucketName, bucket.getName());
    assertEquals(StorageType.SSD, bucket.getStorageType());
  }

  @Test
  public void testCreateBucketWithAcls()
      throws IOException {
    String volumeName = UUID.randomUUID().toString();
    String bucketName = UUID.randomUUID().toString();
    OzoneAcl userAcl = new OzoneAcl(USER, "test",
        READ, ACCESS);
    List<OzoneAcl> acls = new ArrayList<>();
    acls.add(userAcl);
    store.createVolume(volumeName);
    OzoneVolume volume = store.getVolume(volumeName);
    BucketArgs.Builder builder = BucketArgs.newBuilder();
    builder.setAcls(acls);
    volume.createBucket(bucketName, builder.build());
    OzoneBucket bucket = volume.getBucket(bucketName);
    assertEquals(bucketName, bucket.getName());
    assertTrue(bucket.getAcls().contains(userAcl));
  }

  @Test
  public void testCreateBucketWithReplicationConfig()
      throws IOException {
    String volumeName = UUID.randomUUID().toString();
    String bucketName = UUID.randomUUID().toString();
    ReplicationConfig repConfig = new ECReplicationConfig(3, 2);
    store.createVolume(volumeName);
    OzoneVolume volume = store.getVolume(volumeName);
    BucketArgs bucketArgs = BucketArgs.newBuilder()
        .setDefaultReplicationConfig(new DefaultReplicationConfig(repConfig))
        .build();
    volume.createBucket(bucketName, bucketArgs);
    OzoneBucket bucket = volume.getBucket(bucketName);
    assertEquals(bucketName, bucket.getName());
    assertEquals(repConfig, bucket.getReplicationConfig());
  }

  @Test
  public void testCreateBucketWithAllArgument()
      throws IOException {
    String volumeName = UUID.randomUUID().toString();
    String bucketName = UUID.randomUUID().toString();
    OzoneAcl userAcl = new OzoneAcl(USER, "test",
        ACLType.ALL, ACCESS);
    List<OzoneAcl> acls = new ArrayList<>();
    acls.add(userAcl);
    ReplicationConfig repConfig = new ECReplicationConfig(3, 2);
    store.createVolume(volumeName);
    OzoneVolume volume = store.getVolume(volumeName);
    BucketArgs.Builder builder = BucketArgs.newBuilder();
    builder.setVersioning(true)
        .setStorageType(StorageType.SSD)
        .setAcls(acls)
        .setDefaultReplicationConfig(new DefaultReplicationConfig(repConfig));
    volume.createBucket(bucketName, builder.build());
    OzoneBucket bucket = volume.getBucket(bucketName);
    assertEquals(bucketName, bucket.getName());
    assertEquals(true, bucket.getVersioning());
    assertEquals(StorageType.SSD, bucket.getStorageType());
    assertTrue(bucket.getAcls().contains(userAcl));
    assertEquals(repConfig, bucket.getReplicationConfig());
  }

  @Test
  public void testInvalidBucketCreation() throws Exception {
    String volumeName = UUID.randomUUID().toString();
    String bucketName = "invalid#bucket";
    store.createVolume(volumeName);
    OzoneVolume volume = store.getVolume(volumeName);
    OMException omException = assertThrows(OMException.class,
        () -> volume.createBucket(bucketName));
    assertEquals("Bucket or Volume name has an unsupported character : #",
        omException.getMessage());
  }

  @Test
  public void testAddBucketAcl()
      throws IOException {
    String volumeName = UUID.randomUUID().toString();
    String bucketName = UUID.randomUUID().toString();
    store.createVolume(volumeName);
    OzoneVolume volume = store.getVolume(volumeName);
    volume.createBucket(bucketName);
    List<OzoneAcl> acls = new ArrayList<>();
    acls.add(new OzoneAcl(USER, "test", ACLType.ALL, ACCESS));
    OzoneBucket bucket = volume.getBucket(bucketName);
    for (OzoneAcl acl : acls) {
      assertTrue(bucket.addAcl(acl));
    }
    OzoneBucket newBucket = volume.getBucket(bucketName);
    assertEquals(bucketName, newBucket.getName());
    assertTrue(bucket.getAcls().contains(acls.get(0)));
  }

  @Test
  public void testRemoveBucketAcl()
      throws IOException {
    String volumeName = UUID.randomUUID().toString();
    String bucketName = UUID.randomUUID().toString();
    OzoneAcl userAcl = new OzoneAcl(USER, "test",
        ACLType.ALL, ACCESS);
    List<OzoneAcl> acls = new ArrayList<>();
    acls.add(userAcl);
    store.createVolume(volumeName);
    OzoneVolume volume = store.getVolume(volumeName);
    BucketArgs.Builder builder = BucketArgs.newBuilder();
    builder.setAcls(acls);
    volume.createBucket(bucketName, builder.build());
    OzoneBucket bucket = volume.getBucket(bucketName);
    for (OzoneAcl acl : acls) {
      assertTrue(bucket.removeAcl(acl));
    }
    OzoneBucket newBucket = volume.getBucket(bucketName);
    assertEquals(bucketName, newBucket.getName());
    assertFalse(bucket.getAcls().contains(acls.get(0)));
  }

  @Test
  public void testRemoveBucketAclUsingRpcClientRemoveAcl()
      throws IOException {
    String volumeName = UUID.randomUUID().toString();
    String bucketName = UUID.randomUUID().toString();
    OzoneAcl userAcl = new OzoneAcl(USER, "test",
        ACLType.ALL, ACCESS);
    List<OzoneAcl> acls = new ArrayList<>();
    acls.add(userAcl);
    acls.add(new OzoneAcl(USER, "test1",
        ACLType.ALL, ACCESS));
    store.createVolume(volumeName);
    OzoneVolume volume = store.getVolume(volumeName);
    BucketArgs.Builder builder = BucketArgs.newBuilder();
    builder.setAcls(acls);
    volume.createBucket(bucketName, builder.build());
    OzoneObj ozoneObj = OzoneObjInfo.Builder.newBuilder()
        .setBucketName(bucketName)
        .setVolumeName(volumeName)
        .setStoreType(OzoneObj.StoreType.OZONE)
        .setResType(OzoneObj.ResourceType.BUCKET).build();

    // Remove the 2nd acl added to the list.
    boolean remove = store.removeAcl(ozoneObj, acls.get(1));
    assertTrue(remove);
    assertFalse(store.getAcl(ozoneObj).contains(acls.get(1)));

    remove = store.removeAcl(ozoneObj, acls.get(0));
    assertTrue(remove);
    assertFalse(store.getAcl(ozoneObj).contains(acls.get(0)));
  }

  @Test
  public void testSetBucketVersioning()
      throws IOException {
    String volumeName = UUID.randomUUID().toString();
    String bucketName = UUID.randomUUID().toString();
    store.createVolume(volumeName);
    OzoneVolume volume = store.getVolume(volumeName);
    volume.createBucket(bucketName);
    OzoneBucket bucket = volume.getBucket(bucketName);
    bucket.setVersioning(true);
    OzoneBucket newBucket = volume.getBucket(bucketName);
    assertEquals(bucketName, newBucket.getName());
    assertEquals(true, newBucket.getVersioning());
  }

  @Test
  public void testAclsAfterCallingSetBucketProperty() throws Exception {
    String volumeName = UUID.randomUUID().toString();
    String bucketName = UUID.randomUUID().toString();
    store.createVolume(volumeName);
    OzoneVolume volume = store.getVolume(volumeName);
    volume.createBucket(bucketName);

    OzoneBucket ozoneBucket = volume.getBucket(bucketName);
    List<OzoneAcl> currentAcls = ozoneBucket.getAcls();

    ozoneBucket.setVersioning(true);

    OzoneBucket newBucket = volume.getBucket(bucketName);
    assertEquals(bucketName, newBucket.getName());
    assertEquals(true, newBucket.getVersioning());

    List<OzoneAcl> aclsAfterSet = newBucket.getAcls();
    assertEquals(currentAcls, aclsAfterSet);

  }

  @Test
  public void testSetBucketStorageType()
      throws IOException {
    String volumeName = UUID.randomUUID().toString();
    String bucketName = UUID.randomUUID().toString();
    store.createVolume(volumeName);
    OzoneVolume volume = store.getVolume(volumeName);
    volume.createBucket(bucketName);
    OzoneBucket bucket = volume.getBucket(bucketName);
    bucket.setStorageType(StorageType.SSD);
    OzoneBucket newBucket = volume.getBucket(bucketName);
    assertEquals(bucketName, newBucket.getName());
    assertEquals(StorageType.SSD, newBucket.getStorageType());
  }

  @ParameterizedTest
  @MethodSource("bucketLayouts")
  public void testDeleteBucketWithTheSamePrefix(BucketLayout bucketLayout)
      throws Exception {
    /*
    * This test case simulates the following operations:
    * 1. (op1) Create a bucket named "bucket1".
    * 2. (op2) Create another bucket named "bucket11",
    *          which has the same prefix as "bucket1".
    * 3. (op3) Put a key in the "bucket11".
    * 4. (op4) Delete the "bucket1". This operation should succeed.
    * */
    String volumeName = UUID.randomUUID().toString();
    String bucketName1 = "bucket1";
    String bucketName2 = "bucket11";
    String keyName = "key";
    store.createVolume(volumeName);
    OzoneVolume volume = store.getVolume(volumeName);
    BucketArgs bucketArgs =
        BucketArgs.newBuilder().setBucketLayout(bucketLayout).build();
    volume.createBucket(bucketName1, bucketArgs);
    volume.createBucket(bucketName2, bucketArgs);
    OzoneBucket bucket2 = volume.getBucket(bucketName2);
    bucket2.createKey(keyName, 1).close();
    volume.deleteBucket(bucketName1);
    OzoneTestUtils.expectOmException(ResultCodes.BUCKET_NOT_FOUND,
        () -> volume.getBucket(bucketName1));
  }

  @Test
  public void testDeleteBucket()
      throws Exception {

    String volumeName = UUID.randomUUID().toString();
    String bucketName = UUID.randomUUID().toString();
    store.createVolume(volumeName);
    OzoneVolume volume = store.getVolume(volumeName);
    volume.createBucket(bucketName);
    OzoneBucket bucket = volume.getBucket(bucketName);
    assertNotNull(bucket);
    volume.deleteBucket(bucketName);

    OzoneTestUtils.expectOmException(ResultCodes.BUCKET_NOT_FOUND,
        () -> volume.getBucket(bucketName)
    );
  }

  @Test
  public void testDeleteLinkedBucket()
      throws Exception {
    String volumeName = UUID.randomUUID().toString();
    String bucketName = UUID.randomUUID().toString();
    String linkedBucketName = UUID.randomUUID().toString();

    store.createVolume(volumeName);
    OzoneVolume volume = store.getVolume(volumeName);

    volume.createBucket(bucketName);
    OzoneBucket bucket = volume.getBucket(bucketName);
    assertNotNull(bucket);

    volume.createBucket(linkedBucketName,
        BucketArgs.newBuilder()
            .setSourceBucket(bucketName)
            .setSourceVolume(volumeName)
            .build());
    OzoneBucket linkedBucket = volume.getBucket(linkedBucketName);
    assertNotNull(linkedBucket);

    volume.deleteBucket(bucketName);

    OzoneTestUtils.expectOmException(ResultCodes.BUCKET_NOT_FOUND,
        () -> volume.getBucket(bucketName)
    );
    //now linkedBucketName has become a dangling one
    //should still be possible to get its info
    OzoneBucket danglingLinkedBucket = volume.getBucket(linkedBucketName);
    assertNotNull(danglingLinkedBucket);

    //now delete the dangling linked bucket
    volume.deleteBucket(linkedBucketName);

    OzoneTestUtils.expectOmException(ResultCodes.BUCKET_NOT_FOUND,
        () -> volume.getBucket(linkedBucketName)
    );

    store.deleteVolume(volumeName);
  }

  private void verifyReplication(String volumeName, String bucketName,
      String keyName, ReplicationConfig replication)
      throws IOException {
    OmKeyArgs keyArgs = new OmKeyArgs.Builder()
        .setVolumeName(volumeName)
        .setBucketName(bucketName)
        .setKeyName(keyName)
        .build();
    OmKeyInfo keyInfo = ozoneManager.lookupKey(keyArgs);
    for (OmKeyLocationInfo info:
        keyInfo.getLatestVersionLocations().getLocationList()) {
      ContainerInfo container =
          storageContainerLocationClient.getContainer(info.getContainerID());
      assertEquals(replication, container.getReplicationConfig());
    }
  }

  @ParameterizedTest
  @CsvSource({"rs-3-3-1024k,false", "xor-3-5-2048k,false",
              "rs-3-2-1024k,true", "rs-6-3-1024k,true", "rs-10-4-1024k,true"})
  public void testPutKeyWithReplicationConfig(String replicationValue,
                                              boolean isValidReplicationConfig)
          throws IOException {
    String volumeName = UUID.randomUUID().toString();
    String bucketName = UUID.randomUUID().toString();

    store.createVolume(volumeName);
    OzoneVolume volume = store.getVolume(volumeName);
    volume.createBucket(bucketName);
    OzoneBucket bucket = volume.getBucket(bucketName);

    String keyName = UUID.randomUUID().toString();
    String value = UUID.randomUUID().toString();
    ReplicationConfig replicationConfig =
            new ECReplicationConfig(replicationValue);
    if (isValidReplicationConfig) {
      OzoneOutputStream out = bucket.createKey(keyName,
              value.getBytes(UTF_8).length, replicationConfig, new HashMap<>());
      out.write(value.getBytes(UTF_8));
      out.close();
      OzoneKey key = bucket.getKey(keyName);
      assertEquals(keyName, key.getName());
      try (OzoneInputStream is = bucket.readKey(keyName)) {
        byte[] fileContent = new byte[value.getBytes(UTF_8).length];
        is.read(fileContent);
        assertEquals(value, new String(fileContent, UTF_8));
      }
    } else {
      assertThrows(IllegalArgumentException.class,
          () -> bucket.createKey(keyName, "dummy".getBytes(UTF_8).length,
              replicationConfig, new HashMap<>()));
    }
  }

  @Test
  public void testPutKey() throws IOException {
    String volumeName = UUID.randomUUID().toString();
    String bucketName = UUID.randomUUID().toString();
    Instant testStartTime = Instant.now();

    String value = "sample value";
    store.createVolume(volumeName);
    OzoneVolume volume = store.getVolume(volumeName);
    volume.createBucket(bucketName);
    OzoneBucket bucket = volume.getBucket(bucketName);

    for (int i = 0; i < 10; i++) {
      String keyName = UUID.randomUUID().toString();

      OzoneOutputStream out = bucket.createKey(keyName,
          value.getBytes(UTF_8).length, RATIS,
          ONE, new HashMap<>());
      out.write(value.getBytes(UTF_8));
      out.close();
      OzoneKey key = bucket.getKey(keyName);
      assertEquals(keyName, key.getName());
      try (OzoneInputStream is = bucket.readKey(keyName)) {
        byte[] fileContent = new byte[value.getBytes(UTF_8).length];
        is.read(fileContent);
        verifyReplication(volumeName, bucketName, keyName,
            RatisReplicationConfig.getInstance(
                HddsProtos.ReplicationFactor.ONE));
        assertEquals(value, new String(fileContent, UTF_8));
        assertFalse(key.getCreationTime().isBefore(testStartTime));
        assertFalse(key.getModificationTime().isBefore(testStartTime));
      }
    }
  }

  @Test
  public void testCheckUsedBytesQuota() throws IOException {
    String volumeName = UUID.randomUUID().toString();
    String bucketName = UUID.randomUUID().toString();
    OzoneVolume volume = null;

    String value = "sample value";
    int blockSize = (int) ozoneManager.getConfiguration().getStorageSize(
        OZONE_SCM_BLOCK_SIZE, OZONE_SCM_BLOCK_SIZE_DEFAULT, StorageUnit.BYTES);
    int valueLength = value.getBytes(UTF_8).length;
    int countException = 0;

    store.createVolume(volumeName);
    volume = store.getVolume(volumeName);
    volume.createBucket(bucketName);
    OzoneBucket bucket = volume.getBucket(bucketName);
    bucket.setQuota(OzoneQuota.parseQuota("1 B", "100"));

    // Test bucket quota.
    bucketName = UUID.randomUUID().toString();
    volume.createBucket(bucketName);
    bucket = volume.getBucket(bucketName);
    bucket.setQuota(OzoneQuota.parseQuota("1 B", "100"));
    store.getVolume(volumeName).setQuota(
        OzoneQuota.parseQuota(Long.MAX_VALUE + " B", "100"));

    // Test bucket quota: write key.
    // The remaining quota does not satisfy a block size, so the write fails.
    try {
      writeKey(bucket, UUID.randomUUID().toString(), ONE, value, valueLength);
    } catch (IOException ex) {
      countException++;
      GenericTestUtils.assertExceptionContains("QUOTA_EXCEEDED", ex);
    }
    // Write failed, bucket usedBytes should be 0
    assertEquals(0L,
        store.getVolume(volumeName).getBucket(bucketName).getUsedBytes());

    // Test bucket quota: write file.
    // The remaining quota does not satisfy a block size, so the write fails.
    try {
      writeFile(bucket, UUID.randomUUID().toString(), ONE, value, 0);
    } catch (IOException ex) {
      countException++;
      GenericTestUtils.assertExceptionContains("QUOTA_EXCEEDED", ex);
    }
    // Write failed, bucket usedBytes should be 0
    assertEquals(0L,
        store.getVolume(volumeName).getBucket(bucketName).getUsedBytes());

    // Test bucket quota: write large key(with five blocks), the first four
    // blocks will succeed，while the later block will fail.
    bucket.setQuota(OzoneQuota.parseQuota(
        4 * blockSize + " B", "100"));
    try {
      String keyName = UUID.randomUUID().toString();
      OzoneOutputStream out = bucket.createKey(keyName,
          valueLength, RATIS, ONE, new HashMap<>());
      for (int i = 0; i <= (4 * blockSize) / value.length(); i++) {
        out.write(value.getBytes(UTF_8));
      }
      out.close();
    } catch (IOException ex) {
      countException++;
      GenericTestUtils.assertExceptionContains("QUOTA_EXCEEDED", ex);
    }
    // AllocateBlock failed, bucket usedBytes should not update.
    assertEquals(0L,
        store.getVolume(volumeName).getBucket(bucketName).getUsedBytes());

    // Reset bucket quota, the original usedBytes needs to remain the same
    bucket.setQuota(OzoneQuota.parseQuota(
        100 + " GB", "100"));
    assertEquals(0,
        store.getVolume(volumeName).getBucket(bucketName).getUsedBytes());

    assertEquals(3, countException);

    // key with 0 bytes, usedBytes should not increase.
    bucket.setQuota(OzoneQuota.parseQuota(
        5 * blockSize + " B", "100"));
    OzoneOutputStream out = bucket.createKey(UUID.randomUUID().toString(),
        valueLength, RATIS, ONE, new HashMap<>());
    out.close();
    assertEquals(0,
        store.getVolume(volumeName).getBucket(bucketName).getUsedBytes());

    // key write success,bucket usedBytes should update.
    bucket.setQuota(OzoneQuota.parseQuota(
        5 * blockSize + " B", "100"));
    OzoneOutputStream out2 = bucket.createKey(UUID.randomUUID().toString(),
        valueLength, RATIS, ONE, new HashMap<>());
    out2.write(value.getBytes(UTF_8));
    out2.close();
    assertEquals(valueLength,
        store.getVolume(volumeName).getBucket(bucketName).getUsedBytes());
  }

  @Test
  public void testBucketUsedBytes() throws IOException {
    bucketUsedBytesTestHelper(BucketLayout.OBJECT_STORE);
  }

  @Test
  public void testFSOBucketUsedBytes() throws IOException {
    bucketUsedBytesTestHelper(BucketLayout.FILE_SYSTEM_OPTIMIZED);
  }

  private void bucketUsedBytesTestHelper(BucketLayout bucketLayout)
      throws IOException {
    String volumeName = UUID.randomUUID().toString();
    String bucketName = UUID.randomUUID().toString();
    int blockSize = (int) ozoneManager.getConfiguration().getStorageSize(
        OZONE_SCM_BLOCK_SIZE, OZONE_SCM_BLOCK_SIZE_DEFAULT, StorageUnit.BYTES);
    OzoneVolume volume = null;
    String value = "sample value";
    int valueLength = value.getBytes(UTF_8).length;
    store.createVolume(volumeName);
    volume = store.getVolume(volumeName);
    BucketArgs bucketArgs = BucketArgs.newBuilder()
        .setBucketLayout(bucketLayout).build();
    volume.createBucket(bucketName, bucketArgs);
    OzoneBucket bucket = volume.getBucket(bucketName);
    String keyName = UUID.randomUUID().toString();

    writeKey(bucket, keyName, ONE, value, valueLength);
    assertEquals(valueLength,
        store.getVolume(volumeName).getBucket(bucketName).getUsedBytes());

    writeKey(bucket, keyName, ONE, value, valueLength);
    assertEquals(valueLength,
        store.getVolume(volumeName).getBucket(bucketName).getUsedBytes());

    // pre-allocate more blocks than needed
    int fakeValueLength = valueLength + blockSize;
    writeKey(bucket, keyName, ONE, value, fakeValueLength);
    assertEquals(valueLength,
        store.getVolume(volumeName).getBucket(bucketName).getUsedBytes());

    bucket.deleteKey(keyName);
    assertEquals(0L,
        store.getVolume(volumeName).getBucket(bucketName).getUsedBytes());
  }

  static Stream<BucketLayout> bucketLayouts() {
    return Stream.of(
        BucketLayout.OBJECT_STORE,
        BucketLayout.LEGACY,
        BucketLayout.FILE_SYSTEM_OPTIMIZED
    );
  }

  @ParameterizedTest
  @MethodSource("bucketLayouts")
  void bucketUsedBytesOverWrite(BucketLayout bucketLayout)
      throws IOException {
    String volumeName = UUID.randomUUID().toString();
    String bucketName = UUID.randomUUID().toString();
    OzoneVolume volume = null;
    String value = "sample value";
    int valueLength = value.getBytes(UTF_8).length;
    store.createVolume(volumeName);
    volume = store.getVolume(volumeName);
    BucketArgs bucketArgs = BucketArgs.newBuilder()
        .setBucketLayout(bucketLayout).setVersioning(true).build();
    volume.createBucket(bucketName, bucketArgs);
    OzoneBucket bucket = volume.getBucket(bucketName);
    String keyName = UUID.randomUUID().toString();

    writeKey(bucket, keyName, ONE, value, valueLength);
    assertEquals(valueLength,
        store.getVolume(volumeName).getBucket(bucketName).getUsedBytes());

    // Overwrite the same key, because this bucket setVersioning is true,
    // so the bucket usedBytes should increase.
    writeKey(bucket, keyName, ONE, value, valueLength);
    assertEquals(valueLength * 2,
        store.getVolume(volumeName).getBucket(bucketName).getUsedBytes());
  }


  // TODO: testBucketQuota overlaps with testBucketUsedBytes,
  //       do cleanup when EC branch gets merged into master.
  @ParameterizedTest
  @MethodSource("replicationConfigs")
  void testBucketQuota(ReplicationConfig repConfig) throws IOException {
    int blockSize = (int) ozoneManager.getConfiguration().getStorageSize(
        OZONE_SCM_BLOCK_SIZE, OZONE_SCM_BLOCK_SIZE_DEFAULT, StorageUnit.BYTES);

    for (int i = 0; i <= repConfig.getRequiredNodes(); i++) {
      bucketQuotaTestHelper(i * blockSize, repConfig);
      bucketQuotaTestHelper(i * blockSize + 1, repConfig);
    }
  }

  private void bucketQuotaTestHelper(int keyLength, ReplicationConfig repConfig)
      throws IOException {
    String volumeName = UUID.randomUUID().toString();
    String bucketName = UUID.randomUUID().toString();
    String keyName = UUID.randomUUID().toString();
    long blockSize = (long) ozoneManager.getConfiguration().getStorageSize(
        OZONE_SCM_BLOCK_SIZE, OZONE_SCM_BLOCK_SIZE_DEFAULT, StorageUnit.BYTES);

    store.createVolume(volumeName);
    OzoneVolume volume = store.getVolume(volumeName);
    volume.createBucket(bucketName);
    OzoneBucket bucket = volume.getBucket(bucketName);

    byte[] value = new byte[keyLength];
    long keyQuota = QuotaUtil.getReplicatedSize(keyLength, repConfig);

    OzoneOutputStream out = bucket.createKey(keyName, keyLength,
        repConfig, new HashMap<>());
    // Write a new key and do not update Bucket UsedBytes until commit.
    assertEquals(0,
        store.getVolume(volumeName).getBucket(bucketName).getUsedBytes());
    out.write(value);
    out.close();
    // After committing the new key, the Bucket UsedBytes must be updated to
    // keyQuota.
    assertEquals(keyQuota,
        store.getVolume(volumeName).getBucket(bucketName).getUsedBytes());

    out = bucket.createKey(keyName, keyLength, repConfig, new HashMap<>());
    // Overwrite an old key. The Bucket UsedBytes are not updated before the
    // commit. So the Bucket UsedBytes remain unchanged.
    assertEquals(keyQuota,
        store.getVolume(volumeName).getBucket(bucketName).getUsedBytes());
    out.write(value);
    out.close();
    assertEquals(keyQuota,
        store.getVolume(volumeName).getBucket(bucketName).getUsedBytes());

    bucket.deleteKey(keyName);
    assertEquals(0L,
        store.getVolume(volumeName).getBucket(bucketName).getUsedBytes());
  }

  @ParameterizedTest
  @MethodSource("bucketLayouts")
  public void testBucketUsedNamespace(BucketLayout layout) throws IOException {
    String volumeName = UUID.randomUUID().toString();
    String bucketName = UUID.randomUUID().toString();
    String value = "sample value";
    int valueLength = value.getBytes(UTF_8).length;
    store.createVolume(volumeName);
    OzoneVolume volume = store.getVolume(volumeName);
    BucketArgs bucketArgs = BucketArgs.newBuilder()
        .setBucketLayout(layout)
        .build();
    volume.createBucket(bucketName, bucketArgs);
    OzoneBucket bucket = volume.getBucket(bucketName);
    String keyName1 = UUID.randomUUID().toString();
    String keyName2 = UUID.randomUUID().toString();

    writeKey(bucket, keyName1, ONE, value, valueLength);
    assertEquals(1L, getBucketUsedNamespace(volumeName, bucketName));
    // Test create a file twice will not increase usedNamespace twice
    writeKey(bucket, keyName1, ONE, value, valueLength);
    assertEquals(1L, getBucketUsedNamespace(volumeName, bucketName));
    writeKey(bucket, keyName2, ONE, value, valueLength);
    assertEquals(2L, getBucketUsedNamespace(volumeName, bucketName));
    bucket.deleteKey(keyName1);
    assertEquals(1L, getBucketUsedNamespace(volumeName, bucketName));
    bucket.deleteKey(keyName2);
    assertEquals(0L, getBucketUsedNamespace(volumeName, bucketName));

    RpcClient client = new RpcClient(cluster.getConf(), null);
    try {
      String directoryName1 = UUID.randomUUID().toString();
      String directoryName2 = UUID.randomUUID().toString();

      client.createDirectory(volumeName, bucketName, directoryName1);
      assertEquals(1L, getBucketUsedNamespace(volumeName, bucketName));
      // Test create a directory twice will not increase usedNamespace twice
      client.createDirectory(volumeName, bucketName, directoryName2);
      assertEquals(2L, getBucketUsedNamespace(volumeName, bucketName));
      client.deleteKey(volumeName, bucketName,
          OzoneFSUtils.addTrailingSlashIfNeeded(directoryName1), false);
      assertEquals(1L, getBucketUsedNamespace(volumeName, bucketName));
      client.deleteKey(volumeName, bucketName,
          OzoneFSUtils.addTrailingSlashIfNeeded(directoryName2), false);
      assertEquals(0L, getBucketUsedNamespace(volumeName, bucketName));

      String multiComponentsDir = "dir1/dir2/dir3/dir4";
      client.createDirectory(volumeName, bucketName, multiComponentsDir);
      assertEquals(OzoneFSUtils.getFileCount(multiComponentsDir),
          getBucketUsedNamespace(volumeName, bucketName));
    } finally {
      client.close();
    }
  }

  @ParameterizedTest
  @MethodSource("bucketLayouts")
  public void testMissingParentBucketUsedNamespace(BucketLayout layout)
      throws IOException {
    // when will put a key that contain not exist directory only FSO buckets
    // and LEGACY buckets with ozone.om.enable.filesystem.paths set to true
    // will create missing directories.
    String volumeName = UUID.randomUUID().toString();
    String bucketName = UUID.randomUUID().toString();
    String value = "sample value";
    int valueLength = value.getBytes(UTF_8).length;
    store.createVolume(volumeName);
    OzoneVolume volume = store.getVolume(volumeName);
    BucketArgs bucketArgs = BucketArgs.newBuilder()
        .setBucketLayout(layout)
        .build();
    volume.createBucket(bucketName, bucketArgs);
    OzoneBucket bucket = volume.getBucket(bucketName);

    if (layout.equals(BucketLayout.LEGACY)) {
      OzoneConfiguration conf = cluster.getConf();
      conf.setBoolean(OMConfigKeys.OZONE_OM_ENABLE_FILESYSTEM_PATHS, true);
      cluster.setConf(conf);
    }

    // the directory "/dir1", ""/dir1/dir2/", "/dir1/dir2/dir3/"
    // will be created automatically
    String missingParentKeyName = "dir1/dir2/dir3/file1";
    writeKey(bucket, missingParentKeyName, ONE, value, valueLength);
    if (layout.equals(BucketLayout.OBJECT_STORE)) {
      // for OBJECT_STORE bucket, missing parent will not be
      // created automatically
      assertEquals(1, getBucketUsedNamespace(volumeName, bucketName));
    } else {
      assertEquals(OzoneFSUtils.getFileCount(missingParentKeyName),
          getBucketUsedNamespace(volumeName, bucketName));
    }
  }

  private long getBucketUsedNamespace(String volume, String bucket)
      throws IOException {
    return store.getVolume(volume).getBucket(bucket).getUsedNamespace();
  }

  @Test
  public void testVolumeUsedNamespace() throws IOException {
    String volumeName = UUID.randomUUID().toString();
    String bucketName = UUID.randomUUID().toString();
    String bucketName2 = UUID.randomUUID().toString();
    OzoneVolume volume = null;

    // set Volume namespace quota as 1
    store.createVolume(volumeName,
        VolumeArgs.newBuilder().setQuotaInNamespace(1L).build());
    volume = store.getVolume(volumeName);
    // The initial value should be 0
    assertEquals(0L, volume.getUsedNamespace());
    volume.createBucket(bucketName);
    // Used namespace should be 1
    volume = store.getVolume(volumeName);
    assertEquals(1L, volume.getUsedNamespace());

    try {
      volume.createBucket(bucketName2);
    } catch (IOException ex) {
      GenericTestUtils.assertExceptionContains("QUOTA_EXCEEDED", ex);
    }

    // test linked bucket
    String targetVolName = UUID.randomUUID().toString();
    store.createVolume(targetVolName);
    OzoneVolume volumeWithLinkedBucket = store.getVolume(targetVolName);
    String targetBucketName = UUID.randomUUID().toString();
    BucketArgs.Builder argsBuilder = new BucketArgs.Builder()
        .setStorageType(StorageType.DEFAULT)
        .setVersioning(false)
        .setSourceVolume(volumeName)
        .setSourceBucket(bucketName);
    volumeWithLinkedBucket.createBucket(targetBucketName, argsBuilder.build());
    // Used namespace should be 0 because linked bucket does not consume
    // namespace quota
    assertEquals(0L, volumeWithLinkedBucket.getUsedNamespace());

    // Reset volume quota, the original usedNamespace needs to remain the same
    store.getVolume(volumeName).setQuota(OzoneQuota.parseNameSpaceQuota(
        "100"));
    assertEquals(1L,
        store.getVolume(volumeName).getUsedNamespace());

    volume.deleteBucket(bucketName);
    // Used namespace should be 0
    volume = store.getVolume(volumeName);
    assertEquals(0L, volume.getUsedNamespace());
  }

  @Test
  public void testBucketQuotaInNamespace() throws IOException {
    String volumeName = UUID.randomUUID().toString();
    String bucketName = UUID.randomUUID().toString();
    String key1 = UUID.randomUUID().toString();
    String key2 = UUID.randomUUID().toString();
    String key3 = UUID.randomUUID().toString();

    String value = "sample value";

    store.createVolume(volumeName);
    OzoneVolume volume = store.getVolume(volumeName);
    volume.createBucket(bucketName);
    OzoneBucket bucket = volume.getBucket(bucketName);
    bucket.setQuota(OzoneQuota.parseQuota(Long.MAX_VALUE + " B", "2"));

    writeKey(bucket, key1, ONE, value, value.length());
    assertEquals(1L,
        store.getVolume(volumeName).getBucket(bucketName).getUsedNamespace());

    writeKey(bucket, key2, ONE, value, value.length());
    assertEquals(2L,
        store.getVolume(volumeName).getBucket(bucketName).getUsedNamespace());

    IOException ioException = assertThrows(IOException.class,
        () -> writeKey(bucket, key3, ONE, value, value.length()));
    assertTrue(ioException.toString().contains("QUOTA_EXCEEDED"));

    // Write failed, bucket usedNamespace should remain as 2
    assertEquals(2L,
        store.getVolume(volumeName).getBucket(bucketName).getUsedNamespace());

    // Reset bucket quota, the original usedNamespace needs to remain the same
    bucket.setQuota(
        OzoneQuota.parseQuota(Long.MAX_VALUE + " B", "100"));
    assertEquals(2L,
        store.getVolume(volumeName).getBucket(bucketName).getUsedNamespace());

    bucket.deleteKeys(Arrays.asList(key1, key2));
    assertEquals(0L,
        store.getVolume(volumeName).getBucket(bucketName).getUsedNamespace());
  }

  private void writeKey(OzoneBucket bucket, String keyName,
                        ReplicationFactor replication, String value,
                        int valueLength)
      throws IOException {
    OzoneOutputStream out = bucket.createKey(keyName, valueLength, RATIS,
        replication, new HashMap<>());
    out.write(value.getBytes(UTF_8));
    out.close();
  }

  private void writeFile(OzoneBucket bucket, String keyName,
                         ReplicationFactor replication, String value,
                         int valueLength)
      throws IOException {
    OzoneOutputStream out = bucket.createFile(keyName, valueLength, RATIS,
        replication, true, true);
    out.write(value.getBytes(UTF_8));
    out.close();
  }

  @Test
  public void testUsedBytesWithUploadPart() throws IOException {
    String volumeName = UUID.randomUUID().toString();
    String bucketName = UUID.randomUUID().toString();
    String keyName = UUID.randomUUID().toString();
    int blockSize = (int) ozoneManager.getConfiguration().getStorageSize(
        OZONE_SCM_BLOCK_SIZE, OZONE_SCM_BLOCK_SIZE_DEFAULT, StorageUnit.BYTES);
    String sampleData = Arrays.toString(generateData(blockSize + 100,
        (byte) RandomUtils.nextLong()));
    int valueLength = sampleData.getBytes(UTF_8).length;

    store.createVolume(volumeName);
    OzoneVolume volume = store.getVolume(volumeName);
    volume.createBucket(bucketName);
    OzoneBucket bucket = volume.getBucket(bucketName);
    ReplicationConfig replication = RatisReplicationConfig.getInstance(
        HddsProtos.ReplicationFactor.ONE);
    OmMultipartInfo multipartInfo = bucket.initiateMultipartUpload(keyName,
        replication);

    assertNotNull(multipartInfo);
    String uploadID = multipartInfo.getUploadID();
    assertEquals(volumeName, multipartInfo.getVolumeName());
    assertEquals(bucketName, multipartInfo.getBucketName());
    assertEquals(keyName, multipartInfo.getKeyName());
    assertNotNull(multipartInfo.getUploadID());

    OzoneOutputStream ozoneOutputStream = bucket.createMultipartKey(keyName,
        sampleData.length(), 1, uploadID);
    ozoneOutputStream.write(string2Bytes(sampleData), 0,
        sampleData.length());
    ozoneOutputStream.close();

    assertEquals(valueLength, store.getVolume(volumeName)
        .getBucket(bucketName).getUsedBytes());

    // Abort uploaded partKey and the usedBytes of bucket should be 0.
    bucket.abortMultipartUpload(keyName, uploadID);
    assertEquals(0, store.getVolume(volumeName)
        .getBucket(bucketName).getUsedBytes());
  }

  @Test
  public void testValidateBlockLengthWithCommitKey() throws IOException {
    String volumeName = UUID.randomUUID().toString();
    String bucketName = UUID.randomUUID().toString();

    String value = RandomStringUtils.random(RandomUtils.nextInt(0, 1024));
    store.createVolume(volumeName);
    OzoneVolume volume = store.getVolume(volumeName);
    volume.createBucket(bucketName);
    OzoneBucket bucket = volume.getBucket(bucketName);
    String keyName = UUID.randomUUID().toString();

    // create the initial key with size 0, write will allocate the first block.
    OzoneOutputStream out = bucket.createKey(keyName, 0,
        RATIS, ONE, new HashMap<>());
    out.write(value.getBytes(UTF_8));
    out.close();
    OmKeyArgs.Builder builder = new OmKeyArgs.Builder();
    builder.setVolumeName(volumeName).setBucketName(bucketName)
        .setKeyName(keyName);
    OmKeyInfo keyInfo = ozoneManager.lookupKey(builder.build());

    List<OmKeyLocationInfo> locationInfoList =
        keyInfo.getLatestVersionLocations().getBlocksLatestVersionOnly();
    // LocationList should have only 1 block
    assertEquals(1, locationInfoList.size());
    // make sure the data block size is updated
    assertEquals(value.getBytes(UTF_8).length,
        locationInfoList.get(0).getLength());
    // make sure the total data size is set correctly
    assertEquals(value.getBytes(UTF_8).length, keyInfo.getDataSize());
  }

  @Test
  public void testPutKeyRatisOneNode() throws IOException {
    String volumeName = UUID.randomUUID().toString();
    String bucketName = UUID.randomUUID().toString();
    Instant testStartTime = Instant.now();

    String value = "sample value";
    store.createVolume(volumeName);
    OzoneVolume volume = store.getVolume(volumeName);
    volume.createBucket(bucketName);
    OzoneBucket bucket = volume.getBucket(bucketName);

    for (int i = 0; i < 10; i++) {
      String keyName = UUID.randomUUID().toString();

      OzoneOutputStream out = bucket.createKey(keyName,
          value.getBytes(UTF_8).length, ReplicationType.RATIS,
          ONE, new HashMap<>());
      out.write(value.getBytes(UTF_8));
      out.close();
      OzoneKey key = bucket.getKey(keyName);
      assertEquals(keyName, key.getName());
      try (OzoneInputStream is = bucket.readKey(keyName)) {
        byte[] fileContent = new byte[value.getBytes(UTF_8).length];
        is.read(fileContent);
        verifyReplication(volumeName, bucketName, keyName,
            RatisReplicationConfig.getInstance(
                HddsProtos.ReplicationFactor.ONE));
        assertEquals(value, new String(fileContent, UTF_8));
        assertFalse(key.getCreationTime().isBefore(testStartTime));
        assertFalse(key.getModificationTime().isBefore(testStartTime));
      }
    }
  }

  @Test
  public void testPutKeyRatisThreeNodes() throws IOException {
    String volumeName = UUID.randomUUID().toString();
    String bucketName = UUID.randomUUID().toString();
    Instant testStartTime = Instant.now();

    String value = "sample value";
    store.createVolume(volumeName);
    OzoneVolume volume = store.getVolume(volumeName);
    volume.createBucket(bucketName);
    OzoneBucket bucket = volume.getBucket(bucketName);

    for (int i = 0; i < 10; i++) {
      String keyName = UUID.randomUUID().toString();

      OzoneOutputStream out = bucket.createKey(keyName,
          value.getBytes(UTF_8).length, ReplicationType.RATIS,
          THREE, new HashMap<>());
      out.write(value.getBytes(UTF_8));
      out.close();
      OzoneKey key = bucket.getKey(keyName);
      assertEquals(keyName, key.getName());
      try (OzoneInputStream is = bucket.readKey(keyName)) {
        byte[] fileContent = new byte[value.getBytes(UTF_8).length];
        is.read(fileContent);
        verifyReplication(volumeName, bucketName, keyName,
            RatisReplicationConfig.getInstance(
                HddsProtos.ReplicationFactor.THREE));
        assertEquals(value, new String(fileContent, UTF_8));
        assertFalse(key.getCreationTime().isBefore(testStartTime));
        assertFalse(key.getModificationTime().isBefore(testStartTime));
      }
    }
  }


  @Test
  public void testPutKeyRatisThreeNodesParallel() throws IOException,
      InterruptedException {
    String volumeName = UUID.randomUUID().toString();
    String bucketName = UUID.randomUUID().toString();
    Instant testStartTime = Instant.now();
    store.createVolume(volumeName);
    OzoneVolume volume = store.getVolume(volumeName);
    volume.createBucket(bucketName);
    OzoneBucket bucket = volume.getBucket(bucketName);

    CountDownLatch latch = new CountDownLatch(2);
    AtomicInteger failCount = new AtomicInteger(0);

    Runnable r = () -> {
      try {
        for (int i = 0; i < 5; i++) {
          String keyName = UUID.randomUUID().toString();
          String data = Arrays.toString(generateData(5 * 1024 * 1024,
              (byte) RandomUtils.nextLong()));
          OzoneOutputStream out = bucket.createKey(keyName,
              data.getBytes(UTF_8).length, ReplicationType.RATIS,
              THREE, new HashMap<>());
          out.write(data.getBytes(UTF_8));
          out.close();
          OzoneKey key = bucket.getKey(keyName);
          assertEquals(keyName, key.getName());
          try (OzoneInputStream is = bucket.readKey(keyName)) {
            byte[] fileContent = new byte[data.getBytes(UTF_8).length];
            is.read(fileContent);
            verifyReplication(volumeName, bucketName, keyName,
                RatisReplicationConfig.getInstance(
                    HddsProtos.ReplicationFactor.THREE));
            assertEquals(data, new String(fileContent, UTF_8));
          }
          assertFalse(key.getCreationTime().isBefore(testStartTime));
          assertFalse(key.getModificationTime().isBefore(testStartTime));
        }
        latch.countDown();
      } catch (IOException ex) {
        latch.countDown();
        failCount.incrementAndGet();
      }
    };

    Thread thread1 = new Thread(r);
    Thread thread2 = new Thread(r);

    thread1.start();
    thread2.start();

    latch.await(600, TimeUnit.SECONDS);

    assertTrue(failCount.get() <= 0,
        "testPutKeyRatisThreeNodesParallel failed");
  }


  @Test
  public void testReadKeyWithVerifyChecksumFlagEnable() throws Exception {
    String volumeName = UUID.randomUUID().toString();
    String bucketName = UUID.randomUUID().toString();
    String keyName = UUID.randomUUID().toString();

    // Create and corrupt key
    createAndCorruptKey(volumeName, bucketName, keyName);

    // read corrupt key with verify checksum enabled
    readCorruptedKey(volumeName, bucketName, keyName, true);

  }


  @Test
  public void testReadKeyWithVerifyChecksumFlagDisable() throws Exception {
    String volumeName = UUID.randomUUID().toString();
    String bucketName = UUID.randomUUID().toString();
    String keyName = UUID.randomUUID().toString();

    // Create and corrupt key
    createAndCorruptKey(volumeName, bucketName, keyName);

    // read corrupt key with verify checksum enabled
    readCorruptedKey(volumeName, bucketName, keyName, false);

  }

  private void createAndCorruptKey(String volumeName, String bucketName,
      String keyName) throws IOException {
    String value = "sample value";
    store.createVolume(volumeName);
    OzoneVolume volume = store.getVolume(volumeName);
    volume.createBucket(bucketName);
    OzoneBucket bucket = volume.getBucket(bucketName);

    // Write data into a key
    OzoneOutputStream out = bucket.createKey(keyName,
        value.getBytes(UTF_8).length, ReplicationType.RATIS,
        ONE, new HashMap<>());
    out.write(value.getBytes(UTF_8));
    out.close();

    // We need to find the location of the chunk file corresponding to the
    // data we just wrote.
    OzoneKey key = bucket.getKey(keyName);
    long containerID = ((OzoneKeyDetails) key).getOzoneKeyLocations().get(0)
        .getContainerID();

    // Get the container by traversing the datanodes. Atleast one of the
    // datanode must have this container.
    Container container = null;
    for (HddsDatanodeService hddsDatanode : cluster.getHddsDatanodes()) {
      container = hddsDatanode.getDatanodeStateMachine().getContainer()
          .getContainerSet().getContainer(containerID);
      if (container != null) {
        break;
      }
    }
    assertNotNull(container, "Container not found");
    corruptData(container, key);
  }


  private void readCorruptedKey(String volumeName, String bucketName,
      String keyName, boolean verifyChecksum) {
    try {

      OzoneConfiguration configuration = cluster.getConf();

      final OzoneClientConfig clientConfig =
          configuration.getObject(OzoneClientConfig.class);
      clientConfig.setChecksumVerify(verifyChecksum);
      configuration.setFromObject(clientConfig);

      RpcClient client = new RpcClient(configuration, null);
      try (InputStream is = client.getKey(volumeName, bucketName, keyName)) {
        is.read(new byte[100]);
      } finally {
        client.close();
      }
      if (verifyChecksum) {
        fail("Reading corrupted data should fail, as verify checksum is " +
            "enabled");
      }
    } catch (IOException e) {
      if (!verifyChecksum) {
        fail("Reading corrupted data should not fail, as verify checksum is " +
            "disabled");
      }
    }
  }

  @Test
  public void testGetKeyDetails() throws IOException {
    String volumeName = UUID.randomUUID().toString();
    String bucketName = UUID.randomUUID().toString();

    store.createVolume(volumeName);
    OzoneVolume volume = store.getVolume(volumeName);
    volume.createBucket(bucketName);
    OzoneBucket bucket = volume.getBucket(bucketName);
    String keyName = UUID.randomUUID().toString();
    String keyValue = RandomStringUtils.random(128);
    //String keyValue = "this is a test value.glx";
    // create the initial key with size 0, write will allocate the first block.
    OzoneOutputStream out = bucket.createKey(keyName,
        keyValue.getBytes(UTF_8).length, RATIS,
        ONE, new HashMap<>());
    out.write(keyValue.getBytes(UTF_8));
    out.close();

    // First, confirm the key info from the client matches the info in OM.
    OmKeyArgs.Builder builder = new OmKeyArgs.Builder();
    builder.setVolumeName(volumeName).setBucketName(bucketName)
        .setKeyName(keyName);
    OmKeyLocationInfo keyInfo = ozoneManager.lookupKey(builder.build()).
        getKeyLocationVersions().get(0).getBlocksLatestVersionOnly().get(0);
    long containerID = keyInfo.getContainerID();
    long localID = keyInfo.getLocalID();
    OzoneKeyDetails keyDetails = (OzoneKeyDetails)bucket.getKey(keyName);
    assertEquals(keyName, keyDetails.getName());

    List<OzoneKeyLocation> keyLocations = keyDetails.getOzoneKeyLocations();
    assertEquals(1, keyLocations.size());
    assertEquals(containerID, keyLocations.get(0).getContainerID());
    assertEquals(localID, keyLocations.get(0).getLocalID());

    // Make sure that the data size matched.
    assertEquals(keyValue.getBytes(UTF_8).length,
        keyLocations.get(0).getLength());

    // Second, sum the data size from chunks in Container via containerID
    // and localID, make sure the size equals to the size from keyDetails.
    ContainerInfo container = cluster.getStorageContainerManager()
        .getContainerManager().getContainer(ContainerID.valueOf(containerID));
    Pipeline pipeline = cluster.getStorageContainerManager()
        .getPipelineManager().getPipeline(container.getPipelineID());
    List<DatanodeDetails> datanodes = pipeline.getNodes();
    assertEquals(datanodes.size(), 1);

    DatanodeDetails datanodeDetails = datanodes.get(0);
    assertNotNull(datanodeDetails);
    HddsDatanodeService datanodeService = null;
    for (HddsDatanodeService datanodeServiceItr : cluster.getHddsDatanodes()) {
      if (datanodeDetails.equals(datanodeServiceItr.getDatanodeDetails())) {
        datanodeService = datanodeServiceItr;
        break;
      }
    }
    KeyValueContainerData containerData =
        (KeyValueContainerData)(datanodeService.getDatanodeStateMachine()
            .getContainer().getContainerSet().getContainer(containerID)
            .getContainerData());
    try (DBHandle db = BlockUtils.getDB(containerData, cluster.getConf());
         BlockIterator<BlockData> keyValueBlockIterator =
                db.getStore().getBlockIterator(containerID)) {
      while (keyValueBlockIterator.hasNext()) {
        BlockData blockData = keyValueBlockIterator.nextBlock();
        if (blockData.getBlockID().getLocalID() == localID) {
          long length = 0;
          List<ContainerProtos.ChunkInfo> chunks = blockData.getChunks();
          for (ContainerProtos.ChunkInfo chunk : chunks) {
            length += chunk.getLen();
          }
          assertEquals(length, keyValue.getBytes(UTF_8).length);
          break;
        }
      }
    }

    try (OzoneInputStream inputStream = keyDetails.getContent()) {
      assertInputStreamContent(keyValue, inputStream);
    }
  }

  private void assertInputStreamContent(String expected, InputStream is)
      throws IOException {
    byte[] fileContent = new byte[expected.getBytes(UTF_8).length];
    is.read(fileContent);
    assertEquals(expected, new String(fileContent, UTF_8));
  }

  /**
   * Tests reading a corrputed chunk file throws checksum exception.
   * @throws IOException
   */
  @Test
  public void testReadKeyWithCorruptedData() throws IOException {
    String volumeName = UUID.randomUUID().toString();
    String bucketName = UUID.randomUUID().toString();

    String value = "sample value";
    store.createVolume(volumeName);
    OzoneVolume volume = store.getVolume(volumeName);
    volume.createBucket(bucketName);
    OzoneBucket bucket = volume.getBucket(bucketName);
    String keyName = UUID.randomUUID().toString();

    // Write data into a key
    OzoneOutputStream out = bucket.createKey(keyName,
        value.getBytes(UTF_8).length, ReplicationType.RATIS,
        ONE, new HashMap<>());
    out.write(value.getBytes(UTF_8));
    out.close();

    // We need to find the location of the chunk file corresponding to the
    // data we just wrote.
    OzoneKey key = bucket.getKey(keyName);
    long containerID = ((OzoneKeyDetails) key).getOzoneKeyLocations().get(0)
        .getContainerID();

    // Get the container by traversing the datanodes. Atleast one of the
    // datanode must have this container.
    Container container = null;
    for (HddsDatanodeService hddsDatanode : cluster.getHddsDatanodes()) {
      container = hddsDatanode.getDatanodeStateMachine().getContainer()
          .getContainerSet().getContainer(containerID);
      if (container != null) {
        break;
      }
    }
    assertNotNull(container, "Container not found");
    corruptData(container, key);

    // Try reading the key. Since the chunk file is corrupted, it should
    // throw a checksum mismatch exception.
    try {
      try (OzoneInputStream is = bucket.readKey(keyName)) {
        is.read(new byte[100]);
      }
      fail("Reading corrupted data should fail.");
    } catch (IOException e) {
      GenericTestUtils.assertExceptionContains("Checksum mismatch", e);
    }
  }

  // Make this executed at last, for it has some side effect to other UTs
  @Test
  @Flaky("HDDS-6151")
  public void testZReadKeyWithUnhealthyContainerReplica() throws Exception {
    String volumeName = UUID.randomUUID().toString();
    String bucketName = UUID.randomUUID().toString();

    String value = "sample value";
    store.createVolume(volumeName);
    OzoneVolume volume = store.getVolume(volumeName);
    volume.createBucket(bucketName);
    OzoneBucket bucket = volume.getBucket(bucketName);
    String keyName1 = UUID.randomUUID().toString();

    // Write first key
    OzoneOutputStream out = bucket.createKey(keyName1,
        value.getBytes(UTF_8).length, ReplicationType.RATIS,
        THREE, new HashMap<>());
    out.write(value.getBytes(UTF_8));
    out.close();

    // Write second key
    String keyName2 = UUID.randomUUID().toString();
    value = "unhealthy container replica";
    out = bucket.createKey(keyName2,
        value.getBytes(UTF_8).length, ReplicationType.RATIS,
        THREE, new HashMap<>());
    out.write(value.getBytes(UTF_8));
    out.close();

    // Find container ID
    OzoneKey key = bucket.getKey(keyName2);
    long containerID = ((OzoneKeyDetails) key).getOzoneKeyLocations().get(0)
        .getContainerID();

    // Set container replica to UNHEALTHY
    Container container;
    int index = 1;
    List<HddsDatanodeService> involvedDNs = new ArrayList<>();
    for (HddsDatanodeService hddsDatanode : cluster.getHddsDatanodes()) {
      container = hddsDatanode.getDatanodeStateMachine().getContainer()
          .getContainerSet().getContainer(containerID);
      if (container == null) {
        continue;
      }
      container.markContainerUnhealthy();
      // Change first and second replica commit sequenceId
      if (index < 3) {
        long newBCSID = container.getBlockCommitSequenceId() - 1;
        KeyValueContainerData cData =
            (KeyValueContainerData) container.getContainerData();
        try (DBHandle db = BlockUtils.getDB(cData, cluster.getConf())) {
          db.getStore().getMetadataTable().put(cData.getBcsIdKey(),
              newBCSID);
        }
        container.updateBlockCommitSequenceId(newBCSID);
        index++;
      }
      involvedDNs.add(hddsDatanode);
    }

    // Restart DNs
    int dnCount = involvedDNs.size();
    for (index = 0; index < dnCount; index++) {
      if (index == dnCount - 1) {
        cluster.restartHddsDatanode(
            involvedDNs.get(index).getDatanodeDetails(), true);
      } else {
        cluster.restartHddsDatanode(
            involvedDNs.get(index).getDatanodeDetails(), false);
      }
    }

    StorageContainerManager scm = cluster.getStorageContainerManager();
    GenericTestUtils.waitFor(() -> {
      try {
        ContainerInfo containerInfo = scm.getContainerInfo(containerID);
        System.out.println("state " + containerInfo.getState());
        return containerInfo.getState() == HddsProtos.LifeCycleState.CLOSING;
      } catch (IOException e) {
        fail("Failed to get container info for " + e.getMessage());
        return false;
      }
    }, 1000, 10000);

    // Try reading keyName2
    try {
      GenericTestUtils.setLogLevel(XceiverClientGrpc.getLogger(), DEBUG);
      try (OzoneInputStream is = bucket.readKey(keyName2)) {
        byte[] content = new byte[100];
        is.read(content);
        String retValue = new String(content, UTF_8);
        assertTrue(value.equals(retValue.trim()));
      }
    } catch (IOException e) {
      fail("Reading unhealthy replica should succeed.");
    }
  }

  /**
   * Tests reading a corrputed chunk file throws checksum exception.
   * @throws IOException
   */
  @Test
  public void testReadKeyWithCorruptedDataWithMutiNodes() throws IOException {
    String volumeName = UUID.randomUUID().toString();
    String bucketName = UUID.randomUUID().toString();

    String value = "sample value";
    byte[] data = value.getBytes(UTF_8);
    store.createVolume(volumeName);
    OzoneVolume volume = store.getVolume(volumeName);
    volume.createBucket(bucketName);
    OzoneBucket bucket = volume.getBucket(bucketName);
    String keyName = UUID.randomUUID().toString();

    // Write data into a key
    OzoneOutputStream out = bucket.createKey(keyName,
        value.getBytes(UTF_8).length, ReplicationType.RATIS,
        THREE, new HashMap<>());
    out.write(value.getBytes(UTF_8));
    out.close();

    // We need to find the location of the chunk file corresponding to the
    // data we just wrote.
    OzoneKey key = bucket.getKey(keyName);
    List<OzoneKeyLocation> keyLocation =
        ((OzoneKeyDetails) key).getOzoneKeyLocations();
    assertFalse(keyLocation.isEmpty(), "Key location not found in OM");
    long containerID = ((OzoneKeyDetails) key).getOzoneKeyLocations().get(0)
        .getContainerID();

    // Get the container by traversing the datanodes.
    List<Container> containerList = new ArrayList<>();
    Container container;
    for (HddsDatanodeService hddsDatanode : cluster.getHddsDatanodes()) {
      container = hddsDatanode.getDatanodeStateMachine().getContainer()
          .getContainerSet().getContainer(containerID);
      if (container != null) {
        containerList.add(container);
        if (containerList.size() == 3) {
          break;
        }
      }
    }
    assertFalse(containerList.isEmpty(), "Container not found");
    corruptData(containerList.get(0), key);
    // Try reading the key. Read will fail on the first node and will eventually
    // failover to next replica
    try (OzoneInputStream is = bucket.readKey(keyName)) {
      byte[] b = new byte[data.length];
      is.read(b);
      assertArrayEquals(b, data);
    } catch (OzoneChecksumException e) {
      fail("Reading corrupted data should not fail.");
    }
    corruptData(containerList.get(1), key);
    // Try reading the key. Read will fail on the first node and will eventually
    // failover to next replica
    try (OzoneInputStream is = bucket.readKey(keyName)) {
      byte[] b = new byte[data.length];
      is.read(b);
      assertArrayEquals(b, data);
    } catch (OzoneChecksumException e) {
      fail("Reading corrupted data should not fail.");
    }
    corruptData(containerList.get(2), key);
    // Try reading the key. Read will fail here as all the replica are corrupt
    try (OzoneInputStream is = bucket.readKey(keyName)) {
      byte[] b = new byte[data.length];
      is.read(b);
      fail("Reading corrupted data should fail.");
    } catch (IOException e) {
      GenericTestUtils.assertExceptionContains("Checksum mismatch", e);
    }
  }

  private void corruptData(Container container, OzoneKey key)
      throws IOException {
    long containerID = ((OzoneKeyDetails) key).getOzoneKeyLocations().get(0)
        .getContainerID();
    long localID = ((OzoneKeyDetails) key).getOzoneKeyLocations().get(0)
        .getLocalID();
    // From the containerData, get the block iterator for all the blocks in
    // the container.
    KeyValueContainerData containerData =
        (KeyValueContainerData) container.getContainerData();
    try (DBHandle db = BlockUtils.getDB(containerData, cluster.getConf());
         BlockIterator<BlockData> keyValueBlockIterator =
                 db.getStore().getBlockIterator(containerID)) {
      // Find the block corresponding to the key we put. We use the localID of
      // the BlockData to identify out key.
      BlockData blockData = null;
      while (keyValueBlockIterator.hasNext()) {
        blockData = keyValueBlockIterator.nextBlock();
        if (blockData.getBlockID().getLocalID() == localID) {
          break;
        }
      }
      assertNotNull(blockData, "Block not found");

      // Get the location of the chunk file
      String containreBaseDir =
          container.getContainerData().getVolume().getHddsRootDir().getPath();
      File chunksLocationPath = KeyValueContainerLocationUtil
          .getChunksLocationPath(containreBaseDir, clusterId, containerID);
      byte[] corruptData = "corrupted data".getBytes(UTF_8);
      // Corrupt the contents of chunk files
      for (File file : FileUtils.listFiles(chunksLocationPath, null, false)) {
        FileUtils.writeByteArrayToFile(file, corruptData);
      }
    }
  }

  @Test
  public void testDeleteKey()
      throws Exception {

    String volumeName = UUID.randomUUID().toString();
    String bucketName = UUID.randomUUID().toString();
    String keyName = UUID.randomUUID().toString();
    String value = "sample value";
    store.createVolume(volumeName);
    OzoneVolume volume = store.getVolume(volumeName);
    volume.createBucket(bucketName);
    OzoneBucket bucket = volume.getBucket(bucketName);
    OzoneOutputStream out = bucket.createKey(keyName,
        value.getBytes(UTF_8).length, RATIS,
        ONE, new HashMap<>());
    out.write(value.getBytes(UTF_8));
    out.close();
    OzoneKey key = bucket.getKey(keyName);
    assertEquals(keyName, key.getName());
    bucket.deleteKey(keyName);

    OzoneTestUtils.expectOmException(KEY_NOT_FOUND,
        () -> bucket.getKey(keyName));
  }

  @Test
  public void testRenameKey()
      throws IOException {
    String volumeName = UUID.randomUUID().toString();
    String bucketName = UUID.randomUUID().toString();
    String fromKeyName = UUID.randomUUID().toString();
    String value = "sample value";
    store.createVolume(volumeName);
    OzoneVolume volume = store.getVolume(volumeName);
    volume.createBucket(bucketName);
    OzoneBucket bucket = volume.getBucket(bucketName);
    createTestKey(bucket, fromKeyName, value);
    BucketLayout bucketLayout = bucket.getBucketLayout();
    OMException oe = null;
    String toKeyName = "";

    if (!bucketLayout.isFileSystemOptimized()) {
      // Rename to an empty string should fail only in non FSO buckets
      try {
        bucket.renameKey(fromKeyName, toKeyName);
      } catch (OMException e) {
        oe = e;
      }
      assertEquals(ResultCodes.INVALID_KEY_NAME, oe.getResult());
    } else {
      // Rename to an empty key in FSO should be okay, as we are handling the
      // empty dest key on the server side and the source key name will be used
      bucket.renameKey(fromKeyName, toKeyName);
      OzoneKey emptyRenameKey = bucket.getKey(fromKeyName);
      assertEquals(fromKeyName, emptyRenameKey.getName());
    }

    toKeyName = UUID.randomUUID().toString();
    bucket.renameKey(fromKeyName, toKeyName);

    // Lookup for old key should fail.
    try {
      bucket.getKey(fromKeyName);
    } catch (OMException e) {
      oe = e;
    }
    assertEquals(KEY_NOT_FOUND, oe.getResult());

    OzoneKey key = bucket.getKey(toKeyName);
    assertEquals(toKeyName, key.getName());
  }

  /**
   * Test of the deprecated rename keys API, which only works on object store
   * or legacy buckets.
   */
  @Test
  public void testKeysRename() throws Exception {
    String volumeName = UUID.randomUUID().toString();
    String bucketName = UUID.randomUUID().toString();
    String keyName1 = "dir/file1";
    String keyName2 = "dir/file2";

    String newKeyName1 = "dir/key1";
    String newKeyName2 = "dir/key2";

    String value = "sample value";
    store.createVolume(volumeName);
    OzoneVolume volume = store.getVolume(volumeName);
    volume.createBucket(bucketName, BucketArgs.newBuilder()
        .setBucketLayout(BucketLayout.OBJECT_STORE).build());
    OzoneBucket bucket = volume.getBucket(bucketName);
    createTestKey(bucket, keyName1, value);
    createTestKey(bucket, keyName2, value);

    Map<String, String> keyMap = new HashMap();
    keyMap.put(keyName1, newKeyName1);
    keyMap.put(keyName2, newKeyName2);
    bucket.renameKeys(keyMap);

    // new key should exist
    assertEquals(newKeyName1, bucket.getKey(newKeyName1).getName());
    assertEquals(newKeyName2, bucket.getKey(newKeyName2).getName());

    // old key should not exist
    assertKeyRenamedEx(bucket, keyName1);
    assertKeyRenamedEx(bucket, keyName2);
  }

  /**
   * Legacy test for the keys rename API, which is deprecated and only
   * supported for object store and legacy bucket layout types.
   */
  @Test
  public void testKeysRenameFail() throws Exception {
    String volumeName = UUID.randomUUID().toString();
    String bucketName = UUID.randomUUID().toString();
    String keyName1 = "dir/file1";
    String keyName2 = "dir/file2";

    String newKeyName1 = "dir/key1";
    String newKeyName2 = "dir/key2";

    String value = "sample value";
    store.createVolume(volumeName);
    OzoneVolume volume = store.getVolume(volumeName);
    volume.createBucket(bucketName, BucketArgs.newBuilder()
        .setBucketLayout(BucketLayout.OBJECT_STORE).build());
    OzoneBucket bucket = volume.getBucket(bucketName);

    // Create only keyName1 to test the partial failure of renameKeys.
    createTestKey(bucket, keyName1, value);

    Map<String, String> keyMap = new HashMap();
    keyMap.put(keyName1, newKeyName1);
    keyMap.put(keyName2, newKeyName2);

    try {
      bucket.renameKeys(keyMap);
    } catch (OMException ex) {
      assertEquals(PARTIAL_RENAME, ex.getResult());
    }

    // newKeyName1 should exist
    assertEquals(newKeyName1, bucket.getKey(newKeyName1).getName());
    // newKeyName2 should not exist
    assertKeyRenamedEx(bucket, keyName2);
  }

  @Test
  public void testListVolume() throws IOException {
    String volBase = "vol-list-";
    //Create 10 volume vol-list-a-0-<random> to vol-list-a-9-<random>
    String volBaseNameA = volBase + "a-";
    for (int i = 0; i < 10; i++) {
      store.createVolume(
          volBaseNameA + i + "-" + RandomStringUtils.randomNumeric(5));
    }
    //Create 10 volume vol-list-b-0-<random> to vol-list-b-9-<random>
    String volBaseNameB = volBase + "b-";
    for (int i = 0; i < 10; i++) {
      store.createVolume(
          volBaseNameB + i + "-" + RandomStringUtils.randomNumeric(5));
    }
    Iterator<? extends OzoneVolume> volIterator = store.listVolumes(volBase);
    int totalVolumeCount = 0;
    while (volIterator.hasNext()) {
      volIterator.next();
      totalVolumeCount++;
    }
    assertEquals(20, totalVolumeCount);
    Iterator<? extends OzoneVolume> volAIterator = store.listVolumes(
        volBaseNameA);
    for (int i = 0; i < 10; i++) {
      assertTrue(volAIterator.next().getName()
          .startsWith(volBaseNameA + i + "-"));
    }
    assertFalse(volAIterator.hasNext());
    Iterator<? extends OzoneVolume> volBIterator = store.listVolumes(
        volBaseNameB);
    for (int i = 0; i < 10; i++) {
      assertTrue(volBIterator.next().getName()
          .startsWith(volBaseNameB + i + "-"));
    }
    assertFalse(volBIterator.hasNext());
    Iterator<? extends OzoneVolume> iter = store.listVolumes(volBaseNameA +
        "1-");
    assertTrue(iter.next().getName().startsWith(volBaseNameA + "1-"));
    assertFalse(iter.hasNext());
  }

  @Test
  public void testListBucket()
      throws IOException {
    String volumeA = "vol-a-" + RandomStringUtils.randomNumeric(5);
    String volumeB = "vol-b-" + RandomStringUtils.randomNumeric(5);
    store.createVolume(volumeA);
    store.createVolume(volumeB);
    OzoneVolume volA = store.getVolume(volumeA);
    OzoneVolume volB = store.getVolume(volumeB);


    //Create 10 buckets in  vol-a-<random> and 10 in vol-b-<random>
    String bucketBaseNameA = "bucket-a-";
    for (int i = 0; i < 10; i++) {
      String bucketName = bucketBaseNameA +
          i + "-" + RandomStringUtils.randomNumeric(5);
      volA.createBucket(bucketName);
      store.createSnapshot(volumeA, bucketName, null);
      bucketName = bucketBaseNameA +
          i + "-" + RandomStringUtils.randomNumeric(5);
      volB.createBucket(bucketName);
      store.createSnapshot(volumeB, bucketName, null);
    }
    //Create 10 buckets in vol-a-<random> and 10 in vol-b-<random>
    String bucketBaseNameB = "bucket-b-";
    for (int i = 0; i < 10; i++) {
      String bucketName = bucketBaseNameB +
          i + "-" + RandomStringUtils.randomNumeric(5);
      volA.createBucket(bucketName);
      store.createSnapshot(volumeA, bucketName, null);
      volB.createBucket(
          bucketBaseNameB + i + "-" + RandomStringUtils.randomNumeric(5));
    }
    assertBucketCount(volA, "bucket-", null, false, 20);
    assertBucketCount(volA, "bucket-", null, true, 20);
    assertBucketCount(volB, "bucket-", null, false, 20);
    assertBucketCount(volB, "bucket-", null, true, 10);
    assertBucketCount(volA, bucketBaseNameA, null, false, 10);
    assertBucketCount(volA, bucketBaseNameA, null, true, 10);
    assertBucketCount(volB, bucketBaseNameB, null, false, 10);
    assertBucketCount(volB, bucketBaseNameB, null, true, 0);
    assertBucketCount(volA, bucketBaseNameB, null, false, 10);
    assertBucketCount(volA, bucketBaseNameB, null, true, 10);
    assertBucketCount(volB, bucketBaseNameA, null, false, 10);
    assertBucketCount(volB, bucketBaseNameA, null, true, 10);

  }

  @Test
  public void testListBucketsOnEmptyVolume()
      throws IOException {
    String volume = "vol-empty";
    store.createVolume(volume);
    OzoneVolume vol = store.getVolume(volume);
    Iterator<? extends OzoneBucket> buckets = vol.listBuckets("");
    while (buckets.hasNext()) {
      fail();
    }
  }

  @Test
  public void testListBucketsReplicationConfig()
      throws Exception {
    String volumeName = UUID.randomUUID().toString();
    getStore().createVolume(volumeName);
    OzoneVolume volume = getStore().getVolume(volumeName);

    // bucket-level replication config: null (default)
    String bucketName = UUID.randomUUID().toString();
    volume.createBucket(bucketName);
    OzoneBucket bucket = volume.listBuckets(bucketName).next();
    assertNull(bucket.getReplicationConfig());

    // bucket-level replication config: EC/rs-3-2-1024k
    String ecBucketName = UUID.randomUUID().toString();
    ReplicationConfig ecRepConfig = new ECReplicationConfig(3, 2);
    BucketArgs ecBucketArgs = BucketArgs.newBuilder()
        .setDefaultReplicationConfig(
            new DefaultReplicationConfig(ecRepConfig))
        .build();
    volume.createBucket(ecBucketName, ecBucketArgs);
    OzoneBucket ecBucket = volume.listBuckets(ecBucketName).next();
    assertEquals(ecRepConfig, ecBucket.getReplicationConfig());

    // bucket-level replication config: RATIS/THREE
    String ratisBucketName = UUID.randomUUID().toString();
    ReplicationConfig ratisRepConfig = ReplicationConfig
        .fromTypeAndFactor(RATIS, THREE);
    BucketArgs ratisBucketArgs = BucketArgs.newBuilder()
        .setDefaultReplicationConfig(
            new DefaultReplicationConfig(ratisRepConfig))
        .build();
    volume.createBucket(ratisBucketName, ratisBucketArgs);
    OzoneBucket ratisBucket = volume.listBuckets(ratisBucketName).next();
    assertEquals(ratisRepConfig, ratisBucket.getReplicationConfig());
  }

  @Test
  public void testListKey()
      throws IOException {
    String volumeA = "vol-a-" + RandomStringUtils.randomNumeric(5);
    String volumeB = "vol-b-" + RandomStringUtils.randomNumeric(5);
    String bucketA = "buc-a-" + RandomStringUtils.randomNumeric(5);
    String bucketB = "buc-b-" + RandomStringUtils.randomNumeric(5);
    store.createVolume(volumeA);
    store.createVolume(volumeB);
    OzoneVolume volA = store.getVolume(volumeA);
    OzoneVolume volB = store.getVolume(volumeB);
    volA.createBucket(bucketA);
    volA.createBucket(bucketB);
    volB.createBucket(bucketA);
    volB.createBucket(bucketB);
    OzoneBucket volAbucketA = volA.getBucket(bucketA);
    OzoneBucket volAbucketB = volA.getBucket(bucketB);
    OzoneBucket volBbucketA = volB.getBucket(bucketA);
    OzoneBucket volBbucketB = volB.getBucket(bucketB);

    /*
    Create 10 keys in  vol-a-<random>/buc-a-<random>,
    vol-a-<random>/buc-b-<random>, vol-b-<random>/buc-a-<random> and
    vol-b-<random>/buc-b-<random>
     */
    String keyBaseA = "key-a-";
    for (int i = 0; i < 10; i++) {
      byte[] value = RandomStringUtils.randomAscii(10240).getBytes(UTF_8);
      OzoneOutputStream one = volAbucketA.createKey(
          keyBaseA + i + "-" + RandomStringUtils.randomNumeric(5),
          value.length, RATIS, ONE,
          new HashMap<>());
      one.write(value);
      one.close();
      OzoneOutputStream two = volAbucketB.createKey(
          keyBaseA + i + "-" + RandomStringUtils.randomNumeric(5),
          value.length, RATIS, ONE,
          new HashMap<>());
      two.write(value);
      two.close();
      OzoneOutputStream three = volBbucketA.createKey(
          keyBaseA + i + "-" + RandomStringUtils.randomNumeric(5),
          value.length, RATIS, ONE,
          new HashMap<>());
      three.write(value);
      three.close();
      OzoneOutputStream four = volBbucketB.createKey(
          keyBaseA + i + "-" + RandomStringUtils.randomNumeric(5),
          value.length, RATIS, ONE,
          new HashMap<>());
      four.write(value);
      four.close();
    }
    /*
    Create 10 keys in  vol-a-<random>/buc-a-<random>,
    vol-a-<random>/buc-b-<random>, vol-b-<random>/buc-a-<random> and
    vol-b-<random>/buc-b-<random>
     */
    String keyBaseB = "key-b-";
    for (int i = 0; i < 10; i++) {
      byte[] value = RandomStringUtils.randomAscii(10240).getBytes(UTF_8);
      OzoneOutputStream one = volAbucketA.createKey(
          keyBaseB + i + "-" + RandomStringUtils.randomNumeric(5),
          value.length, RATIS, ONE,
          new HashMap<>());
      one.write(value);
      one.close();
      OzoneOutputStream two = volAbucketB.createKey(
          keyBaseB + i + "-" + RandomStringUtils.randomNumeric(5),
          value.length, RATIS, ONE,
          new HashMap<>());
      two.write(value);
      two.close();
      OzoneOutputStream three = volBbucketA.createKey(
          keyBaseB + i + "-" + RandomStringUtils.randomNumeric(5),
          value.length, RATIS, ONE,
          new HashMap<>());
      three.write(value);
      three.close();
      OzoneOutputStream four = volBbucketB.createKey(
          keyBaseB + i + "-" + RandomStringUtils.randomNumeric(5),
          value.length, RATIS, ONE,
          new HashMap<>());
      four.write(value);
      four.close();
    }
    Iterator<? extends OzoneKey> volABucketAIter =
        volAbucketA.listKeys("key-");
    int volABucketAKeyCount = 0;
    while (volABucketAIter.hasNext()) {
      volABucketAIter.next();
      volABucketAKeyCount++;
    }
    assertEquals(20, volABucketAKeyCount);
    Iterator<? extends OzoneKey> volABucketBIter =
        volAbucketB.listKeys("key-");
    int volABucketBKeyCount = 0;
    while (volABucketBIter.hasNext()) {
      volABucketBIter.next();
      volABucketBKeyCount++;
    }
    assertEquals(20, volABucketBKeyCount);
    Iterator<? extends OzoneKey> volBBucketAIter =
        volBbucketA.listKeys("key-");
    int volBBucketAKeyCount = 0;
    while (volBBucketAIter.hasNext()) {
      volBBucketAIter.next();
      volBBucketAKeyCount++;
    }
    assertEquals(20, volBBucketAKeyCount);
    Iterator<? extends OzoneKey> volBBucketBIter =
        volBbucketB.listKeys("key-");
    int volBBucketBKeyCount = 0;
    while (volBBucketBIter.hasNext()) {
      volBBucketBIter.next();
      volBBucketBKeyCount++;
    }
    assertEquals(20, volBBucketBKeyCount);
    Iterator<? extends OzoneKey> volABucketAKeyAIter =
        volAbucketA.listKeys("key-a-");
    int volABucketAKeyACount = 0;
    while (volABucketAKeyAIter.hasNext()) {
      volABucketAKeyAIter.next();
      volABucketAKeyACount++;
    }
    assertEquals(10, volABucketAKeyACount);
    Iterator<? extends OzoneKey> volABucketAKeyBIter =
        volAbucketA.listKeys("key-b-");
    for (int i = 0; i < 10; i++) {
      assertTrue(volABucketAKeyBIter.next().getName()
          .startsWith("key-b-" + i + "-"));
    }
    assertFalse(volABucketBIter.hasNext());
  }

  @Test
  public void testListKeyOnEmptyBucket()
      throws IOException {
    String volume = "vol-" + RandomStringUtils.randomNumeric(5);
    String bucket = "buc-" + RandomStringUtils.randomNumeric(5);
    store.createVolume(volume);
    OzoneVolume vol = store.getVolume(volume);
    vol.createBucket(bucket);
    OzoneBucket buc = vol.getBucket(bucket);
    Iterator<? extends OzoneKey> keys = buc.listKeys("");
    while (keys.hasNext()) {
      fail();
    }
  }

  static Stream<ReplicationConfig> replicationConfigs() {
    return Stream.of(
        RatisReplicationConfig.getInstance(HddsProtos.ReplicationFactor.ONE),
        RatisReplicationConfig.getInstance(HddsProtos.ReplicationFactor.THREE),
        new ECReplicationConfig(3, 2)
    );
  }

  @ParameterizedTest
  @MethodSource("replicationConfigs")
  void testInitiateMultipartUpload(ReplicationConfig replicationConfig)
      throws IOException {
    String volumeName = UUID.randomUUID().toString();
    String bucketName = UUID.randomUUID().toString();
    String keyName = UUID.randomUUID().toString();

    store.createVolume(volumeName);
    OzoneVolume volume = store.getVolume(volumeName);
    volume.createBucket(bucketName);
    OzoneBucket bucket = volume.getBucket(bucketName);
    OmMultipartInfo multipartInfo = bucket.initiateMultipartUpload(keyName,
        replicationConfig);

    assertNotNull(multipartInfo);
    String uploadID = multipartInfo.getUploadID();
    assertEquals(volumeName, multipartInfo.getVolumeName());
    assertEquals(bucketName, multipartInfo.getBucketName());
    assertEquals(keyName, multipartInfo.getKeyName());
    assertNotNull(multipartInfo.getUploadID());

    // Call initiate multipart upload for the same key again, this should
    // generate a new uploadID.
    multipartInfo = bucket.initiateMultipartUpload(keyName,
        replicationConfig);

    assertNotNull(multipartInfo);
    assertEquals(volumeName, multipartInfo.getVolumeName());
    assertEquals(bucketName, multipartInfo.getBucketName());
    assertEquals(keyName, multipartInfo.getKeyName());
    assertNotEquals(multipartInfo.getUploadID(), uploadID);
    assertNotNull(multipartInfo.getUploadID());
  }


  @Test
  public void testInitiateMultipartUploadWithDefaultReplication() throws
      IOException {
    String volumeName = UUID.randomUUID().toString();
    String bucketName = UUID.randomUUID().toString();
    String keyName = UUID.randomUUID().toString();

    store.createVolume(volumeName);
    OzoneVolume volume = store.getVolume(volumeName);
    volume.createBucket(bucketName);
    OzoneBucket bucket = volume.getBucket(bucketName);
    OmMultipartInfo multipartInfo = bucket.initiateMultipartUpload(keyName);

    assertNotNull(multipartInfo);
    String uploadID = multipartInfo.getUploadID();
    assertEquals(volumeName, multipartInfo.getVolumeName());
    assertEquals(bucketName, multipartInfo.getBucketName());
    assertEquals(keyName, multipartInfo.getKeyName());
    assertNotNull(multipartInfo.getUploadID());

    // Call initiate multipart upload for the same key again, this should
    // generate a new uploadID.
    multipartInfo = bucket.initiateMultipartUpload(keyName);

    assertNotNull(multipartInfo);
    assertEquals(volumeName, multipartInfo.getVolumeName());
    assertEquals(bucketName, multipartInfo.getBucketName());
    assertEquals(keyName, multipartInfo.getKeyName());
    assertNotEquals(multipartInfo.getUploadID(), uploadID);
    assertNotNull(multipartInfo.getUploadID());
  }

  @ParameterizedTest
  @MethodSource("replicationConfigs")
  void testUploadPartWithNoOverride(ReplicationConfig replication)
      throws IOException {
    String volumeName = UUID.randomUUID().toString();
    String bucketName = UUID.randomUUID().toString();
    String keyName = UUID.randomUUID().toString();
    String sampleData = "sample Value";

    store.createVolume(volumeName);
    OzoneVolume volume = store.getVolume(volumeName);
    volume.createBucket(bucketName);
    OzoneBucket bucket = volume.getBucket(bucketName);
    OmMultipartInfo multipartInfo = bucket.initiateMultipartUpload(keyName,
        replication);

    assertNotNull(multipartInfo);
    String uploadID = multipartInfo.getUploadID();
    assertEquals(volumeName, multipartInfo.getVolumeName());
    assertEquals(bucketName, multipartInfo.getBucketName());
    assertEquals(keyName, multipartInfo.getKeyName());
    assertNotNull(multipartInfo.getUploadID());

    OzoneOutputStream ozoneOutputStream = bucket.createMultipartKey(keyName,
        sampleData.length(), 1, uploadID);
    ozoneOutputStream.write(string2Bytes(sampleData), 0, sampleData.length());
    ozoneOutputStream.close();

    OmMultipartCommitUploadPartInfo commitUploadPartInfo = ozoneOutputStream
        .getCommitUploadPartInfo();

    assertNotNull(commitUploadPartInfo);
    assertNotNull(commitUploadPartInfo.getPartName());
  }

  @ParameterizedTest
  @MethodSource("replicationConfigs")
  void testUploadPartOverride(ReplicationConfig replication)
      throws IOException {

    String volumeName = UUID.randomUUID().toString();
    String bucketName = UUID.randomUUID().toString();
    String keyName = UUID.randomUUID().toString();
    String sampleData = "sample Value";
    int partNumber = 1;

    store.createVolume(volumeName);
    OzoneVolume volume = store.getVolume(volumeName);
    volume.createBucket(bucketName);
    OzoneBucket bucket = volume.getBucket(bucketName);
    OmMultipartInfo multipartInfo = bucket.initiateMultipartUpload(keyName,
        replication);

    assertNotNull(multipartInfo);
    String uploadID = multipartInfo.getUploadID();
    assertEquals(volumeName, multipartInfo.getVolumeName());
    assertEquals(bucketName, multipartInfo.getBucketName());
    assertEquals(keyName, multipartInfo.getKeyName());
    assertNotNull(multipartInfo.getUploadID());

    OzoneOutputStream ozoneOutputStream = bucket.createMultipartKey(keyName,
        sampleData.length(), partNumber, uploadID);
    ozoneOutputStream.write(string2Bytes(sampleData), 0, sampleData.length());
    ozoneOutputStream.close();

    OmMultipartCommitUploadPartInfo commitUploadPartInfo = ozoneOutputStream
        .getCommitUploadPartInfo();

    assertNotNull(commitUploadPartInfo);
    String partName = commitUploadPartInfo.getPartName();
    assertNotNull(commitUploadPartInfo.getPartName());

    // Overwrite the part by creating part key with same part number
    // and different content.
    sampleData = "sample Data Changed";
    ozoneOutputStream = bucket.createMultipartKey(keyName,
        sampleData.length(), partNumber, uploadID);
    ozoneOutputStream.write(string2Bytes(sampleData), 0, "name".length());
    ozoneOutputStream.close();

    commitUploadPartInfo = ozoneOutputStream
        .getCommitUploadPartInfo();

    assertNotNull(commitUploadPartInfo);
    assertNotNull(commitUploadPartInfo.getPartName());

    // AWS S3 for same content generates same partName during upload part.
    // In AWS S3 ETag is generated from md5sum. In Ozone right now we
    // don't do this. For now to make things work for large file upload
    // through aws s3 cp, the partName are generated in a predictable fashion.
    // So, when a part is override partNames will still be same irrespective
    // of content in ozone s3. This will make S3 Mpu completeMPU pass when
    // comparing part names and large file uploads work using aws cp.
    assertEquals(partName, commitUploadPartInfo.getPartName(),
        "Part names should be same");
  }

  @Test
  public void testNoSuchUploadError() throws Exception {
    String volumeName = UUID.randomUUID().toString();
    String bucketName = UUID.randomUUID().toString();
    String keyName = UUID.randomUUID().toString();
    String sampleData = "sample Value";

    store.createVolume(volumeName);
    OzoneVolume volume = store.getVolume(volumeName);
    volume.createBucket(bucketName);
    OzoneBucket bucket = volume.getBucket(bucketName);

    String uploadID = "random";
    OzoneTestUtils
        .expectOmException(NO_SUCH_MULTIPART_UPLOAD_ERROR, () ->
            bucket
                .createMultipartKey(keyName, sampleData.length(), 1, uploadID));
  }

  @Test
  public void testMultipartUploadWithACL() throws Exception {
    String volumeName = UUID.randomUUID().toString();
    String bucketName = UUID.randomUUID().toString();
    String keyName = UUID.randomUUID().toString();

    store.createVolume(volumeName);
    OzoneVolume volume = store.getVolume(volumeName);
    // TODO: HDDS-3402. Files/dirs in FSO buckets currently do not inherit
    //  parent ACLs.
    volume.createBucket(bucketName, BucketArgs.newBuilder()
        .setBucketLayout(BucketLayout.OBJECT_STORE).build());
    OzoneBucket bucket = volume.getBucket(bucketName);

    // Add ACL on Bucket
    OzoneAcl acl1 = new OzoneAcl(USER, "Monday", ACLType.ALL, DEFAULT);
    OzoneAcl acl2 = new OzoneAcl(USER, "Friday", ACLType.ALL, DEFAULT);
    OzoneAcl acl3 = new OzoneAcl(USER, "Jan", ACLType.ALL, ACCESS);
    OzoneAcl acl4 = new OzoneAcl(USER, "Feb", ACLType.ALL, ACCESS);
    bucket.addAcl(acl1);
    bucket.addAcl(acl2);
    bucket.addAcl(acl3);
    bucket.addAcl(acl4);

    ReplicationConfig replication = RatisReplicationConfig.getInstance(
        HddsProtos.ReplicationFactor.ONE);
    doMultipartUpload(bucket, keyName, (byte)98, replication);
    OzoneObj keyObj = OzoneObjInfo.Builder.newBuilder()
        .setBucketName(bucketName)
        .setVolumeName(volumeName).setKeyName(keyName)
        .setResType(OzoneObj.ResourceType.KEY)
        .setStoreType(OzoneObj.StoreType.OZONE).build();
    List<OzoneAcl> aclList = store.getAcl(keyObj);
    // key should inherit bucket's DEFAULT type acl
    assertTrue(aclList.stream().anyMatch(
        acl -> acl.getName().equals(acl1.getName())));
    assertTrue(aclList.stream().anyMatch(
        acl -> acl.getName().equals(acl2.getName())));

    // kye should not inherit bucket's ACCESS type acl
    assertFalse(aclList.stream().anyMatch(
        acl -> acl.getName().equals(acl3.getName())));
    assertFalse(aclList.stream().anyMatch(
        acl -> acl.getName().equals(acl4.getName())));

    // User without permission should fail to upload the object
    String userName = "test-user";
    UserGroupInformation remoteUser =
        UserGroupInformation.createRemoteUser(userName);
    try (OzoneClient client =
        remoteUser.doAs((PrivilegedExceptionAction<OzoneClient>)
            () -> OzoneClientFactory.getRpcClient(cluster.getConf()))) {
      OzoneAcl acl5 = new OzoneAcl(USER, userName, ACLType.READ, DEFAULT);
      OzoneAcl acl6 = new OzoneAcl(USER, userName, ACLType.READ, ACCESS);
      OzoneObj volumeObj = OzoneObjInfo.Builder.newBuilder()
          .setVolumeName(volumeName).setStoreType(OzoneObj.StoreType.OZONE)
          .setResType(OzoneObj.ResourceType.VOLUME).build();
      OzoneObj bucketObj = OzoneObjInfo.Builder.newBuilder()
          .setVolumeName(volumeName).setBucketName(bucketName)
          .setStoreType(OzoneObj.StoreType.OZONE)
          .setResType(OzoneObj.ResourceType.BUCKET).build();
      store.addAcl(volumeObj, acl5);
      store.addAcl(volumeObj, acl6);
      store.addAcl(bucketObj, acl5);
      store.addAcl(bucketObj, acl6);

      // User without permission cannot start multi-upload
      String keyName2 = UUID.randomUUID().toString();
      OzoneBucket bucket2 = client.getObjectStore().getVolume(volumeName)
          .getBucket(bucketName);
      try {
        initiateMultipartUpload(bucket2, keyName2, anyReplication());
        fail("User without permission should fail");
      } catch (Exception e) {
        assertTrue(e instanceof OMException);
        assertEquals(ResultCodes.PERMISSION_DENIED,
            ((OMException) e).getResult());
      }

      // Add create permission for user, and try multi-upload init again
      OzoneAcl acl7 = new OzoneAcl(USER, userName, ACLType.CREATE, DEFAULT);
      OzoneAcl acl8 = new OzoneAcl(USER, userName, ACLType.CREATE, ACCESS);
      OzoneAcl acl9 = new OzoneAcl(USER, userName, WRITE, DEFAULT);
      OzoneAcl acl10 = new OzoneAcl(USER, userName, WRITE, ACCESS);
      store.addAcl(volumeObj, acl7);
      store.addAcl(volumeObj, acl8);
      store.addAcl(volumeObj, acl9);
      store.addAcl(volumeObj, acl10);

      store.addAcl(bucketObj, acl7);
      store.addAcl(bucketObj, acl8);
      store.addAcl(bucketObj, acl9);
      store.addAcl(bucketObj, acl10);
      String uploadId = initiateMultipartUpload(bucket2, keyName2,
          anyReplication());

      // Upload part
      byte[] data = generateData(OzoneConsts.OM_MULTIPART_MIN_SIZE, (byte) 1);
      String partName = uploadPart(bucket, keyName2, uploadId, 1, data);
      Map<Integer, String> partsMap = new TreeMap<>();
      partsMap.put(1, partName);

      // Complete multipart upload request
      completeMultipartUpload(bucket2, keyName2, uploadId, partsMap);

      // User without permission cannot read multi-uploaded object
      try (OzoneInputStream ignored = bucket2.readKey(keyName)) {
        fail("User without permission should fail");
      } catch (Exception e) {
        assertTrue(e instanceof OMException);
        assertEquals(ResultCodes.PERMISSION_DENIED,
            ((OMException) e).getResult());
      }
    }
  }

  @ParameterizedTest
  @MethodSource("replicationConfigs")
  void testMultipartUploadOverride(ReplicationConfig replication)
      throws Exception {
    String volumeName = UUID.randomUUID().toString();
    String bucketName = UUID.randomUUID().toString();
    String keyName = UUID.randomUUID().toString();

    store.createVolume(volumeName);
    OzoneVolume volume = store.getVolume(volumeName);
    volume.createBucket(bucketName);
    OzoneBucket bucket = volume.getBucket(bucketName);

    doMultipartUpload(bucket, keyName, (byte)96, replication);

    // Initiate Multipart upload again, now we should read latest version, as
    // read always reads latest blocks.
    doMultipartUpload(bucket, keyName, (byte)97, replication);

  }


  @Test
  public void testMultipartUploadWithPartsLessThanMinSize() throws Exception {
    String volumeName = UUID.randomUUID().toString();
    String bucketName = UUID.randomUUID().toString();
    String keyName = UUID.randomUUID().toString();

    store.createVolume(volumeName);
    OzoneVolume volume = store.getVolume(volumeName);
    volume.createBucket(bucketName);
    OzoneBucket bucket = volume.getBucket(bucketName);

    // Initiate multipart upload
    String uploadID = initiateMultipartUpload(bucket, keyName,
        anyReplication());

    // Upload Parts
    Map<Integer, String> partsMap = new TreeMap<>();
    // Uploading part 1 with less than min size
    String partName = uploadPart(bucket, keyName, uploadID, 1,
        "data".getBytes(UTF_8));
    partsMap.put(1, partName);

    partName = uploadPart(bucket, keyName, uploadID, 2,
        "data".getBytes(UTF_8));
    partsMap.put(2, partName);


    // Complete multipart upload

    OzoneTestUtils.expectOmException(ResultCodes.ENTITY_TOO_SMALL,
        () -> completeMultipartUpload(bucket, keyName, uploadID, partsMap));

  }
  @Test
  public void testMultipartUploadWithPartsMisMatchWithListSizeDifferent()
      throws Exception {
    String volumeName = UUID.randomUUID().toString();
    String bucketName = UUID.randomUUID().toString();
    String keyName = UUID.randomUUID().toString();

    store.createVolume(volumeName);
    OzoneVolume volume = store.getVolume(volumeName);
    volume.createBucket(bucketName);
    OzoneBucket bucket = volume.getBucket(bucketName);

    String uploadID = initiateMultipartUpload(bucket, keyName,
        anyReplication());

    // We have not uploaded any parts, but passing some list it should throw
    // error.
    TreeMap<Integer, String> partsMap = new TreeMap<>();
    partsMap.put(1, UUID.randomUUID().toString());

    OzoneTestUtils.expectOmException(ResultCodes.INVALID_PART,
        () -> completeMultipartUpload(bucket, keyName, uploadID, partsMap));

  }

  @Test
  public void testMultipartUploadWithPartsMisMatchWithIncorrectPartName()
      throws Exception {
    String volumeName = UUID.randomUUID().toString();
    String bucketName = UUID.randomUUID().toString();
    String keyName = UUID.randomUUID().toString();

    store.createVolume(volumeName);
    OzoneVolume volume = store.getVolume(volumeName);
    volume.createBucket(bucketName);
    OzoneBucket bucket = volume.getBucket(bucketName);

    ReplicationConfig replication = RatisReplicationConfig.getInstance(
        HddsProtos.ReplicationFactor.ONE);
    String uploadID = initiateMultipartUpload(bucket, keyName, replication);

    uploadPart(bucket, keyName, uploadID, 1, "data".getBytes(UTF_8));
    // We have not uploaded any parts, but passing some list it should throw
    // error.
    TreeMap<Integer, String> partsMap = new TreeMap<>();
    partsMap.put(1, UUID.randomUUID().toString());

    OzoneTestUtils.expectOmException(ResultCodes.INVALID_PART,
        () -> completeMultipartUpload(bucket, keyName, uploadID, partsMap));

  }

  @Test
  public void testMultipartUploadWithMissingParts() throws Exception {
    String volumeName = UUID.randomUUID().toString();
    String bucketName = UUID.randomUUID().toString();
    String keyName = UUID.randomUUID().toString();

    store.createVolume(volumeName);
    OzoneVolume volume = store.getVolume(volumeName);
    volume.createBucket(bucketName);
    OzoneBucket bucket = volume.getBucket(bucketName);

    ReplicationConfig replication = RatisReplicationConfig.getInstance(
        HddsProtos.ReplicationFactor.ONE);
    String uploadID = initiateMultipartUpload(bucket, keyName, replication);

    uploadPart(bucket, keyName, uploadID, 1, "data".getBytes(UTF_8));
    // We have not uploaded any parts, but passing some list it should throw
    // error.
    TreeMap<Integer, String> partsMap = new TreeMap<>();
    partsMap.put(3, "random");

    OzoneTestUtils.expectOmException(ResultCodes.INVALID_PART,
        () -> completeMultipartUpload(bucket, keyName, uploadID, partsMap));
  }

  @Test
  public void testMultipartPartNumberExceedingAllowedRange() throws Exception {
    String volumeName = UUID.randomUUID().toString();
    String bucketName = UUID.randomUUID().toString();
    String keyName = UUID.randomUUID().toString();
    String sampleData = "sample Value";

    store.createVolume(volumeName);
    OzoneVolume volume = store.getVolume(volumeName);
    volume.createBucket(bucketName);
    OzoneBucket bucket = volume.getBucket(bucketName);
    OmMultipartInfo multipartInfo = bucket.initiateMultipartUpload(keyName);
    assertNotNull(multipartInfo);
    String uploadID = multipartInfo.getUploadID();

    // Multipart part number must be an integer between 1 and 10000. So the
    // part number 1, 5000, 10000 will succeed,
    // the part number 0, 10001 will fail.
    bucket.createMultipartKey(keyName, sampleData.length(), 1, uploadID);
    bucket.createMultipartKey(keyName, sampleData.length(), 5000, uploadID);
    bucket.createMultipartKey(keyName, sampleData.length(), 10000, uploadID);
    OzoneTestUtils.expectOmException(ResultCodes.INVALID_PART, () ->
        bucket.createMultipartKey(
            keyName, sampleData.length(), 0, uploadID));
    OzoneTestUtils.expectOmException(ResultCodes.INVALID_PART, () ->
        bucket.createMultipartKey(
            keyName, sampleData.length(), 10001, uploadID));
  }

  @Test
  public void testAbortUploadFail() throws Exception {
    String volumeName = UUID.randomUUID().toString();
    String bucketName = UUID.randomUUID().toString();
    String keyName = UUID.randomUUID().toString();

    store.createVolume(volumeName);
    OzoneVolume volume = store.getVolume(volumeName);
    volume.createBucket(bucketName);
    OzoneBucket bucket = volume.getBucket(bucketName);

    OzoneTestUtils.expectOmException(NO_SUCH_MULTIPART_UPLOAD_ERROR,
        () -> bucket.abortMultipartUpload(keyName, "random"));
  }

  @Test
  void testAbortUploadFailWithInProgressPartUpload() throws Exception {
    String volumeName = UUID.randomUUID().toString();
    String bucketName = UUID.randomUUID().toString();
    String keyName = UUID.randomUUID().toString();

    store.createVolume(volumeName);
    OzoneVolume volume = store.getVolume(volumeName);
    volume.createBucket(bucketName);
    OzoneBucket bucket = volume.getBucket(bucketName);

    OmMultipartInfo omMultipartInfo = bucket.initiateMultipartUpload(keyName,
        anyReplication());

    assertNotNull(omMultipartInfo.getUploadID());

    // Do not close output stream.
    byte[] data = "data".getBytes(UTF_8);
    OzoneOutputStream ozoneOutputStream = bucket.createMultipartKey(keyName,
        data.length, 1, omMultipartInfo.getUploadID());
    ozoneOutputStream.write(data, 0, data.length);

    // Abort before completing part upload.
    bucket.abortMultipartUpload(keyName, omMultipartInfo.getUploadID());

    try {
      ozoneOutputStream.close();
      fail("testAbortUploadFailWithInProgressPartUpload failed");
    } catch (IOException ex) {
      assertTrue(ex instanceof OMException);
      assertEquals(NO_SUCH_MULTIPART_UPLOAD_ERROR,
          ((OMException) ex).getResult());
    }
  }

  @Test
  void testCommitPartAfterCompleteUpload() throws Exception {
    String volumeName = UUID.randomUUID().toString();
    String bucketName = UUID.randomUUID().toString();
    String keyName = UUID.randomUUID().toString();

    store.createVolume(volumeName);
    OzoneVolume volume = store.getVolume(volumeName);
    volume.createBucket(bucketName);
    OzoneBucket bucket = volume.getBucket(bucketName);

    OmMultipartInfo omMultipartInfo = bucket.initiateMultipartUpload(keyName,
        anyReplication());

    assertNotNull(omMultipartInfo.getUploadID());

    String uploadID = omMultipartInfo.getUploadID();

    // upload part 1.
    byte[] data = generateData(5 * 1024 * 1024,
        (byte) RandomUtils.nextLong());
    OzoneOutputStream ozoneOutputStream = bucket.createMultipartKey(keyName,
        data.length, 1, uploadID);
    ozoneOutputStream.write(data, 0, data.length);
    ozoneOutputStream.close();

    OmMultipartCommitUploadPartInfo omMultipartCommitUploadPartInfo =
        ozoneOutputStream.getCommitUploadPartInfo();

    // Do not close output stream for part 2.
    ozoneOutputStream = bucket.createMultipartKey(keyName,
        data.length, 2, omMultipartInfo.getUploadID());
    ozoneOutputStream.write(data, 0, data.length);

    Map<Integer, String> partsMap = new LinkedHashMap<>();
    partsMap.put(1, omMultipartCommitUploadPartInfo.getPartName());
    OmMultipartUploadCompleteInfo omMultipartUploadCompleteInfo =
        bucket.completeMultipartUpload(keyName,
        uploadID, partsMap);

    assertNotNull(omMultipartCommitUploadPartInfo);

    byte[] fileContent = new byte[data.length];
    try (OzoneInputStream inputStream = bucket.readKey(keyName)) {
      inputStream.read(fileContent);
    }
    StringBuilder sb = new StringBuilder(data.length);

    // Combine all parts data, and check is it matching with get key data.
    String part1 = new String(data, UTF_8);
    sb.append(part1);
    assertEquals(sb.toString(), new String(fileContent, UTF_8));

    try {
      ozoneOutputStream.close();
      fail("testCommitPartAfterCompleteUpload failed");
    } catch (IOException ex) {
      assertTrue(ex instanceof OMException);
      assertEquals(NO_SUCH_MULTIPART_UPLOAD_ERROR,
          ((OMException) ex).getResult());
    }
  }


  @Test
  public void testAbortUploadSuccessWithOutAnyParts() throws Exception {
    String volumeName = UUID.randomUUID().toString();
    String bucketName = UUID.randomUUID().toString();
    String keyName = UUID.randomUUID().toString();

    store.createVolume(volumeName);
    OzoneVolume volume = store.getVolume(volumeName);
    volume.createBucket(bucketName);
    OzoneBucket bucket = volume.getBucket(bucketName);

    String uploadID = initiateMultipartUpload(bucket, keyName,
        anyReplication());
    bucket.abortMultipartUpload(keyName, uploadID);
  }

  @Test
  public void testAbortUploadSuccessWithParts() throws Exception {
    String volumeName = UUID.randomUUID().toString();
    String bucketName = UUID.randomUUID().toString();
    String keyName = UUID.randomUUID().toString();

    store.createVolume(volumeName);
    OzoneVolume volume = store.getVolume(volumeName);
    volume.createBucket(bucketName);
    OzoneBucket bucket = volume.getBucket(bucketName);

    String uploadID = initiateMultipartUpload(bucket, keyName,
        anyReplication());
    uploadPart(bucket, keyName, uploadID, 1, "data".getBytes(UTF_8));
    bucket.abortMultipartUpload(keyName, uploadID);
  }

  @ParameterizedTest
  @MethodSource("replicationConfigs")
  void testListMultipartUploadParts(ReplicationConfig replication)
      throws Exception {
    String volumeName = UUID.randomUUID().toString();
    String bucketName = UUID.randomUUID().toString();
    String keyName = UUID.randomUUID().toString();

    store.createVolume(volumeName);
    OzoneVolume volume = store.getVolume(volumeName);
    volume.createBucket(bucketName);
    OzoneBucket bucket = volume.getBucket(bucketName);

    Map<Integer, String> partsMap = new TreeMap<>();
    String uploadID = initiateMultipartUpload(bucket, keyName, replication);
    String partName1 = uploadPart(bucket, keyName, uploadID, 1,
        generateData(OzoneConsts.OM_MULTIPART_MIN_SIZE, (byte)97));
    partsMap.put(1, partName1);

    String partName2 = uploadPart(bucket, keyName, uploadID, 2,
        generateData(OzoneConsts.OM_MULTIPART_MIN_SIZE, (byte)97));
    partsMap.put(2, partName2);

    String partName3 = uploadPart(bucket, keyName, uploadID, 3,
        generateData(OzoneConsts.OM_MULTIPART_MIN_SIZE, (byte)97));
    partsMap.put(3, partName3);

    OzoneMultipartUploadPartListParts ozoneMultipartUploadPartListParts =
        bucket.listParts(keyName, uploadID, 0, 3);

    assertEquals(
        replication,
        ozoneMultipartUploadPartListParts.getReplicationConfig());

    assertEquals(partsMap.get(ozoneMultipartUploadPartListParts
            .getPartInfoList().get(0).getPartNumber()),
        ozoneMultipartUploadPartListParts.getPartInfoList().get(0)
            .getPartName());
    assertEquals(partsMap.get(ozoneMultipartUploadPartListParts
            .getPartInfoList().get(1).getPartNumber()),
        ozoneMultipartUploadPartListParts.getPartInfoList().get(1)
            .getPartName());
    assertEquals(partsMap.get(ozoneMultipartUploadPartListParts
            .getPartInfoList().get(2).getPartNumber()),
        ozoneMultipartUploadPartListParts.getPartInfoList().get(2)
            .getPartName());

    assertFalse(ozoneMultipartUploadPartListParts.isTruncated());
  }

  @ParameterizedTest
  @MethodSource("replicationConfigs")
  void testListMultipartUploadPartsWithContinuation(
      ReplicationConfig replication) throws Exception {

    String volumeName = UUID.randomUUID().toString();
    String bucketName = UUID.randomUUID().toString();
    String keyName = UUID.randomUUID().toString();

    store.createVolume(volumeName);
    OzoneVolume volume = store.getVolume(volumeName);
    volume.createBucket(bucketName);
    OzoneBucket bucket = volume.getBucket(bucketName);

    Map<Integer, String> partsMap = new TreeMap<>();
    String uploadID = initiateMultipartUpload(bucket, keyName, replication);
    String partName1 = uploadPart(bucket, keyName, uploadID, 1,
        generateData(OzoneConsts.OM_MULTIPART_MIN_SIZE, (byte)97));
    partsMap.put(1, partName1);

    String partName2 = uploadPart(bucket, keyName, uploadID, 2,
        generateData(OzoneConsts.OM_MULTIPART_MIN_SIZE, (byte)97));
    partsMap.put(2, partName2);

    String partName3 = uploadPart(bucket, keyName, uploadID, 3,
        generateData(OzoneConsts.OM_MULTIPART_MIN_SIZE, (byte)97));
    partsMap.put(3, partName3);

    OzoneMultipartUploadPartListParts ozoneMultipartUploadPartListParts =
        bucket.listParts(keyName, uploadID, 0, 2);

    assertEquals(replication,
        ozoneMultipartUploadPartListParts.getReplicationConfig());

    assertEquals(2,
        ozoneMultipartUploadPartListParts.getPartInfoList().size());

    assertEquals(partsMap.get(ozoneMultipartUploadPartListParts
            .getPartInfoList().get(0).getPartNumber()),
        ozoneMultipartUploadPartListParts.getPartInfoList().get(0)
            .getPartName());
    assertEquals(partsMap.get(ozoneMultipartUploadPartListParts
            .getPartInfoList().get(1).getPartNumber()),
        ozoneMultipartUploadPartListParts.getPartInfoList().get(1)
            .getPartName());

    // Get remaining
    assertTrue(ozoneMultipartUploadPartListParts.isTruncated());
    ozoneMultipartUploadPartListParts = bucket.listParts(keyName, uploadID,
        ozoneMultipartUploadPartListParts.getNextPartNumberMarker(), 2);

    assertEquals(1,
        ozoneMultipartUploadPartListParts.getPartInfoList().size());
    assertEquals(partsMap.get(ozoneMultipartUploadPartListParts
            .getPartInfoList().get(0).getPartNumber()),
        ozoneMultipartUploadPartListParts.getPartInfoList().get(0)
            .getPartName());


    // As we don't have any parts for this, we should get false here
    assertFalse(ozoneMultipartUploadPartListParts.isTruncated());

  }

  @Test
  public void testListPartsInvalidPartMarker() throws Exception {
    try {
      String volumeName = UUID.randomUUID().toString();
      String bucketName = UUID.randomUUID().toString();
      String keyName = UUID.randomUUID().toString();

      store.createVolume(volumeName);
      OzoneVolume volume = store.getVolume(volumeName);
      volume.createBucket(bucketName);
      OzoneBucket bucket = volume.getBucket(bucketName);


      OzoneMultipartUploadPartListParts ozoneMultipartUploadPartListParts =
          bucket.listParts(keyName, "random", -1, 2);
    } catch (IllegalArgumentException ex) {
      GenericTestUtils.assertExceptionContains("Should be greater than or " +
          "equal to zero", ex);
    }
  }

  @Test
  public void testListPartsInvalidMaxParts() throws Exception {
    try {
      String volumeName = UUID.randomUUID().toString();
      String bucketName = UUID.randomUUID().toString();
      String keyName = UUID.randomUUID().toString();

      store.createVolume(volumeName);
      OzoneVolume volume = store.getVolume(volumeName);
      volume.createBucket(bucketName);
      OzoneBucket bucket = volume.getBucket(bucketName);


      OzoneMultipartUploadPartListParts ozoneMultipartUploadPartListParts =
          bucket.listParts(keyName, "random", 1,  -1);
    } catch (IllegalArgumentException ex) {
      GenericTestUtils.assertExceptionContains("Max Parts Should be greater " +
          "than zero", ex);
    }
  }

  @Test
  public void testListPartsWithPartMarkerGreaterThanPartCount()
      throws Exception {
    String volumeName = UUID.randomUUID().toString();
    String bucketName = UUID.randomUUID().toString();
    String keyName = UUID.randomUUID().toString();

    store.createVolume(volumeName);
    OzoneVolume volume = store.getVolume(volumeName);
    volume.createBucket(bucketName);
    OzoneBucket bucket = volume.getBucket(bucketName);


    String uploadID = initiateMultipartUpload(bucket, keyName,
        anyReplication());
    uploadPart(bucket, keyName, uploadID, 1,
        generateData(OzoneConsts.OM_MULTIPART_MIN_SIZE, (byte)97));


    OzoneMultipartUploadPartListParts ozoneMultipartUploadPartListParts =
        bucket.listParts(keyName, uploadID, 100, 2);

    // Should return empty

    assertEquals(0,
        ozoneMultipartUploadPartListParts.getPartInfoList().size());

    // As we don't have any parts with greater than partNumberMarker and list
    // is not truncated, so it should return false here.
    assertFalse(ozoneMultipartUploadPartListParts.isTruncated());

  }

  @Test
  public void testListPartsWithInvalidUploadID() throws Exception {
    OzoneTestUtils
        .expectOmException(NO_SUCH_MULTIPART_UPLOAD_ERROR, () -> {
          String volumeName = UUID.randomUUID().toString();
          String bucketName = UUID.randomUUID().toString();
          String keyName = UUID.randomUUID().toString();

          store.createVolume(volumeName);
          OzoneVolume volume = store.getVolume(volumeName);
          volume.createBucket(bucketName);
          OzoneBucket bucket = volume.getBucket(bucketName);
          OzoneMultipartUploadPartListParts ozoneMultipartUploadPartListParts =
              bucket.listParts(keyName, "random", 100, 2);
        });
  }

  @Test
  public void testNativeAclsForVolume() throws Exception {
    String volumeName = UUID.randomUUID().toString();
    store.createVolume(volumeName);

    OzoneObj ozObj = new OzoneObjInfo.Builder()
        .setVolumeName(volumeName)
        .setResType(OzoneObj.ResourceType.VOLUME)
        .setStoreType(OzoneObj.StoreType.OZONE)
        .build();

    validateOzoneAccessAcl(ozObj);
  }

  @Test
  public void testNativeAclsForBucket() throws Exception {
    String volumeName = UUID.randomUUID().toString();
    String bucketName = UUID.randomUUID().toString();

    store.createVolume(volumeName);
    OzoneVolume volume = store.getVolume(volumeName);
    volume.createBucket(bucketName);
    OzoneBucket bucket = volume.getBucket(bucketName);
    assertNotNull(bucket, "Bucket creation failed");

    OzoneObj ozObj = new OzoneObjInfo.Builder()
        .setVolumeName(volumeName)
        .setBucketName(bucketName)
        .setResType(OzoneObj.ResourceType.BUCKET)
        .setStoreType(OzoneObj.StoreType.OZONE)
        .build();

    validateOzoneAccessAcl(ozObj);

    OzoneObj volObj = new OzoneObjInfo.Builder()
        .setVolumeName(volumeName)
        .setResType(OzoneObj.ResourceType.VOLUME)
        .setStoreType(OzoneObj.StoreType.OZONE)
        .build();
    validateDefaultAcls(volObj, ozObj, volume, null);
  }

  private void validateDefaultAcls(OzoneObj parentObj, OzoneObj childObj,
      OzoneVolume volume,  OzoneBucket bucket) throws Exception {
    assertTrue(store.addAcl(parentObj, defaultUserAcl));
    assertTrue(store.addAcl(parentObj, defaultGroupAcl));
    if (volume != null) {
      volume.deleteBucket(childObj.getBucketName());
      volume.createBucket(childObj.getBucketName());
    } else {
      if (childObj.getResourceType().equals(OzoneObj.ResourceType.KEY)) {
        bucket.deleteKey(childObj.getKeyName());
        writeKey(childObj.getKeyName(), bucket);
      } else {
        store.setAcl(childObj, getAclList(new OzoneConfiguration()));
      }
    }
    List<OzoneAcl> acls = store.getAcl(parentObj);
    assertTrue(acls.contains(defaultUserAcl),
        "Current acls: " + StringUtils.join(",", acls) +
            " inheritedUserAcl: " + inheritedUserAcl);
    assertTrue(acls.contains(defaultGroupAcl),
        "Current acls: " + StringUtils.join(",", acls) +
            " inheritedGroupAcl: " + inheritedGroupAcl);

    acls = store.getAcl(childObj);
    assertTrue(acls.contains(inheritedUserAcl),
        "Current acls:" + StringUtils.join(",", acls) +
            " inheritedUserAcl:" + inheritedUserAcl);
    assertTrue(acls.contains(inheritedGroupAcl),
        "Current acls:" + StringUtils.join(",", acls) +
            " inheritedGroupAcl:" + inheritedGroupAcl);
  }

  @Test
  public void testNativeAclsForKey() throws Exception {
    String volumeName = UUID.randomUUID().toString();
    String bucketName = UUID.randomUUID().toString();
    String key1 = "dir1/dir2" + UUID.randomUUID();
    String key2 = "dir1/dir2" + UUID.randomUUID();

    store.createVolume(volumeName);
    OzoneVolume volume = store.getVolume(volumeName);
    volume.createBucket(bucketName);
    OzoneBucket bucket = volume.getBucket(bucketName);
    assertNotNull(bucket, "Bucket creation failed");

    writeKey(key1, bucket);
    writeKey(key2, bucket);

    OzoneObj ozObj = new OzoneObjInfo.Builder()
        .setVolumeName(volumeName)
        .setBucketName(bucketName)
        .setKeyName(key1)
        .setResType(OzoneObj.ResourceType.KEY)
        .setStoreType(OzoneObj.StoreType.OZONE)
        .build();

    // Validates access acls.
    validateOzoneAccessAcl(ozObj);

    // Check default acls inherited from bucket.
    OzoneObj buckObj = new OzoneObjInfo.Builder()
        .setVolumeName(volumeName)
        .setBucketName(bucketName)
        .setKeyName(key1)
        .setResType(OzoneObj.ResourceType.BUCKET)
        .setStoreType(OzoneObj.StoreType.OZONE)
        .build();

    validateDefaultAcls(buckObj, ozObj, null, bucket);

    // Check default acls inherited from prefix.
    OzoneObj prefixObj = new OzoneObjInfo.Builder()
        .setVolumeName(volumeName)
        .setBucketName(bucketName)
        .setKeyName(key1)
        .setPrefixName("dir1/")
        .setResType(OzoneObj.ResourceType.PREFIX)
        .setStoreType(OzoneObj.StoreType.OZONE)
        .build();
    store.setAcl(prefixObj, getAclList(new OzoneConfiguration()));
    // Prefix should inherit DEFAULT acl from bucket.

    List<OzoneAcl> acls = store.getAcl(prefixObj);
    assertTrue(acls.contains(inheritedUserAcl),
        "Current acls:" + StringUtils.join(",", acls));
    assertTrue(acls.contains(inheritedGroupAcl),
        "Current acls:" + StringUtils.join(",", acls));
    // Remove inherited acls from prefix.
    assertTrue(store.removeAcl(prefixObj, inheritedUserAcl));
    assertTrue(store.removeAcl(prefixObj, inheritedGroupAcl));

    validateDefaultAcls(prefixObj, ozObj, null, bucket);
  }

  @Test
  public void testNativeAclsForPrefix() throws Exception {
    String volumeName = UUID.randomUUID().toString();
    String bucketName = UUID.randomUUID().toString();

    String prefix1 = "PF" + UUID.randomUUID().toString() + "/";
    String key1 = prefix1 + "KEY" + UUID.randomUUID().toString();

    String prefix2 = "PF" + UUID.randomUUID().toString() + "/";
    String key2 = prefix2 + "KEY" + UUID.randomUUID().toString();

    store.createVolume(volumeName);
    OzoneVolume volume = store.getVolume(volumeName);
    volume.createBucket(bucketName);
    OzoneBucket bucket = volume.getBucket(bucketName);
    assertNotNull(bucket, "Bucket creation failed");

    writeKey(key1, bucket);
    writeKey(key2, bucket);

    OzoneObj prefixObj = new OzoneObjInfo.Builder()
        .setVolumeName(volumeName)
        .setBucketName(bucketName)
        .setPrefixName(prefix1)
        .setResType(OzoneObj.ResourceType.PREFIX)
        .setStoreType(OzoneObj.StoreType.OZONE)
        .build();

    OzoneObj prefixObj2 = new OzoneObjInfo.Builder()
        .setVolumeName(volumeName)
        .setBucketName(bucketName)
        .setPrefixName(prefix2)
        .setResType(OzoneObj.ResourceType.PREFIX)
        .setStoreType(OzoneObj.StoreType.OZONE)
        .build();

    // add acl
    BitSet aclRights1 = new BitSet();
    aclRights1.set(READ.ordinal());
    OzoneAcl user1Acl = new OzoneAcl(USER,
        "user1", aclRights1, ACCESS);
    assertTrue(store.addAcl(prefixObj, user1Acl));

    // get acl
    List<OzoneAcl> aclsGet = store.getAcl(prefixObj);
    assertEquals(1, aclsGet.size());
    assertEquals(user1Acl, aclsGet.get(0));

    // remove acl
    assertTrue(store.removeAcl(prefixObj, user1Acl));
    aclsGet = store.getAcl(prefixObj);
    assertEquals(0, aclsGet.size());

    // set acl
    BitSet aclRights2 = new BitSet();
    aclRights2.set(ACLType.ALL.ordinal());
    OzoneAcl group1Acl = new OzoneAcl(GROUP,
        "group1", aclRights2, ACCESS);
    List<OzoneAcl> acls = new ArrayList<>();
    acls.add(user1Acl);
    acls.add(group1Acl);
    assertTrue(store.setAcl(prefixObj, acls));

    // get acl
    aclsGet = store.getAcl(prefixObj);
    assertEquals(2, aclsGet.size());

    OzoneObj keyObj = new OzoneObjInfo.Builder()
        .setVolumeName(volumeName)
        .setBucketName(bucketName)
        .setKeyName(key1)
        .setResType(OzoneObj.ResourceType.KEY)
        .setStoreType(OzoneObj.StoreType.OZONE)
        .build();

    // Check default acls inherited from prefix.
    validateDefaultAcls(prefixObj, keyObj, null, bucket);

    // Check default acls inherited from bucket when prefix does not exist.
    validateDefaultAcls(prefixObj2, keyObj, null, bucket);
  }

  /**
   * Helper function to get default acl list for current user.
   *
   * @return list of default Acls.
   * @throws IOException
   * */
  private List<OzoneAcl> getAclList(OzoneConfiguration conf)
      throws IOException {
    List<OzoneAcl> listOfAcls = new ArrayList<>();
    //User ACL
    UserGroupInformation ugi = UserGroupInformation.getCurrentUser();
    OzoneAclConfig aclConfig = conf.getObject(OzoneAclConfig.class);
    ACLType userRights = aclConfig.getUserDefaultRights();
    ACLType groupRights = aclConfig.getGroupDefaultRights();

    listOfAcls.add(new OzoneAcl(USER,
        ugi.getUserName(), userRights, ACCESS));
    //Group ACLs of the User
    List<String> userGroups = Arrays.asList(ugi.getGroupNames());
    userGroups.stream().forEach((group) -> listOfAcls.add(
        new OzoneAcl(GROUP, group, groupRights, ACCESS)));
    return listOfAcls;
  }

  /**
   * Helper function to validate ozone Acl for given object.
   * @param ozObj
   * */
  private void validateOzoneAccessAcl(OzoneObj ozObj) throws IOException {
    // Get acls for volume.
    List<OzoneAcl> expectedAcls = getAclList(new OzoneConfiguration());

    // Case:1 Add new acl permission to existing acl.
    if (expectedAcls.size() > 0) {
      OzoneAcl oldAcl = expectedAcls.get(0);
      OzoneAcl newAcl = new OzoneAcl(oldAcl.getType(), oldAcl.getName(),
          ACLType.READ_ACL, oldAcl.getAclScope());
      // Verify that operation successful.
      assertTrue(store.addAcl(ozObj, newAcl));

      assertEquals(expectedAcls.size(), store.getAcl(ozObj).size());
      final Optional<OzoneAcl> readAcl = store.getAcl(ozObj).stream()
          .filter(acl -> acl.getName().equals(newAcl.getName())
              && acl.getType().equals(newAcl.getType()))
          .findFirst();
      assertTrue(readAcl.isPresent(), "New acl expected but not found.");
      assertTrue(readAcl.get().getAclList().contains(ACLType.READ_ACL),
          "READ_ACL should exist in current acls:" + readAcl.get());


      // Case:2 Remove newly added acl permission.
      assertTrue(store.removeAcl(ozObj, newAcl));

      assertEquals(expectedAcls.size(), store.getAcl(ozObj).size());
      final Optional<OzoneAcl> nonReadAcl = store.getAcl(ozObj).stream()
          .filter(acl -> acl.getName().equals(newAcl.getName())
              && acl.getType().equals(newAcl.getType()))
          .findFirst();
      assertTrue(nonReadAcl.isPresent(), "New acl expected but not found.");
      assertFalse(nonReadAcl.get().getAclList().contains(ACLType.READ_ACL),
          "READ_ACL should not exist in current acls:" + nonReadAcl.get());
    } else {
      fail("Default acl should not be empty.");
    }

    List<OzoneAcl> keyAcls = store.getAcl(ozObj);
    expectedAcls.forEach(a -> assertTrue(keyAcls.contains(a)));

    // Remove all acl's.
    for (OzoneAcl a : expectedAcls) {
      store.removeAcl(ozObj, a);
    }
    List<OzoneAcl> newAcls = store.getAcl(ozObj);
    assertEquals(0, newAcls.size());

    // Add acl's and then call getAcl.
    int aclCount = 0;
    for (OzoneAcl a : expectedAcls) {
      aclCount++;
      assertTrue(store.addAcl(ozObj, a));
      assertEquals(aclCount, store.getAcl(ozObj).size());
    }
    newAcls = store.getAcl(ozObj);
    assertEquals(expectedAcls.size(), newAcls.size());
    List<OzoneAcl> finalNewAcls = newAcls;
    expectedAcls.forEach(a -> assertTrue(finalNewAcls.contains(a)));

    // Reset acl's.
    OzoneAcl ua = new OzoneAcl(USER, "userx",
        ACLType.READ_ACL, ACCESS);
    OzoneAcl ug = new OzoneAcl(GROUP, "userx",
        ACLType.ALL, ACCESS);
    store.setAcl(ozObj, Arrays.asList(ua, ug));
    newAcls = store.getAcl(ozObj);
    assertEquals(2, newAcls.size());
    assertTrue(newAcls.contains(ua));
    assertTrue(newAcls.contains(ug));
  }

  private void writeKey(String key1, OzoneBucket bucket) throws IOException {
    OzoneOutputStream out = bucket.createKey(key1, 1024, RATIS,
        ONE, new HashMap<>());
    out.write(RandomStringUtils.random(1024).getBytes(UTF_8));
    out.close();
  }

  private byte[] generateData(int size, byte val) {
    byte[] chars = new byte[size];
    Arrays.fill(chars, val);
    return chars;
  }

  private void doMultipartUpload(OzoneBucket bucket, String keyName, byte val,
      ReplicationConfig replication)
      throws Exception {
    // Initiate Multipart upload request
    String uploadID = initiateMultipartUpload(bucket, keyName, replication);

    // Upload parts
    Map<Integer, String> partsMap = new TreeMap<>();

    // get 5mb data, as each part should be of min 5mb, last part can be less
    // than 5mb
    int length = 0;
    byte[] data = generateData(OzoneConsts.OM_MULTIPART_MIN_SIZE, val);
    String partName = uploadPart(bucket, keyName, uploadID, 1, data);
    partsMap.put(1, partName);
    length += data.length;


    partName = uploadPart(bucket, keyName, uploadID, 2, data);
    partsMap.put(2, partName);
    length += data.length;

    String part3 = UUID.randomUUID().toString();
    partName = uploadPart(bucket, keyName, uploadID, 3, part3.getBytes(
        UTF_8));
    partsMap.put(3, partName);
    length += part3.getBytes(UTF_8).length;

    // Complete multipart upload request
    completeMultipartUpload(bucket, keyName, uploadID, partsMap);

    //Now Read the key which has been completed multipart upload.
    byte[] fileContent = new byte[data.length + data.length + part3.getBytes(
        UTF_8).length];
    try (OzoneInputStream inputStream = bucket.readKey(keyName)) {
      inputStream.read(fileContent);
    }

    verifyReplication(bucket.getVolumeName(), bucket.getName(), keyName,
        replication);

    StringBuilder sb = new StringBuilder(length);

    // Combine all parts data, and check is it matching with get key data.
    String part1 = new String(data, UTF_8);
    String part2 = new String(data, UTF_8);
    sb.append(part1);
    sb.append(part2);
    sb.append(part3);
    assertEquals(sb.toString(), new String(fileContent, UTF_8));

    OmKeyArgs keyArgs = new OmKeyArgs.Builder()
        .setVolumeName(bucket.getVolumeName())
        .setBucketName(bucket.getName())
        .setKeyName(keyName)
        .build();
    ResolvedBucket resolvedBucket = new ResolvedBucket(
        bucket.getVolumeName(), bucket.getName(),
        bucket.getVolumeName(), bucket.getName(),
        "", bucket.getBucketLayout());
    OmKeyInfo omKeyInfo = ozoneManager.getKeyManager().getKeyInfo(keyArgs,
        resolvedBucket, UUID.randomUUID().toString());

    OmKeyLocationInfoGroup latestVersionLocations =
        omKeyInfo.getLatestVersionLocations();
    assertNotNull(latestVersionLocations);
    assertTrue(latestVersionLocations.isMultipartKey());
    latestVersionLocations.getBlocksLatestVersionOnly()
        .forEach(omKeyLocationInfo ->
            assertTrue(omKeyLocationInfo.getPartNumber() != -1));
  }

  private String initiateMultipartUpload(OzoneBucket bucket, String keyName,
      ReplicationConfig replicationConfig) throws Exception {
    OmMultipartInfo multipartInfo = bucket.initiateMultipartUpload(keyName,
        replicationConfig);

    String uploadID = multipartInfo.getUploadID();
    assertNotNull(uploadID);
    return uploadID;
  }

  private String uploadPart(OzoneBucket bucket, String keyName, String
      uploadID, int partNumber, byte[] data) throws Exception {
    OzoneOutputStream ozoneOutputStream = bucket.createMultipartKey(keyName,
        data.length, partNumber, uploadID);
    ozoneOutputStream.write(data, 0,
        data.length);
    ozoneOutputStream.close();

    OmMultipartCommitUploadPartInfo omMultipartCommitUploadPartInfo =
        ozoneOutputStream.getCommitUploadPartInfo();

    assertNotNull(omMultipartCommitUploadPartInfo);
    assertNotNull(omMultipartCommitUploadPartInfo.getPartName());
    return omMultipartCommitUploadPartInfo.getPartName();

  }

  private void completeMultipartUpload(OzoneBucket bucket, String keyName,
      String uploadID, Map<Integer, String> partsMap) throws Exception {
    OmMultipartUploadCompleteInfo omMultipartUploadCompleteInfo = bucket
        .completeMultipartUpload(keyName, uploadID, partsMap);

    assertNotNull(omMultipartUploadCompleteInfo);
    assertEquals(omMultipartUploadCompleteInfo.getBucket(), bucket
        .getName());
    assertEquals(omMultipartUploadCompleteInfo.getVolume(), bucket
        .getVolumeName());
    assertEquals(omMultipartUploadCompleteInfo.getKey(), keyName);
    assertNotNull(omMultipartUploadCompleteInfo.getHash());
  }

  private void createTestKey(OzoneBucket bucket, String keyName,
                             String keyValue) throws IOException {
    OzoneOutputStream out = bucket.createKey(keyName,
        keyValue.getBytes(UTF_8).length, RATIS,
        ONE, new HashMap<>());
    out.write(keyValue.getBytes(UTF_8));
    out.close();
    OzoneKey key = bucket.getKey(keyName);
    assertEquals(keyName, key.getName());
  }

  private void assertKeyRenamedEx(OzoneBucket bucket, String keyName)
      throws Exception {
    OMException oe = null;
    try {
      bucket.getKey(keyName);
    } catch (OMException e) {
      oe = e;
    }
    assertEquals(KEY_NOT_FOUND, oe.getResult());
  }

  /**
   * Tests GDPR encryption/decryption.
   * 1. Create GDPR Enabled bucket.
   * 2. Create a Key in this bucket so it gets encrypted via GDPRSymmetricKey.
   * 3. Read key and validate the content/metadata is as expected because the
   * readKey will decrypt using the GDPR Symmetric Key with details from KeyInfo
   * Metadata.
   * 4. To check encryption, we forcibly update KeyInfo Metadata and remove the
   * gdprEnabled flag
   * 5. When we now read the key, {@link RpcClient} checks for GDPR Flag in
   * method createInputStream. If the gdprEnabled flag in metadata is set to
   * true, it decrypts using the GDPRSymmetricKey. Since we removed that flag
   * from metadata for this key, if will read the encrypted data as-is.
   * 6. Thus, when we compare this content with expected text, it should
   * not match as the decryption has not been performed.
   * @throws Exception
   */
  @Test
  public void testKeyReadWriteForGDPR() throws Exception {
    //Step 1
    String volumeName = UUID.randomUUID().toString();
    String bucketName = UUID.randomUUID().toString();
    String keyName = UUID.randomUUID().toString();

    store.createVolume(volumeName);
    OzoneVolume volume = store.getVolume(volumeName);
    // This test uses object store layout to make manual key modifications
    // easier.
    BucketArgs args = BucketArgs.newBuilder()
        .setBucketLayout(BucketLayout.OBJECT_STORE)
        .addMetadata(OzoneConsts.GDPR_FLAG, "true").build();
    volume.createBucket(bucketName, args);
    OzoneBucket bucket = volume.getBucket(bucketName);
    assertEquals(bucketName, bucket.getName());
    assertNotNull(bucket.getMetadata());
    assertEquals("true",
        bucket.getMetadata().get(OzoneConsts.GDPR_FLAG));

    //Step 2
    String text = "hello world";
    Map<String, String> keyMetadata = new HashMap<>();
    keyMetadata.put(OzoneConsts.GDPR_FLAG, "true");
    OzoneOutputStream out = bucket.createKey(keyName,
        text.getBytes(UTF_8).length, RATIS, ONE, keyMetadata);
    out.write(text.getBytes(UTF_8));
    out.close();
    assertNull(keyMetadata.get(OzoneConsts.GDPR_SECRET));

    //Step 3
    OzoneKeyDetails key = bucket.getKey(keyName);

    assertEquals(keyName, key.getName());
    assertEquals("true", key.getMetadata().get(OzoneConsts.GDPR_FLAG));
    assertEquals("AES",
        key.getMetadata().get(OzoneConsts.GDPR_ALGORITHM));
    assertNotNull(key.getMetadata().get(OzoneConsts.GDPR_SECRET));

    try (OzoneInputStream is = bucket.readKey(keyName)) {
      assertInputStreamContent(text, is);
      verifyReplication(volumeName, bucketName, keyName,
          RatisReplicationConfig.getInstance(HddsProtos.ReplicationFactor.ONE));
    }

    //Step 4
    OMMetadataManager omMetadataManager = ozoneManager.getMetadataManager();
    OmKeyInfo omKeyInfo = omMetadataManager.getKeyTable(getBucketLayout())
        .get(omMetadataManager.getOzoneKey(volumeName, bucketName, keyName));

    omKeyInfo.getMetadata().remove(OzoneConsts.GDPR_FLAG);

    omMetadataManager.getKeyTable(getBucketLayout())
        .put(omMetadataManager.getOzoneKey(volumeName, bucketName, keyName),
            omKeyInfo);

    //Step 5
    key = bucket.getKey(keyName);
    assertEquals(keyName, key.getName());
    assertNull(key.getMetadata().get(OzoneConsts.GDPR_FLAG));

    try (OzoneInputStream is = bucket.readKey(keyName)) {
      byte[] fileContent = new byte[text.getBytes(UTF_8).length];
      is.read(fileContent);

      //Step 6
      assertNotEquals(text, new String(fileContent, UTF_8));
    }
  }

  /**
   * Tests deletedKey for GDPR.
   * 1. Create GDPR Enabled bucket.
   * 2. Create a Key in this bucket so it gets encrypted via GDPRSymmetricKey.
   * 3. Read key and validate the content/metadata is as expected because the
   * readKey will decrypt using the GDPR Symmetric Key with details from KeyInfo
   * Metadata.
   * 4. Delete this key in GDPR enabled bucket
   * 5. Confirm the deleted key metadata in deletedTable does not contain the
   * GDPR encryption details (flag, secret, algorithm).
   * @throws Exception
   */
  @Test
  public void testDeletedKeyForGDPR() throws Exception {
    //Step 1
    String volumeName = UUID.randomUUID().toString();
    String bucketName = UUID.randomUUID().toString();
    String keyName = UUID.randomUUID().toString();

    store.createVolume(volumeName);
    OzoneVolume volume = store.getVolume(volumeName);
    BucketArgs args = BucketArgs.newBuilder()
        .addMetadata(OzoneConsts.GDPR_FLAG, "true").build();
    volume.createBucket(bucketName, args);
    OzoneBucket bucket = volume.getBucket(bucketName);
    assertEquals(bucketName, bucket.getName());
    assertNotNull(bucket.getMetadata());
    assertEquals("true",
        bucket.getMetadata().get(OzoneConsts.GDPR_FLAG));

    //Step 2
    String text = "hello world";
    Map<String, String> keyMetadata = new HashMap<>();
    keyMetadata.put(OzoneConsts.GDPR_FLAG, "true");
    OzoneOutputStream out = bucket.createKey(keyName,
        text.getBytes(UTF_8).length, RATIS, ONE, keyMetadata);
    out.write(text.getBytes(UTF_8));
    out.close();

    //Step 3
    OzoneKeyDetails key = bucket.getKey(keyName);

    assertEquals(keyName, key.getName());
    assertEquals("true", key.getMetadata().get(OzoneConsts.GDPR_FLAG));
    assertEquals("AES",
        key.getMetadata().get(OzoneConsts.GDPR_ALGORITHM));
    assertTrue(key.getMetadata().get(OzoneConsts.GDPR_SECRET) != null);

    try (OzoneInputStream is = bucket.readKey(keyName)) {
      assertInputStreamContent(text, is);
    }
    verifyReplication(volumeName, bucketName, keyName,
        RatisReplicationConfig.getInstance(HddsProtos.ReplicationFactor.ONE));

    //Step 4
    bucket.deleteKey(keyName);

    //Step 5
    OMMetadataManager omMetadataManager = ozoneManager.getMetadataManager();
    String objectKey = omMetadataManager.getOzoneKey(volumeName, bucketName,
        keyName);
    RepeatedOmKeyInfo deletedKeys =
        omMetadataManager.getDeletedTable().get(objectKey);
    if (deletedKeys != null) {
      Map<String, String> deletedKeyMetadata =
          deletedKeys.getOmKeyInfoList().get(0).getMetadata();
      assertFalse(deletedKeyMetadata.containsKey(OzoneConsts.GDPR_FLAG));
      assertFalse(
          deletedKeyMetadata.containsKey(OzoneConsts.GDPR_SECRET));
      assertFalse(
          deletedKeyMetadata.containsKey(OzoneConsts.GDPR_ALGORITHM));
    }
  }

  @Test
  public void testSetS3VolumeAcl() throws Exception {
    OzoneObj s3vVolume = new OzoneObjInfo.Builder()
        .setVolumeName(
            HddsClientUtils.getDefaultS3VolumeName(cluster.getConf()))
        .setResType(OzoneObj.ResourceType.VOLUME)
        .setStoreType(OzoneObj.StoreType.OZONE)
        .build();

    OzoneAcl ozoneAcl = new OzoneAcl(USER, remoteUserName, WRITE, DEFAULT);

    boolean result = store.addAcl(s3vVolume, ozoneAcl);

    assertTrue(result, "SetAcl on default s3v failed");

    List<OzoneAcl> ozoneAclList = store.getAcl(s3vVolume);

    assertTrue(ozoneAclList.contains(ozoneAcl));
  }

  @Test
  public void testHeadObject() throws IOException {
    String volumeName = UUID.randomUUID().toString();
    String bucketName = UUID.randomUUID().toString();
    ReplicationConfig replicationConfig = ReplicationConfig
        .fromProtoTypeAndFactor(HddsProtos.ReplicationType.RATIS,
            HddsProtos.ReplicationFactor.THREE);

    String value = "sample value";
    store.createVolume(volumeName);
    OzoneVolume volume = store.getVolume(volumeName);
    volume.createBucket(bucketName);
    OzoneBucket bucket = volume.getBucket(bucketName);


    String keyName = UUID.randomUUID().toString();

    OzoneOutputStream out = bucket.createKey(keyName,
        value.getBytes(UTF_8).length, replicationConfig, new HashMap<>());
    out.write(value.getBytes(UTF_8));
    out.close();

    OzoneKey key = bucket.headObject(keyName);
    assertEquals(volumeName, key.getVolumeName());
    assertEquals(bucketName, key.getBucketName());
    assertEquals(keyName, key.getName());
    assertEquals(replicationConfig.getReplicationType(),
        key.getReplicationConfig().getReplicationType());
    assertEquals(replicationConfig.getRequiredNodes(),
        key.getReplicationConfig().getRequiredNodes());
    assertEquals(value.getBytes(UTF_8).length, key.getDataSize());

    try {
      bucket.headObject(UUID.randomUUID().toString());
    } catch (OMException ex) {
      assertEquals(ResultCodes.KEY_NOT_FOUND, ex.getResult());
    }

  }

  private BucketLayout getBucketLayout() {
    return BucketLayout.DEFAULT;
  }

  private void createRequiredForVersioningTest(String volumeName,
      String bucketName, String keyName, boolean versioning) throws Exception {

    ReplicationConfig replicationConfig = ReplicationConfig
        .fromProtoTypeAndFactor(HddsProtos.ReplicationType.RATIS,
            HddsProtos.ReplicationFactor.THREE);

    String value = "sample value";
    store.createVolume(volumeName);
    OzoneVolume volume = store.getVolume(volumeName);

    // This test inspects RocksDB delete table to check for versioning
    // information. This is easier to do with object store keys.
    volume.createBucket(bucketName, BucketArgs.newBuilder()
        .setVersioning(versioning)
        .setBucketLayout(BucketLayout.OBJECT_STORE).build());
    OzoneBucket bucket = volume.getBucket(bucketName);

    OzoneOutputStream out = bucket.createKey(keyName,
        value.getBytes(UTF_8).length, replicationConfig, new HashMap<>());
    out.write(value.getBytes(UTF_8));
    out.close();

    // Override key
    out = bucket.createKey(keyName,
        value.getBytes(UTF_8).length, replicationConfig, new HashMap<>());
    out.write(value.getBytes(UTF_8));
    out.close();
  }

  private void checkExceptedResultForVersioningTest(String volumeName,
      String bucketName, String keyName, int expectedCount) throws Exception {
    OmKeyInfo omKeyInfo = cluster.getOzoneManager().getMetadataManager()
        .getKeyTable(getBucketLayout()).get(
            cluster.getOzoneManager().getMetadataManager()
                .getOzoneKey(volumeName, bucketName, keyName));

    assertNotNull(omKeyInfo);
    assertEquals(expectedCount,
        omKeyInfo.getKeyLocationVersions().size());

    // ensure flush double buffer for deleted Table
    cluster.getOzoneManager().getOmRatisServer().getOmStateMachine()
        .awaitDoubleBufferFlush();

    if (expectedCount == 1) {
      List<? extends Table.KeyValue<String, RepeatedOmKeyInfo>> rangeKVs
          = cluster.getOzoneManager().getMetadataManager().getDeletedTable()
          .getRangeKVs(null, 100,
              cluster.getOzoneManager().getMetadataManager()
              .getOzoneKey(volumeName, bucketName, keyName));

      assertTrue(rangeKVs.size() > 0);
      assertEquals(expectedCount,
          rangeKVs.get(0).getValue().getOmKeyInfoList().size());
    } else {
      // If expectedCount is greater than 1 means versioning enabled,
      // so delete table should be empty.
      RepeatedOmKeyInfo repeatedOmKeyInfo = cluster
          .getOzoneManager().getMetadataManager()
          .getDeletedTable().get(cluster.getOzoneManager().getMetadataManager()
              .getOzoneKey(volumeName, bucketName, keyName));

      assertNull(repeatedOmKeyInfo);
    }
  }

  @Test
  @Unhealthy("HDDS-8752")
  public void testOverWriteKeyWithAndWithOutVersioning() throws Exception {
    String volumeName = UUID.randomUUID().toString();
    String bucketName = UUID.randomUUID().toString();
    String keyName = UUID.randomUUID().toString();

    createRequiredForVersioningTest(volumeName, bucketName, keyName, false);

    checkExceptedResultForVersioningTest(volumeName, bucketName, keyName, 1);


    // Versioning turned on
    volumeName = UUID.randomUUID().toString();
    bucketName = UUID.randomUUID().toString();
    keyName = UUID.randomUUID().toString();

    createRequiredForVersioningTest(volumeName, bucketName, keyName, true);
    checkExceptedResultForVersioningTest(volumeName, bucketName, keyName, 2);
  }

  @Test
  public void testSetECReplicationConfigOnBucket()
      throws IOException {
    String volumeName = UUID.randomUUID().toString();
    store.createVolume(volumeName);
    OzoneVolume volume = store.getVolume(volumeName);
    OzoneBucket bucket = getBucket(volume);
    ReplicationConfig currentReplicationConfig = bucket.getReplicationConfig();
    assertEquals(
        StandaloneReplicationConfig.getInstance(
            HddsProtos.ReplicationFactor.ONE),
        currentReplicationConfig);
    ECReplicationConfig ecReplicationConfig =
        new ECReplicationConfig(3, 2, EcCodec.RS, (int) OzoneConsts.MB);
    bucket.setReplicationConfig(ecReplicationConfig);

    // Get the bucket and check the updated config.
    bucket = volume.getBucket(bucket.getName());

    assertEquals(ecReplicationConfig, bucket.getReplicationConfig());

    RatisReplicationConfig ratisReplicationConfig =
        RatisReplicationConfig.getInstance(HddsProtos.ReplicationFactor.THREE);
    bucket.setReplicationConfig(ratisReplicationConfig);

    // Get the bucket and check the updated config.
    bucket = volume.getBucket(bucket.getName());

    assertEquals(ratisReplicationConfig, bucket.getReplicationConfig());

    //Reset replication config back.
    bucket.setReplicationConfig(currentReplicationConfig);
  }

  private OzoneBucket getBucket(OzoneVolume volume) throws IOException {
    String bucketName = UUID.randomUUID().toString();
    BucketArgs.Builder builder = BucketArgs.newBuilder();
    builder.setVersioning(true).setDefaultReplicationConfig(
        new DefaultReplicationConfig(
            StandaloneReplicationConfig.getInstance(
                HddsProtos.ReplicationFactor.ONE)));
    volume.createBucket(bucketName, builder.build());
    return volume.getBucket(bucketName);
  }

  private static ReplicationConfig anyReplication() {
    return RatisReplicationConfig.getInstance(HddsProtos.ReplicationFactor.ONE);
  }

  private void assertBucketCount(OzoneVolume volume,
                                 String bucketPrefix,
                                 String preBucket,
                                 boolean hasSnapshot,
                                 int expectedBucketCount) {
    Iterator<? extends OzoneBucket> bucketIterator =
        volume.listBuckets(bucketPrefix, preBucket, hasSnapshot);
    int bucketCount = 0;
    while (bucketIterator.hasNext()) {
      assertTrue(
          bucketIterator.next().getName().startsWith(bucketPrefix));
      bucketCount++;
    }
    assertEquals(expectedBucketCount, bucketCount);
  }

  @Test
  public void testListSnapshot() throws IOException {
    String volumeA = "vol-a-" + RandomStringUtils.randomNumeric(5);
    String volumeB = "vol-b-" + RandomStringUtils.randomNumeric(5);
    String bucketA = "buc-a-" + RandomStringUtils.randomNumeric(5);
    String bucketB = "buc-b-" + RandomStringUtils.randomNumeric(5);
    store.createVolume(volumeA);
    store.createVolume(volumeB);
    OzoneVolume volA = store.getVolume(volumeA);
    OzoneVolume volB = store.getVolume(volumeB);
    volA.createBucket(bucketA);
    volA.createBucket(bucketB);
    volB.createBucket(bucketA);
    volB.createBucket(bucketB);
    String snapshotPrefixA = "snapshot-a-";
    String snapshotPrefixB = "snapshot-b-";
    for (int i = 0; i < 10; i++) {
      store.createSnapshot(volumeA, bucketA,
          snapshotPrefixA + i + "-" + RandomStringUtils.randomNumeric(5));
      store.createSnapshot(volumeA, bucketB,
          snapshotPrefixA + i + "-" + RandomStringUtils.randomNumeric(5));
      store.createSnapshot(volumeB, bucketA,
          snapshotPrefixA + i + "-" + RandomStringUtils.randomNumeric(5));
      store.createSnapshot(volumeB, bucketB,
          snapshotPrefixA + i + "-" + RandomStringUtils.randomNumeric(5));
    }
    for (int i = 0; i < 10; i++) {
      store.createSnapshot(volumeA, bucketA,
          snapshotPrefixB + i + "-" + RandomStringUtils.randomNumeric(5));
      store.createSnapshot(volumeA, bucketB,
          snapshotPrefixB + i + "-" + RandomStringUtils.randomNumeric(5));
      store.createSnapshot(volumeB, bucketA,
          snapshotPrefixB + i + "-" + RandomStringUtils.randomNumeric(5));
      store.createSnapshot(volumeB, bucketB,
          snapshotPrefixB + i + "-" + RandomStringUtils.randomNumeric(5));
    }

    Iterator<? extends OzoneSnapshot> snapshotIter =
        store.listSnapshot(volumeA, bucketA, null, null);
    int volABucketASnapshotCount = 0;
    while (snapshotIter.hasNext()) {
      OzoneSnapshot snapshot = snapshotIter.next();
      volABucketASnapshotCount++;
    }
    assertEquals(20, volABucketASnapshotCount);

    snapshotIter = store.listSnapshot(volumeA, bucketB, null, null);
    int volABucketBSnapshotCount = 0;
    while (snapshotIter.hasNext()) {
      OzoneSnapshot snapshot = snapshotIter.next();
      volABucketBSnapshotCount++;
    }
    assertEquals(20, volABucketASnapshotCount);

    snapshotIter = store.listSnapshot(volumeB, bucketA, null, null);
    int volBBucketASnapshotCount = 0;
    while (snapshotIter.hasNext()) {
      OzoneSnapshot snapshot = snapshotIter.next();
      volBBucketASnapshotCount++;
    }
    assertEquals(20, volABucketASnapshotCount);

    snapshotIter = store.listSnapshot(volumeB, bucketB, null, null);
    int volBBucketBSnapshotCount = 0;
    while (snapshotIter.hasNext()) {
      OzoneSnapshot snapshot = snapshotIter.next();
      volBBucketBSnapshotCount++;
    }
    assertEquals(20, volABucketASnapshotCount);

    int volABucketASnapshotACount = 0;
    snapshotIter = store.listSnapshot(volumeA, bucketA, snapshotPrefixA, null);
    while (snapshotIter.hasNext()) {
      OzoneSnapshot snapshot = snapshotIter.next();
      assertTrue(snapshot.getName().startsWith(snapshotPrefixA));
      volABucketASnapshotACount++;
    }
    assertEquals(10, volABucketASnapshotACount);
    assertFalse(snapshotIter.hasNext());

  }
}
