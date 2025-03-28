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

package org.apache.hadoop.ozone.recon.fsck;

import static org.hadoop.ozone.recon.schema.ContainerSchemaDefinition.UnHealthyContainerStates.ALL_REPLICAS_UNHEALTHY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.apache.hadoop.hdds.client.RatisReplicationConfig;
import org.apache.hadoop.hdds.protocol.DatanodeDetails;
import org.apache.hadoop.hdds.protocol.MockDatanodeDetails;
import org.apache.hadoop.hdds.protocol.proto.HddsProtos;
import org.apache.hadoop.hdds.protocol.proto.StorageContainerDatanodeProtocolProtos.ContainerReplicaProto.State;
import org.apache.hadoop.hdds.scm.ContainerPlacementStatus;
import org.apache.hadoop.hdds.scm.PlacementPolicy;
import org.apache.hadoop.hdds.scm.container.ContainerID;
import org.apache.hadoop.hdds.scm.container.ContainerInfo;
import org.apache.hadoop.hdds.scm.container.ContainerManager;
import org.apache.hadoop.hdds.scm.container.ContainerReplica;
import org.apache.hadoop.hdds.scm.container.common.helpers.ContainerWithPipeline;
import org.apache.hadoop.hdds.scm.container.placement.algorithms.ContainerPlacementStatusDefault;
import org.apache.hadoop.ozone.recon.persistence.ContainerHealthSchemaManager;
import org.apache.hadoop.ozone.recon.scm.ReconStorageContainerManagerFacade;
import org.apache.hadoop.ozone.recon.spi.ReconContainerMetadataManager;
import org.apache.hadoop.ozone.recon.spi.StorageContainerServiceProvider;
import org.apache.hadoop.ozone.recon.tasks.ReconTaskConfig;
import org.apache.ozone.test.LambdaTestUtils;
import org.hadoop.ozone.recon.schema.ContainerSchemaDefinition;
import org.apache.hadoop.ozone.recon.persistence.AbstractReconSqlDBTest;
import org.hadoop.ozone.recon.schema.tables.daos.ReconTaskStatusDao;
import org.hadoop.ozone.recon.schema.tables.daos.UnhealthyContainersDao;
import org.hadoop.ozone.recon.schema.tables.pojos.ReconTaskStatus;
import org.hadoop.ozone.recon.schema.tables.pojos.UnhealthyContainers;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Class to test a single run of the Container Health Task.
 */
public class TestContainerHealthTask extends AbstractReconSqlDBTest {

