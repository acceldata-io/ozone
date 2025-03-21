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
package org.apache.hadoop.ozone.scm;

import org.apache.commons.lang3.RandomStringUtils;

import org.apache.hadoop.hdds.client.ReplicationFactor;
import org.apache.hadoop.hdds.client.ReplicationType;
import org.apache.hadoop.hdds.client.StandaloneReplicationConfig;
import org.apache.hadoop.hdds.conf.OzoneConfiguration;
import org.apache.hadoop.hdds.protocol.proto.HddsProtos;
import org.apache.hadoop.hdds.scm.container.ContainerID;
import org.apache.hadoop.hdds.scm.container.ContainerInfo;
import org.apache.hadoop.hdds.scm.container.ContainerReplica;
import org.apache.hadoop.hdds.utils.IOUtils;
import org.apache.hadoop.ozone.MiniOzoneCluster;
import org.apache.hadoop.ozone.client.ObjectStore;
import org.apache.hadoop.ozone.client.OzoneClient;
import org.apache.hadoop.ozone.client.OzoneClientFactory;
import org.apache.hadoop.ozone.client.io.OzoneOutputStream;
import org.apache.hadoop.ozone.container.common.impl.ContainerData;
import org.apache.hadoop.ozone.container.common.impl.ContainerSet;
import org.apache.hadoop.ozone.om.helpers.OmKeyArgs;
import org.apache.hadoop.ozone.om.helpers.OmKeyLocationInfo;
import org.apache.hadoop.hdds.scm.server.StorageContainerManager;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.rules.TestRule;
import org.junit.rules.Timeout;
import org.apache.ozone.test.JUnit5AwareTimeout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.HashMap;
import java.util.Set;

import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * This class tests container report with DN container state info.
 */
public class TestContainerReportWithKeys {

  /**
    * Set a timeout for each test.
    */
  @Rule
  public TestRule timeout = new JUnit5AwareTimeout(Timeout.seconds(300));
  private static final Logger LOG = LoggerFactory.getLogger(
      TestContainerReportWithKeys.class);
  private static MiniOzoneCluster cluster = null;
  private static OzoneClient client;
  private static OzoneConfiguration conf;
  private static StorageContainerManager scm;

  @Rule
  public ExpectedException exception = ExpectedException.none();

  /**
   * Create a MiniDFSCluster for testing.
   * <p>
   * Ozone is made active by setting OZONE_ENABLED = true
   *
   * @throws IOException
   */
  @BeforeClass
  public static void init() throws Exception {
    conf = new OzoneConfiguration();
    cluster = MiniOzoneCluster.newBuilder(conf).build();
    cluster.waitForClusterToBeReady();
    client = OzoneClientFactory.getRpcClient(conf);
    scm = cluster.getStorageContainerManager();
  }

  /**
   * Shutdown MiniDFSCluster.
   */
  @AfterClass
  public static void shutdown() {
    IOUtils.closeQuietly(client);
    if (cluster != null) {
      cluster.shutdown();
    }
  }

  @Test
  public void testContainerReportKeyWrite() throws Exception {
    final String volumeName = "volume" + RandomStringUtils.randomNumeric(5);
    final String bucketName = "bucket" + RandomStringUtils.randomNumeric(5);
    final String keyName = "key" + RandomStringUtils.randomNumeric(5);
    final int keySize = 100;

    ObjectStore objectStore = client.getObjectStore();
    objectStore.createVolume(volumeName);
    objectStore.getVolume(volumeName).createBucket(bucketName);
    OzoneOutputStream key =
        objectStore.getVolume(volumeName).getBucket(bucketName)
            .createKey(keyName, keySize, ReplicationType.RATIS,
                ReplicationFactor.ONE, new HashMap<>());
    String dataString = RandomStringUtils.randomAlphabetic(keySize);
    key.write(dataString.getBytes(UTF_8));
    key.close();

    OmKeyArgs keyArgs = new OmKeyArgs.Builder()
        .setVolumeName(volumeName)
        .setBucketName(bucketName)
        .setKeyName(keyName)
        .setReplicationConfig(
            StandaloneReplicationConfig
                .getInstance(HddsProtos.ReplicationFactor.ONE))
        .setDataSize(keySize)
        .build();


    OmKeyLocationInfo keyInfo =
        cluster.getOzoneManager().lookupKey(keyArgs).getKeyLocationVersions()
            .get(0).getBlocksLatestVersionOnly().get(0);


    ContainerInfo cinfo = scm.getContainerInfo(keyInfo.getContainerID());
    Set<ContainerReplica> replicas =
        scm.getContainerManager().getContainerReplicas(
            ContainerID.valueOf(keyInfo.getContainerID()));
    Assert.assertTrue(replicas.size() == 1);
    replicas.stream().forEach(rp ->
        Assert.assertTrue(rp.getDatanodeDetails().getParent() != null));

    LOG.info("SCM Container Info keyCount: {} usedBytes: {}",
        cinfo.getNumberOfKeys(), cinfo.getUsedBytes());
  }


  private static ContainerData getContainerData(long containerID) {
    ContainerData containerData;
    ContainerSet containerManager = cluster.getHddsDatanodes().get(0)
        .getDatanodeStateMachine().getContainer().getContainerSet();
    containerData =
        containerManager.getContainer(containerID).getContainerData();
    return containerData;
  }
}
