/**
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

package org.apache.hadoop.ozone.scm.pipeline;

import org.apache.hadoop.hdds.HddsConfigKeys;
import org.apache.hadoop.hdds.client.RatisReplicationConfig;
import org.apache.hadoop.hdds.conf.OzoneConfiguration;
import org.apache.hadoop.hdds.protocol.proto.HddsProtos.ReplicationFactor;
import org.apache.hadoop.hdds.scm.container.common.helpers.AllocatedBlock;
import org.apache.hadoop.hdds.scm.container.common.helpers.ExcludeList;
import org.apache.hadoop.hdds.scm.pipeline.Pipeline;
import org.apache.hadoop.hdds.scm.pipeline.PipelineManager;
import org.apache.hadoop.hdds.scm.pipeline.SCMPipelineMetrics;
import org.apache.hadoop.metrics2.MetricsRecordBuilder;
import org.apache.hadoop.ozone.MiniOzoneCluster;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.util.Optional;
import java.util.concurrent.TimeoutException;

import static org.apache.hadoop.test.MetricsAsserts.assertCounter;
import static org.apache.hadoop.test.MetricsAsserts.getLongCounter;
import static org.apache.hadoop.test.MetricsAsserts.getMetrics;

/**
 * Test cases to verify the metrics exposed by SCMPipelineManager.
 */
@Timeout(300)
public class TestSCMPipelineMetrics {

  private MiniOzoneCluster cluster;

  @BeforeEach
  public void setup() throws Exception {
    OzoneConfiguration conf = new OzoneConfiguration();
    conf.set(HddsConfigKeys.HDDS_SCM_SAFEMODE_PIPELINE_AVAILABILITY_CHECK,
        Boolean.TRUE.toString());
    cluster = MiniOzoneCluster.newBuilder(conf)
        .setNumDatanodes(3)
        .build();
    cluster.waitForClusterToBeReady();
  }

  /**
   * Verifies pipeline creation metric.
   */
  @Test
  public void testPipelineCreation() {
    MetricsRecordBuilder metrics = getMetrics(
        SCMPipelineMetrics.class.getSimpleName());
    long numPipelineCreated =
        getLongCounter("NumPipelineCreated", metrics);
    // Pipelines are created in background when the cluster starts.
    Assertions.assertTrue(numPipelineCreated > 0);
  }

  /**
   * Verifies pipeline destroy metric.
   */
  @Test
  public void testPipelineDestroy() {
    PipelineManager pipelineManager = cluster
        .getStorageContainerManager().getPipelineManager();
    Optional<Pipeline> pipeline = pipelineManager
        .getPipelines().stream().findFirst();
    Assertions.assertTrue(pipeline.isPresent());
    Assertions.assertDoesNotThrow(() -> {
      PipelineManager pm = cluster.getStorageContainerManager()
          .getPipelineManager();
      pm.closePipeline(pipeline.get().getId());
      pm.deletePipeline(pipeline.get().getId());
    });
    MetricsRecordBuilder metrics = getMetrics(
        SCMPipelineMetrics.class.getSimpleName());
    assertCounter("NumPipelineDestroyed", 1L, metrics);
  }

  @Test
  public void testNumBlocksAllocated() throws IOException, TimeoutException {
    AllocatedBlock block =
        cluster.getStorageContainerManager().getScmBlockManager()
            .allocateBlock(5,
                RatisReplicationConfig.getInstance(ReplicationFactor.ONE),
                "Test", new ExcludeList());
    MetricsRecordBuilder metrics =
        getMetrics(SCMPipelineMetrics.class.getSimpleName());
    Pipeline pipeline = block.getPipeline();
    long numBlocksAllocated = getLongCounter(
        SCMPipelineMetrics.getBlockAllocationMetricName(pipeline), metrics);
    Assertions.assertEquals(numBlocksAllocated, 1);

    // destroy the pipeline
    Assertions.assertDoesNotThrow(() ->
        cluster.getStorageContainerManager().getClientProtocolServer()
            .closePipeline(pipeline.getId().getProtobuf()));

    MetricsRecordBuilder finalMetrics =
        getMetrics(SCMPipelineMetrics.class.getSimpleName());
    Throwable t = Assertions.assertThrows(AssertionError.class, () ->
        getLongCounter(SCMPipelineMetrics
            .getBlockAllocationMetricName(pipeline), finalMetrics));
    Assertions.assertTrue(t.getMessage().contains(
        "Expected exactly one metric for name " + SCMPipelineMetrics
            .getBlockAllocationMetricName(block.getPipeline())));
  }

  @AfterEach
  public void teardown() {
    cluster.shutdown();
  }
}