  @SuppressWarnings("checkstyle:methodlength")
  @Test
  public void testRun() throws Exception {
    UnhealthyContainersDao unHealthyContainersTableHandle =
        getDao(UnhealthyContainersDao.class);

    ContainerHealthSchemaManager containerHealthSchemaManager =
        new ContainerHealthSchemaManager(
            getSchemaDefinition(ContainerSchemaDefinition.class),
            unHealthyContainersTableHandle);
    ReconStorageContainerManagerFacade scmMock =
        mock(ReconStorageContainerManagerFacade.class);
    ReconContainerMetadataManager reconContainerMetadataManager =
        mock(ReconContainerMetadataManager.class);
    MockPlacementPolicy placementMock = new MockPlacementPolicy();
    ContainerManager containerManagerMock = mock(ContainerManager.class);
    StorageContainerServiceProvider scmClientMock =
        mock(StorageContainerServiceProvider.class);
    ContainerReplica unhealthyReplicaMock = mock(ContainerReplica.class);
    when(unhealthyReplicaMock.getState()).thenReturn(State.UNHEALTHY);
    ContainerReplica healthyReplicaMock = mock(ContainerReplica.class);
    when(healthyReplicaMock.getState()).thenReturn(State.CLOSED);

    // Create 7 containers. The first 5 will have various unhealthy states
    // defined below. The container with ID=6 will be healthy and
    // container with ID=7 will be EMPTY_MISSING
    List<ContainerInfo> mockContainers = getMockContainers(7);
    when(scmMock.getScmServiceProvider()).thenReturn(scmClientMock);
    when(scmMock.getContainerManager()).thenReturn(containerManagerMock);
    when(containerManagerMock.getContainers()).thenReturn(mockContainers);
    for (ContainerInfo c : mockContainers) {
      when(containerManagerMock.getContainer(c.containerID())).thenReturn(c);
      when(scmClientMock.getContainerWithPipeline(c.getContainerID()))
          .thenReturn(new ContainerWithPipeline(c, null));
    }
    // Under replicated
    when(containerManagerMock.getContainerReplicas(ContainerID.valueOf(1L)))
        .thenReturn(getMockReplicas(1L, State.CLOSED, State.UNHEALTHY));

    // return all UNHEALTHY replicas for container ID 2 -> UNDER_REPLICATED
    when(containerManagerMock.getContainerReplicas(ContainerID.valueOf(2L)))
        .thenReturn(getMockReplicas(2L, State.UNHEALTHY));

    // return 0 replicas for container ID 3 -> Missing
    when(containerManagerMock.getContainerReplicas(ContainerID.valueOf(3L)))
        .thenReturn(Collections.emptySet());

    // Return 5 Healthy -> Over replicated
    when(containerManagerMock.getContainerReplicas(ContainerID.valueOf(4L)))
        .thenReturn(getMockReplicas(4L, State.CLOSED, State.CLOSED,
        State.CLOSED, State.CLOSED, State.CLOSED));

    // Mis-replicated
    Set<ContainerReplica> misReplicas = getMockReplicas(5L,
        State.CLOSED, State.CLOSED, State.CLOSED);
    placementMock.setMisRepWhenDnPresent(
        misReplicas.iterator().next().getDatanodeDetails().getUuid());
    when(containerManagerMock.getContainerReplicas(ContainerID.valueOf(5L)))
        .thenReturn(misReplicas);

    // Return 3 Healthy -> Healthy container
    when(containerManagerMock.getContainerReplicas(ContainerID.valueOf(6L)))
        .thenReturn(getMockReplicas(6L,
            State.CLOSED, State.CLOSED, State.CLOSED));

    // return 0 replicas for container ID 7 -> EMPTY_MISSING
    when(containerManagerMock.getContainerReplicas(ContainerID.valueOf(7L)))
        .thenReturn(Collections.emptySet());

    List<UnhealthyContainers> all = unHealthyContainersTableHandle.findAll();
    Assertions.assertTrue(all.isEmpty());

    long currentTime = System.currentTimeMillis();
    ReconTaskStatusDao reconTaskStatusDao = getDao(ReconTaskStatusDao.class);
    ReconTaskConfig reconTaskConfig = new ReconTaskConfig();
    reconTaskConfig.setMissingContainerTaskInterval(Duration.ofSeconds(2));
    when(reconContainerMetadataManager.getKeyCountForContainer(
        7L)).thenReturn(5L);
    ContainerHealthTask containerHealthTask =
        new ContainerHealthTask(scmMock.getContainerManager(),
            scmMock.getScmServiceProvider(),
            reconTaskStatusDao, containerHealthSchemaManager,
            placementMock, reconTaskConfig, reconContainerMetadataManager);
    containerHealthTask.start();
    LambdaTestUtils.await(6000, 1000, () ->
        (unHealthyContainersTableHandle.count() == 6));
    UnhealthyContainers rec =
        unHealthyContainersTableHandle.fetchByContainerId(1L).get(0);
    assertEquals("UNDER_REPLICATED", rec.getContainerState());
    assertEquals(2, rec.getReplicaDelta().intValue());

    rec = unHealthyContainersTableHandle.fetchByContainerId(2L).get(0);
    assertEquals("UNDER_REPLICATED", rec.getContainerState());
    assertEquals(3, rec.getReplicaDelta().intValue());

    List<UnhealthyContainers> unhealthyContainers =
        containerHealthSchemaManager.getUnhealthyContainers(
            ALL_REPLICAS_UNHEALTHY, 0, Integer.MAX_VALUE);
    assertEquals(1, unhealthyContainers.size());
    assertEquals(2L,
        unhealthyContainers.get(0).getContainerId().longValue());
    assertEquals(0,
        unhealthyContainers.get(0).getActualReplicaCount().intValue());

    rec = unHealthyContainersTableHandle.fetchByContainerId(3L).get(0);
    assertEquals("EMPTY_MISSING", rec.getContainerState());
    assertEquals(3, rec.getReplicaDelta().intValue());

    rec = unHealthyContainersTableHandle.fetchByContainerId(7L).get(0);
    assertEquals("MISSING", rec.getContainerState());
    assertEquals(3, rec.getReplicaDelta().intValue());

    rec = unHealthyContainersTableHandle.fetchByContainerId(4L).get(0);
    assertEquals("OVER_REPLICATED", rec.getContainerState());
    assertEquals(-2, rec.getReplicaDelta().intValue());

    rec = unHealthyContainersTableHandle.fetchByContainerId(5L).get(0);
    assertEquals("MIS_REPLICATED", rec.getContainerState());
    assertEquals(1, rec.getReplicaDelta().intValue());
    assertEquals(2, rec.getExpectedReplicaCount().intValue());
    assertEquals(1, rec.getActualReplicaCount().intValue());
    assertNotNull(rec.getReason());

    ReconTaskStatus taskStatus =
        reconTaskStatusDao.findById(containerHealthTask.getTaskName());
    Assertions.assertTrue(taskStatus.getLastUpdatedTimestamp() >
        currentTime);

    // Now run the job again, to check that relevant records are updated or
    // removed as appropriate. Need to adjust the return value for all the mocks
    // Under replicated -> Delta goes from 2 to 1
    when(containerManagerMock.getContainerReplicas(ContainerID.valueOf(1L)))
        .thenReturn(getMockReplicas(1L, State.CLOSED, State.CLOSED));

    // ID 2 was missing - make it healthy now
    when(containerManagerMock.getContainerReplicas(ContainerID.valueOf(2L)))
        .thenReturn(getMockReplicas(2L,
            State.CLOSED, State.CLOSED, State.CLOSED));

    // return 0 replicas for container ID 3 -> Still Missing
    when(containerManagerMock.getContainerReplicas(ContainerID.valueOf(3L)))
        .thenReturn(Collections.emptySet());

    // Return 4 Healthy -> Delta changes from -2 to -1
    when(containerManagerMock.getContainerReplicas(ContainerID.valueOf(4L)))
        .thenReturn(getMockReplicas(4L, State.CLOSED, State.CLOSED,
            State.CLOSED, State.CLOSED));

    // Was mis-replicated - make it healthy now
    placementMock.setMisRepWhenDnPresent(null);

    LambdaTestUtils.await(6000, 1000, () ->
        (unHealthyContainersTableHandle.count() == 4));
    rec = unHealthyContainersTableHandle.fetchByContainerId(1L).get(0);
    assertEquals("UNDER_REPLICATED", rec.getContainerState());
    assertEquals(1, rec.getReplicaDelta().intValue());

    // This container is now healthy, it should not be in the table any more
    assertEquals(0,
        unHealthyContainersTableHandle.fetchByContainerId(2L).size());

    rec = unHealthyContainersTableHandle.fetchByContainerId(3L).get(0);
    assertEquals("EMPTY_MISSING", rec.getContainerState());
    assertEquals(3, rec.getReplicaDelta().intValue());

    rec = unHealthyContainersTableHandle.fetchByContainerId(7L).get(0);
    assertEquals("MISSING", rec.getContainerState());
    assertEquals(3, rec.getReplicaDelta().intValue());

    rec = unHealthyContainersTableHandle.fetchByContainerId(4L).get(0);
    assertEquals("OVER_REPLICATED", rec.getContainerState());
    assertEquals(-1, rec.getReplicaDelta().intValue());

    // This container is now healthy, it should not be in the table any more
    assertEquals(0,
        unHealthyContainersTableHandle.fetchByContainerId(5L).size());
  }

