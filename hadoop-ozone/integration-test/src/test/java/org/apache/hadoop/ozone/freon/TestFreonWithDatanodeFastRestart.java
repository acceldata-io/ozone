/**
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

package org.apache.hadoop.ozone.freon;

import org.apache.hadoop.hdds.conf.OzoneConfiguration;
import org.apache.hadoop.ozone.MiniOzoneCluster;
import org.apache.hadoop.ozone.container.TestHelper;
import org.apache.ozone.test.UnhealthyTest;
import org.apache.ozone.test.tag.Unhealthy;
import org.apache.ratis.server.protocol.TermIndex;
import org.apache.ratis.statemachine.StateMachine;
import org.apache.ratis.statemachine.impl.SimpleStateMachineStorage;
import org.apache.ratis.statemachine.impl.SingleFileSnapshotInfo;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.junit.rules.TestRule;
import org.junit.rules.Timeout;
import org.apache.ozone.test.JUnit5AwareTimeout;
import picocli.CommandLine;

/**
 * Tests Freon with Datanode restarts without waiting for pipeline to close.
 */
public class TestFreonWithDatanodeFastRestart {

  /**
    * Set a timeout for each test.
    */
  @Rule
  public TestRule timeout = new JUnit5AwareTimeout(Timeout.seconds(300));

  private static MiniOzoneCluster cluster;
  private static OzoneConfiguration conf;

  /**
   * Create a MiniDFSCluster for testing.
   * <p>
   * Ozone is made active by setting OZONE_ENABLED = true
   *
   */
  @BeforeClass
  public static void init() throws Exception {
    conf = new OzoneConfiguration();
    cluster = MiniOzoneCluster.newBuilder(conf)
      .setHbProcessorInterval(1000)
      .setHbInterval(1000)
      .setNumDatanodes(3)
      .build();
    cluster.waitForClusterToBeReady();
  }

  /**
   * Shutdown MiniDFSCluster.
   */
  @AfterClass
  public static void shutdown() {
    if (cluster != null) {
      cluster.shutdown();
    }
  }

  @Test
  @Category(UnhealthyTest.class) @Unhealthy("HDDS-1160")
  public void testRestart() throws Exception {
    startFreon();
    StateMachine sm = getStateMachine();
    TermIndex termIndexBeforeRestart = sm.getLastAppliedTermIndex();
    cluster.restartHddsDatanode(0, false);
    sm = getStateMachine();
    SimpleStateMachineStorage storage =
        (SimpleStateMachineStorage)sm.getStateMachineStorage();
    SingleFileSnapshotInfo snapshotInfo = storage.getLatestSnapshot();
    TermIndex termInSnapshot = snapshotInfo.getTermIndex();
    String expectedSnapFile =
        storage.getSnapshotFile(termIndexBeforeRestart.getTerm(),
            termIndexBeforeRestart.getIndex()).getAbsolutePath();
    Assert.assertEquals(expectedSnapFile,
        snapshotInfo.getFile().getPath().toString());
    Assert.assertEquals(termInSnapshot, termIndexBeforeRestart);

    // After restart the term index might have progressed to apply pending
    // transactions.
    TermIndex termIndexAfterRestart = sm.getLastAppliedTermIndex();
    Assert.assertTrue(termIndexAfterRestart.getIndex() >=
        termIndexBeforeRestart.getIndex());
    // TODO: fix me
    // Give some time for the datanode to register again with SCM.
    // If we try to use the pipeline before the datanode registers with SCM
    // we end up in "NullPointerException: scmId cannot be null" in
    // datanode statemachine and datanode crashes.
    // This has to be fixed. Check HDDS-830.
    // Until then this sleep should help us!
    Thread.sleep(5000);
    startFreon();
  }

  private void startFreon() {
    RandomKeyGenerator randomKeyGenerator =
        new RandomKeyGenerator(cluster.getConf());
    CommandLine cmd = new CommandLine(randomKeyGenerator);
    cmd.execute("--num-of-volumes", "1",
        "--num-of-buckets", "1",
        "--num-of-keys", "1",
        "--key-size", "20MB",
        "--factor", "THREE",
        "--type", "RATIS",
        "--validate-writes"
    );

    Assert.assertEquals(1, randomKeyGenerator.getNumberOfVolumesCreated());
    Assert.assertEquals(1, randomKeyGenerator.getNumberOfBucketsCreated());
    Assert.assertEquals(1, randomKeyGenerator.getNumberOfKeysAdded());
    Assert.assertEquals(0, randomKeyGenerator.getUnsuccessfulValidationCount());
  }

  private StateMachine getStateMachine() throws Exception {
    return TestHelper.getStateMachine(cluster);
  }
}
