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

package org.apache.hadoop.ozone.om.request.volume;

import java.util.UUID;

import org.apache.hadoop.ozone.om.request.OMRequestTestUtils;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import org.apache.hadoop.ozone.om.helpers.OmBucketInfo;
import org.apache.hadoop.ozone.om.response.OMClientResponse;
import org.apache.hadoop.ozone.protocol.proto.OzoneManagerProtocolProtos;
import org.apache.hadoop.ozone.protocol.proto.OzoneManagerProtocolProtos
    .DeleteVolumeRequest;
import org.apache.hadoop.ozone.protocol.proto.OzoneManagerProtocolProtos
    .OMRequest;

/**
 * Tests delete volume request.
 */
public class TestOMVolumeDeleteRequest extends TestOMVolumeRequest {

  @Test
  public void testPreExecute() throws Exception {
    String volumeName = UUID.randomUUID().toString();
    OMRequest originalRequest = deleteVolumeRequest(volumeName);

    OMVolumeDeleteRequest omVolumeDeleteRequest =
        new OMVolumeDeleteRequest(originalRequest);

    OMRequest modifiedRequest = omVolumeDeleteRequest.preExecute(ozoneManager);
    Assertions.assertNotEquals(originalRequest, modifiedRequest);
  }

  @Test
  public void testValidateAndUpdateCacheSuccess() throws Exception {
    String volumeName = UUID.randomUUID().toString();
    String ownerName = "user1";

    OMRequest originalRequest = deleteVolumeRequest(volumeName);

    OMVolumeDeleteRequest omVolumeDeleteRequest =
        new OMVolumeDeleteRequest(originalRequest);

    omVolumeDeleteRequest.preExecute(ozoneManager);

    // Add volume and user to DB
    OMRequestTestUtils.addVolumeToDB(volumeName, ownerName, omMetadataManager);
    OMRequestTestUtils.addUserToDB(volumeName, ownerName, omMetadataManager);

    String volumeKey = omMetadataManager.getVolumeKey(volumeName);
    String ownerKey = omMetadataManager.getUserKey(ownerName);


    Assertions.assertNotNull(omMetadataManager.getVolumeTable().get(volumeKey));
    Assertions.assertNotNull(omMetadataManager.getUserTable().get(ownerKey));

    OMClientResponse omClientResponse =
        omVolumeDeleteRequest.validateAndUpdateCache(ozoneManager, 1);

    OzoneManagerProtocolProtos.OMResponse omResponse =
        omClientResponse.getOMResponse();
    Assertions.assertNotNull(omResponse.getCreateVolumeResponse());
    Assertions.assertEquals(OzoneManagerProtocolProtos.Status.OK,
        omResponse.getStatus());

    Assertions.assertEquals(0, omMetadataManager.getUserTable().get(ownerKey)
        .getVolumeNamesList().size());
    // As now volume is deleted, table should not have those entries.
    Assertions.assertNull(omMetadataManager.getVolumeTable().get(volumeKey));
  }

  @Test
  public void testValidateAndUpdateCacheWithVolumeNotFound()
      throws Exception {
    String volumeName = UUID.randomUUID().toString();
    OMRequest originalRequest = deleteVolumeRequest(volumeName);

    OMVolumeDeleteRequest omVolumeDeleteRequest =
        new OMVolumeDeleteRequest(originalRequest);

    omVolumeDeleteRequest.preExecute(ozoneManager);

    OMClientResponse omClientResponse =
        omVolumeDeleteRequest.validateAndUpdateCache(ozoneManager, 1);

    OzoneManagerProtocolProtos.OMResponse omResponse =
        omClientResponse.getOMResponse();
    Assertions.assertNotNull(omResponse.getCreateVolumeResponse());
    Assertions.assertEquals(OzoneManagerProtocolProtos.Status.VOLUME_NOT_FOUND,
        omResponse.getStatus());
  }

  @Test
  public void testValidateAndUpdateCacheWithVolumeNotEmpty() throws Exception {
    String volumeName = UUID.randomUUID().toString();
    String ownerName = "user1";

    OMRequest originalRequest = deleteVolumeRequest(volumeName);

    OMVolumeDeleteRequest omVolumeDeleteRequest =
        new OMVolumeDeleteRequest(originalRequest);

    omVolumeDeleteRequest.preExecute(ozoneManager);

    // Add some bucket to bucket table cache.
    String bucketName = UUID.randomUUID().toString();

    OmBucketInfo omBucketInfo = OmBucketInfo.newBuilder()
        .setVolumeName(volumeName).setBucketName(bucketName).build();
    OMRequestTestUtils.addBucketToOM(omMetadataManager, omBucketInfo);

    // Add user and volume to DB.
    OMRequestTestUtils.addUserToDB(volumeName, ownerName, omMetadataManager);
    OMRequestTestUtils.addVolumeToDB(volumeName, ownerName, omMetadataManager);

    OMClientResponse omClientResponse =
        omVolumeDeleteRequest.validateAndUpdateCache(ozoneManager, 1L);

    OzoneManagerProtocolProtos.OMResponse omResponse =
        omClientResponse.getOMResponse();
    Assertions.assertNotNull(omResponse.getCreateVolumeResponse());
    Assertions.assertEquals(OzoneManagerProtocolProtos.Status.VOLUME_NOT_EMPTY,
        omResponse.getStatus());
  }

  /**
   * Create OMRequest for delete volume.
   * @param volumeName
   * @return OMRequest
   */
  private OMRequest deleteVolumeRequest(String volumeName) {
    DeleteVolumeRequest deleteVolumeRequest =
        DeleteVolumeRequest.newBuilder().setVolumeName(volumeName).build();

    return OMRequest.newBuilder().setClientId(UUID.randomUUID().toString())
        .setCmdType(OzoneManagerProtocolProtos.Type.DeleteVolume)
        .setDeleteVolumeRequest(deleteVolumeRequest).build();
  }
}