  @Test
  public void testDeletedContainer() throws Exception {
    UnhealthyContainersDao unHealthyContainersTableHandle =
        getDao(UnhealthyContainersDao.class);

    ContainerHealthSchemaManager containerHealthSchemaManager =
        new ContainerHealthSchemaManager(
            getSchemaDefinition(ContainerSchemaDefinition.class),
            unHealthyContainersTableHandle);
    ReconStorageContainerManagerFacade scmMock =
        mock(ReconStorageContainerManagerFacade.class);
    MockPlacementPolicy placementMock = new MockPlacementPolicy();
    ContainerManager containerManagerMock = mock(ContainerManager.class);
    StorageContainerServiceProvider scmClientMock =
        mock(StorageContainerServiceProvider.class);
    ReconContainerMetadataManager reconContainerMetadataManager =
        mock(ReconContainerMetadataManager.class);

    // Create 2 containers. The first is OPEN will no replicas, the second is
    // CLOSED with no replicas.
    List<ContainerInfo> mockContainers = getMockContainers(3);
    when(scmMock.getScmServiceProvider()).thenReturn(scmClientMock);
    when(scmMock.getContainerManager()).thenReturn(containerManagerMock);
    when(containerManagerMock.getContainers()).thenReturn(mockContainers);
    for (ContainerInfo c : mockContainers) {
      when(containerManagerMock.getContainer(c.containerID())).thenReturn(c);
      when(scmClientMock.getContainerWithPipeline(c.getContainerID()))
          .thenReturn(new ContainerWithPipeline(c, null));
    }
    // Empty Container with OPEN State and no replicas
    when(containerManagerMock.getContainer(ContainerID.valueOf(1L)).getState())
        .thenReturn(HddsProtos.LifeCycleState.OPEN);
    when(containerManagerMock.getContainerReplicas(ContainerID.valueOf(1L)))
        .thenReturn(Collections.emptySet());
    when(scmClientMock.getContainerWithPipeline(1))
        .thenReturn(new ContainerWithPipeline(mockContainers.get(0), null));

    // Container State CLOSED with no replicas
    when(containerManagerMock.getContainer(ContainerID.valueOf(2L)).getState())
        .thenReturn(HddsProtos.LifeCycleState.CLOSED);
    when(containerManagerMock.getContainerReplicas(ContainerID.valueOf(2L)))
        .thenReturn(Collections.emptySet());
    ContainerInfo mockDeletedContainer = getMockDeletedContainer(2);
    when(scmClientMock.getContainerWithPipeline(2))
        .thenReturn(new ContainerWithPipeline(mockDeletedContainer, null));

    // Container with OPEN State and no replicas
    when(containerManagerMock.getContainer(ContainerID.valueOf(3L)).getState())
        .thenReturn(HddsProtos.LifeCycleState.OPEN);
    when(containerManagerMock.getContainerReplicas(ContainerID.valueOf(3L)))
        .thenReturn(Collections.emptySet());
    when(scmClientMock.getContainerWithPipeline(3))
        .thenReturn(new ContainerWithPipeline(mockContainers.get(0), null));

    List<UnhealthyContainers> all = unHealthyContainersTableHandle.findAll();
    Assertions.assertTrue(all.isEmpty());

    long currentTime = System.currentTimeMillis();
    ReconTaskStatusDao reconTaskStatusDao = getDao(ReconTaskStatusDao.class);
    ReconTaskConfig reconTaskConfig = new ReconTaskConfig();
    reconTaskConfig.setMissingContainerTaskInterval(Duration.ofSeconds(2));
    when(reconContainerMetadataManager.getKeyCountForContainer(
        1L)).thenReturn(5L);
    ContainerHealthTask containerHealthTask =
        new ContainerHealthTask(scmMock.getContainerManager(),
            scmMock.getScmServiceProvider(),
            reconTaskStatusDao, containerHealthSchemaManager,
            placementMock, reconTaskConfig, reconContainerMetadataManager);
    containerHealthTask.start();
    LambdaTestUtils.await(6000, 1000, () ->
        (unHealthyContainersTableHandle.count() == 2));
    UnhealthyContainers rec =
        unHealthyContainersTableHandle.fetchByContainerId(1L).get(0);
    assertEquals("MISSING", rec.getContainerState());
    assertEquals(3, rec.getReplicaDelta().intValue());

    rec =
        unHealthyContainersTableHandle.fetchByContainerId(3L).get(0);
    assertEquals("EMPTY_MISSING", rec.getContainerState());
    assertEquals(3, rec.getReplicaDelta().intValue());

    ReconTaskStatus taskStatus =
        reconTaskStatusDao.findById(containerHealthTask.getTaskName());
    Assertions.assertTrue(taskStatus.getLastUpdatedTimestamp() >
        currentTime);
  }

