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

package org.apache.hadoop.ozone.om.response.volume;

import org.apache.hadoop.hdds.conf.OzoneConfiguration;
import org.apache.hadoop.ozone.om.OMConfigKeys;
import org.apache.hadoop.ozone.om.OMMetadataManager;
import org.apache.hadoop.ozone.om.OmMetadataManagerImpl;
import org.apache.hadoop.ozone.om.helpers.OmVolumeArgs;
import org.apache.hadoop.ozone.protocol.proto.OzoneManagerProtocolProtos;
import org.apache.hadoop.ozone.protocol.proto.OzoneManagerProtocolProtos
    .CreateVolumeResponse;
import org.apache.hadoop.ozone.protocol.proto.OzoneManagerProtocolProtos
    .OMResponse;
import org.apache.hadoop.ozone.storage.proto.OzoneManagerStorageProtos.PersistedUserVolumeInfo;
import org.apache.hadoop.util.Time;
import org.apache.hadoop.hdds.utils.db.BatchOperation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.util.UUID;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * This class tests OMVolumeCreateResponse.
 */
public class TestOMVolumeDeleteResponse {

  @TempDir
  private Path folder;

  private OMMetadataManager omMetadataManager;
  private BatchOperation batchOperation;

  @BeforeEach
  public void setup() throws Exception {
    OzoneConfiguration ozoneConfiguration = new OzoneConfiguration();
    ozoneConfiguration.set(OMConfigKeys.OZONE_OM_DB_DIRS,
        folder.toAbsolutePath().toString());
    omMetadataManager = new OmMetadataManagerImpl(ozoneConfiguration, null);
    batchOperation = omMetadataManager.getStore().initBatchOperation();
  }

  @AfterEach
  public void tearDown() {
    if (batchOperation != null) {
      batchOperation.close();
    }
  }

  @Test
  public void testAddToDBBatch() throws Exception {

    String volumeName = UUID.randomUUID().toString();
    String userName = "user1";
    PersistedUserVolumeInfo volumeList = PersistedUserVolumeInfo.newBuilder()
        .setObjectID(1)
        .setUpdateID(1)
        .addVolumeNames(volumeName).build();

    OMResponse omResponse = OMResponse.newBuilder()
        .setCmdType(OzoneManagerProtocolProtos.Type.DeleteVolume)
        .setStatus(OzoneManagerProtocolProtos.Status.OK)
        .setSuccess(true)
        .setCreateVolumeResponse(CreateVolumeResponse.getDefaultInstance())
        .build();

    OmVolumeArgs omVolumeArgs = OmVolumeArgs.newBuilder()
        .setOwnerName(userName).setAdminName(userName)
        .setVolume(volumeName).setCreationTime(Time.now()).build();
    OMVolumeCreateResponse omVolumeCreateResponse =
        new OMVolumeCreateResponse(omResponse, omVolumeArgs, volumeList);

    // As we are deleting updated volume list should be empty.
    PersistedUserVolumeInfo updatedVolumeList =
        PersistedUserVolumeInfo.newBuilder()
        .setObjectID(1).setUpdateID(1).build();
    OMVolumeDeleteResponse omVolumeDeleteResponse =
        new OMVolumeDeleteResponse(omResponse, volumeName, userName,
            updatedVolumeList);

    omVolumeCreateResponse.addToDBBatch(omMetadataManager, batchOperation);
    omVolumeDeleteResponse.addToDBBatch(omMetadataManager, batchOperation);

    // Do manual commit and see whether addToBatch is successful or not.
    omMetadataManager.getStore().commitBatchOperation(batchOperation);

    Assertions.assertNull(omMetadataManager.getVolumeTable().get(
            omMetadataManager.getVolumeKey(volumeName)));

    Assertions.assertNull(omMetadataManager.getUserTable().get(
        omMetadataManager.getUserKey(userName)));
  }

  @Test
  public void testAddToDBBatchNoOp() throws Exception {

    OMResponse omResponse = OMResponse.newBuilder()
        .setCmdType(OzoneManagerProtocolProtos.Type.DeleteVolume)
        .setStatus(OzoneManagerProtocolProtos.Status.VOLUME_NOT_FOUND)
        .setSuccess(false)
        .setCreateVolumeResponse(CreateVolumeResponse.getDefaultInstance())
        .build();

    OMVolumeDeleteResponse omVolumeDeleteResponse = new OMVolumeDeleteResponse(
        omResponse);

    try {
      omVolumeDeleteResponse.checkAndUpdateDB(omMetadataManager,
          batchOperation);
    } catch (IOException ex) {
      fail("testAddToDBBatchFailure failed");
    }

  }


}
