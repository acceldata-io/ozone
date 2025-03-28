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

package org.apache.hadoop.ozone.om.request.volume.acl;

import org.apache.hadoop.ozone.OzoneAcl;
import org.apache.hadoop.ozone.om.helpers.OmVolumeArgs;
import org.apache.hadoop.ozone.om.request.OMRequestTestUtils;
import org.apache.hadoop.ozone.om.request.volume.TestOMVolumeRequest;
import org.apache.hadoop.ozone.om.response.OMClientResponse;
import org.apache.hadoop.ozone.protocol.proto.OzoneManagerProtocolProtos;
import org.apache.hadoop.ozone.protocol.proto.OzoneManagerProtocolProtos.OMRequest;
import org.apache.hadoop.ozone.protocol.proto.OzoneManagerProtocolProtos.OMResponse;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

/**
 * Tests volume addAcl request.
 */
public class TestOMVolumeAddAclRequest extends TestOMVolumeRequest {

  @Test
  public void testPreExecute() throws Exception {
    String volumeName = UUID.randomUUID().toString();
    OzoneAcl acl = OzoneAcl.parseAcl("user:bilbo:rw");
    OMRequest originalRequest =
        OMRequestTestUtils.createVolumeAddAclRequest(volumeName, acl);
    long originModTime = originalRequest.getAddAclRequest()
        .getModificationTime();

    OMVolumeAddAclRequest omVolumeAddAclRequest =
        new OMVolumeAddAclRequest(originalRequest);

    OMRequest modifiedRequest = omVolumeAddAclRequest.preExecute(
        ozoneManager);
    Assertions.assertNotEquals(modifiedRequest, originalRequest);

    long newModTime = modifiedRequest.getAddAclRequest().getModificationTime();
    // When preExecute() of adding acl,
    // the new modification time is greater than origin one.
    Assertions.assertTrue(newModTime > originModTime);
  }

  @Test
  public void testValidateAndUpdateCacheSuccess() throws Exception {
    String volumeName = UUID.randomUUID().toString();
    String ownerName = "user1";

    OMRequestTestUtils.addUserToDB(volumeName, ownerName, omMetadataManager);
    OMRequestTestUtils.addVolumeToDB(volumeName, ownerName, omMetadataManager);

    OzoneAcl acl = OzoneAcl.parseAcl("user:bilbo:rwdlncxy[ACCESS]");

    OMRequest originalRequest =
        OMRequestTestUtils.createVolumeAddAclRequest(volumeName, acl);

    OMVolumeAddAclRequest omVolumeAddAclRequest =
        new OMVolumeAddAclRequest(originalRequest);

    omVolumeAddAclRequest.preExecute(ozoneManager);

    String volumeKey = omMetadataManager.getVolumeKey(volumeName);

    // Get Acl before validateAndUpdateCache.
    OmVolumeArgs omVolumeArgs =
        omMetadataManager.getVolumeTable().get(volumeKey);
    // As request is valid volume table should have entry.
    Assertions.assertNotNull(omVolumeArgs);

    OMClientResponse omClientResponse =
        omVolumeAddAclRequest.validateAndUpdateCache(ozoneManager, 1);

    OMResponse omResponse = omClientResponse.getOMResponse();
    Assertions.assertNotNull(omResponse.getAddAclResponse());
    Assertions.assertEquals(OzoneManagerProtocolProtos.Status.OK,
        omResponse.getStatus());

    List<OzoneAcl> aclsAfterSet = omMetadataManager
        .getVolumeTable().get(volumeKey).getAcls();

    // acl is added to aclMapAfterSet
    Assertions.assertEquals(1, aclsAfterSet.size());
    Assertions.assertEquals(acl, aclsAfterSet.get(0));
  }

  @Test
  public void testValidateAndUpdateCacheWithVolumeNotFound()
      throws Exception {
    String volumeName = UUID.randomUUID().toString();
    OzoneAcl acl = OzoneAcl.parseAcl("user:bilbo:rw");
    OMRequest originalRequest =
        OMRequestTestUtils.createVolumeAddAclRequest(volumeName, acl);

    OMVolumeAddAclRequest omVolumeAddAclRequest =
        new OMVolumeAddAclRequest(originalRequest);

    omVolumeAddAclRequest.preExecute(ozoneManager);

    OMClientResponse omClientResponse =
        omVolumeAddAclRequest.validateAndUpdateCache(ozoneManager, 1);

    OMResponse omResponse = omClientResponse.getOMResponse();
    Assertions.assertNotNull(omResponse.getAddAclResponse());
    Assertions.assertEquals(OzoneManagerProtocolProtos.Status.VOLUME_NOT_FOUND,
        omResponse.getStatus());
  }
}