  private Set<ContainerReplica> getMockReplicas(
      long containerId, State...states) {
    Set<ContainerReplica> replicas = new HashSet<>();
    for (State s : states) {
      replicas.add(ContainerReplica.newBuilder()
          .setDatanodeDetails(MockDatanodeDetails.randomDatanodeDetails())
          .setContainerState(s)
          .setContainerID(ContainerID.valueOf(containerId))
          .setSequenceId(1)
          .build());
    }
    return replicas;
  }

  private List<ContainerInfo> getMockContainers(int num) {
    List<ContainerInfo> containers = new ArrayList<>();
    for (int i = 1; i <= num; i++) {
      ContainerInfo c = mock(ContainerInfo.class);
      when(c.getContainerID()).thenReturn((long)i);
      when(c.getReplicationConfig())
          .thenReturn(RatisReplicationConfig.getInstance(
              HddsProtos.ReplicationFactor.THREE));
      when(c.containerID()).thenReturn(ContainerID.valueOf(i));
      containers.add(c);
    }
    return containers;
  }

  private ContainerInfo getMockDeletedContainer(int containerID) {
    ContainerInfo c = mock(ContainerInfo.class);
    when(c.getContainerID()).thenReturn((long)containerID);
    when(c.getReplicationConfig())
        .thenReturn(RatisReplicationConfig
            .getInstance(HddsProtos.ReplicationFactor.THREE));
    when(c.containerID()).thenReturn(ContainerID.valueOf(containerID));
    when(c.getState()).thenReturn(HddsProtos.LifeCycleState.DELETED);
    return c;
  }

