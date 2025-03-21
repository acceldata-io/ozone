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

package org.apache.hadoop.ozone.om.request.key;

import java.io.IOException;
import java.util.ArrayList;

import org.apache.hadoop.ozone.om.OzoneManager;
import org.apache.hadoop.ozone.om.helpers.SnapshotInfo;
import org.apache.hadoop.ozone.om.request.util.OmResponseUtil;
import org.apache.hadoop.ozone.om.response.OMClientResponse;
import org.apache.hadoop.ozone.om.response.key.OMKeyPurgeResponse;
import org.apache.hadoop.ozone.protocol.proto.OzoneManagerProtocolProtos.DeletedKeys;
import org.apache.hadoop.ozone.protocol.proto.OzoneManagerProtocolProtos.OMRequest;
import org.apache.hadoop.ozone.protocol.proto.OzoneManagerProtocolProtos.OMResponse;
import org.apache.hadoop.ozone.protocol.proto.OzoneManagerProtocolProtos.PurgeKeysRequest;
import org.apache.hadoop.ozone.protocol.proto.OzoneManagerProtocolProtos.SnapshotMoveKeyInfos;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Handles purging of keys from OM DB.
 */
public class OMKeyPurgeRequest extends OMKeyRequest {

  private static final Logger LOG =
      LoggerFactory.getLogger(OMKeyPurgeRequest.class);

  public OMKeyPurgeRequest(OMRequest omRequest) {
    super(omRequest);
  }

  @Override
  public OMClientResponse validateAndUpdateCache(OzoneManager ozoneManager,
      long trxnLogIndex) {
    PurgeKeysRequest purgeKeysRequest = getOmRequest().getPurgeKeysRequest();
    List<DeletedKeys> bucketDeletedKeysList = purgeKeysRequest
        .getDeletedKeysList();
    List<SnapshotMoveKeyInfos> keysToUpdateList = purgeKeysRequest
        .getKeysToUpdateList();
    String fromSnapshot = purgeKeysRequest.hasSnapshotTableKey() ?
        purgeKeysRequest.getSnapshotTableKey() : null;
    List<String> keysToBePurgedList = new ArrayList<>();

    OMResponse.Builder omResponse = OmResponseUtil.getOMResponseBuilder(
        getOmRequest());
    OMClientResponse omClientResponse = null;

    for (DeletedKeys bucketWithDeleteKeys : bucketDeletedKeysList) {
      for (String deletedKey : bucketWithDeleteKeys.getKeysList()) {
        keysToBePurgedList.add(deletedKey);
      }
    }

    try {
      SnapshotInfo fromSnapshotInfo = null;
      if (fromSnapshot != null) {
        fromSnapshotInfo = ozoneManager.getMetadataManager()
            .getSnapshotInfoTable().get(fromSnapshot);
      }
      omClientResponse = new OMKeyPurgeResponse(omResponse.build(),
          keysToBePurgedList, fromSnapshotInfo, keysToUpdateList);
    } catch (IOException ex) {
      omClientResponse = new OMKeyPurgeResponse(
          createErrorOMResponse(omResponse, ex));
    }

    return omClientResponse;
  }

}
