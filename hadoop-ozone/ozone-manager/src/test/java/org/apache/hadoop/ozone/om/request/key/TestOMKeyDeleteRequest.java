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

package org.apache.hadoop.ozone.om.request.key;

import java.util.UUID;

import org.apache.hadoop.ozone.om.helpers.BucketLayout;
import org.apache.hadoop.ozone.om.request.OMRequestTestUtils;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import org.apache.hadoop.ozone.om.helpers.OmKeyInfo;
import org.apache.hadoop.ozone.om.response.OMClientResponse;
import org.apache.hadoop.ozone.protocol.proto.OzoneManagerProtocolProtos;
import org.apache.hadoop.ozone.protocol.proto.OzoneManagerProtocolProtos
    .DeleteKeyRequest;
import org.apache.hadoop.ozone.protocol.proto.OzoneManagerProtocolProtos
    .OMRequest;
import org.apache.hadoop.ozone.protocol.proto.OzoneManagerProtocolProtos
    .KeyArgs;

/**
 * Tests OmKeyDelete request.
 */
public class TestOMKeyDeleteRequest extends TestOMKeyRequest {

  @Test
  public void testPreExecute() throws Exception {
    doPreExecute(createDeleteKeyRequest());
  }

  @Test
  public void testValidateAndUpdateCache() throws Exception {
    // Add volume, bucket and key entries to OM DB.
    OMRequestTestUtils.addVolumeAndBucketToDB(volumeName, bucketName,
        omMetadataManager, getBucketLayout());

    String ozoneKey = addKeyToTable();

    OmKeyInfo omKeyInfo =
        omMetadataManager.getKeyTable(getBucketLayout()).get(ozoneKey);

    // As we added manually to key table.
    Assertions.assertNotNull(omKeyInfo);

    OMRequest modifiedOmRequest =
            doPreExecute(createDeleteKeyRequest());

    OMKeyDeleteRequest omKeyDeleteRequest =
            getOmKeyDeleteRequest(modifiedOmRequest);

    OMClientResponse omClientResponse =
        omKeyDeleteRequest.validateAndUpdateCache(ozoneManager, 100L);

    Assertions.assertEquals(OzoneManagerProtocolProtos.Status.OK,
        omClientResponse.getOMResponse().getStatus());
    // Now after calling validateAndUpdateCache, it should be deleted.

    omKeyInfo = omMetadataManager.getKeyTable(getBucketLayout()).get(ozoneKey);

    Assertions.assertNull(omKeyInfo);
  }

  @Test
  public void testValidateAndUpdateCacheWithKeyNotFound() throws Exception {
    OMRequest modifiedOmRequest =
        doPreExecute(createDeleteKeyRequest());

    OMKeyDeleteRequest omKeyDeleteRequest =
            getOmKeyDeleteRequest(modifiedOmRequest);

    // Add only volume and bucket entry to DB.
    // In actual implementation we don't check for bucket/volume exists
    // during delete key.
    OMRequestTestUtils.addVolumeAndBucketToDB(volumeName, bucketName,
        omMetadataManager, getBucketLayout());

    OMClientResponse omClientResponse =
        omKeyDeleteRequest.validateAndUpdateCache(ozoneManager, 100L);

    Assertions.assertEquals(OzoneManagerProtocolProtos.Status.KEY_NOT_FOUND,
        omClientResponse.getOMResponse().getStatus());
  }

  @Test
  public void testValidateAndUpdateCacheWithVolumeNotFound() throws Exception {
    OMRequest modifiedOmRequest =
        doPreExecute(createDeleteKeyRequest());

    OMKeyDeleteRequest omKeyDeleteRequest =
            getOmKeyDeleteRequest(modifiedOmRequest);

    OMClientResponse omClientResponse = omKeyDeleteRequest
        .validateAndUpdateCache(ozoneManager, 100L);

    Assertions.assertEquals(OzoneManagerProtocolProtos.Status.VOLUME_NOT_FOUND,
        omClientResponse.getOMResponse().getStatus());
  }

  @Test
  public void testValidateAndUpdateCacheWithBucketNotFound() throws Exception {
    OMRequest modifiedOmRequest =
        doPreExecute(createDeleteKeyRequest());

    OMKeyDeleteRequest omKeyDeleteRequest =
            getOmKeyDeleteRequest(modifiedOmRequest);

    OMRequestTestUtils.addVolumeToDB(volumeName, omMetadataManager);

    OMClientResponse omClientResponse = omKeyDeleteRequest
        .validateAndUpdateCache(ozoneManager, 100L);

    Assertions.assertEquals(OzoneManagerProtocolProtos.Status.BUCKET_NOT_FOUND,
            omClientResponse.getOMResponse().getStatus());
  }

  /**
   * This method calls preExecute and verify the modified request.
   * @param originalOmRequest
   * @return OMRequest - modified request returned from preExecute.
   * @throws Exception
   */
  private OMRequest doPreExecute(OMRequest originalOmRequest) throws Exception {

    OMKeyDeleteRequest omKeyDeleteRequest =
            getOmKeyDeleteRequest(originalOmRequest);

    OMRequest modifiedOmRequest = omKeyDeleteRequest.preExecute(ozoneManager);

    // Will not be equal, as UserInfo will be set.
    Assertions.assertNotEquals(originalOmRequest, modifiedOmRequest);

    return modifiedOmRequest;
  }

  /**
   * Create OMRequest which encapsulates DeleteKeyRequest.
   * @return OMRequest
   */
  private OMRequest createDeleteKeyRequest() {
    KeyArgs keyArgs = KeyArgs.newBuilder().setBucketName(bucketName)
        .setVolumeName(volumeName).setKeyName(keyName).build();

    DeleteKeyRequest deleteKeyRequest =
        DeleteKeyRequest.newBuilder().setKeyArgs(keyArgs).build();

    return OMRequest.newBuilder().setDeleteKeyRequest(deleteKeyRequest)
        .setCmdType(OzoneManagerProtocolProtos.Type.DeleteKey)
        .setClientId(UUID.randomUUID().toString()).build();
  }

  protected String addKeyToTable() throws Exception {
    OMRequestTestUtils.addKeyToTable(false, volumeName,
            bucketName, keyName, clientID, replicationType, replicationFactor,
            omMetadataManager);

    return omMetadataManager.getOzoneKey(volumeName, bucketName,
            keyName);
  }

  protected OMKeyDeleteRequest getOmKeyDeleteRequest(
      OMRequest modifiedOmRequest) {
    return new OMKeyDeleteRequest(modifiedOmRequest, BucketLayout.DEFAULT);
  }
}