  /**
   * This is a simple implementation of PlacementPolicy, so that when
   * validateContainerPlacement() is called, by default it will return a value
   * placement object. To get an invalid placement object, simply pass a UUID
   * of a datanode via setMisRepWhenDnPresent. If a DN with that UUID is passed
   * to validateContainerPlacement, then it will return an invalid placement.
   */
  private static class MockPlacementPolicy implements
          PlacementPolicy {

    private UUID misRepWhenDnPresent = null;

    public void setMisRepWhenDnPresent(UUID dn) {
      misRepWhenDnPresent = dn;
    }

    @Override
    public List<DatanodeDetails> chooseDatanodes(
        List<DatanodeDetails> usedNodes, List<DatanodeDetails> excludedNodes,
        List<DatanodeDetails> favoredNodes,
        int nodesRequired, long metadataSizeRequired, long dataSizeRequired)
        throws IOException {
      return null;
    }

    @Override
    public ContainerPlacementStatus validateContainerPlacement(
        List<DatanodeDetails> dns, int replicas) {
      if (misRepWhenDnPresent != null && isDnPresent(dns)) {
        return new ContainerPlacementStatusDefault(1, 2, 3);
      } else {
        return new ContainerPlacementStatusDefault(1, 1, 1);
      }
    }

    @Override
    public Set<ContainerReplica> replicasToCopyToFixMisreplication(
            Map<ContainerReplica, Boolean> replicas) {
      return Collections.emptySet();
    }


    @Override
    public Set<ContainerReplica> replicasToRemoveToFixOverreplication(
            Set<ContainerReplica> replicas, int expectedCountPerUniqueReplica) {
      return null;
    }

    private boolean isDnPresent(List<DatanodeDetails> dns) {
      for (DatanodeDetails dn : dns) {
        if (misRepWhenDnPresent != null
            && dn.getUuid().equals(misRepWhenDnPresent)) {
          return true;
        }
      }
      return false;
    }
  }

}
