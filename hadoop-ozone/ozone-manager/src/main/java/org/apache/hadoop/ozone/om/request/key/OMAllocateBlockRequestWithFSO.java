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

import com.google.common.base.Preconditions;
import org.apache.hadoop.hdds.client.ReplicationConfig;
import org.apache.hadoop.ozone.OzoneConsts;
import org.apache.hadoop.ozone.audit.AuditLogger;
import org.apache.hadoop.ozone.audit.OMAction;
import org.apache.hadoop.ozone.om.OMMetadataManager;
import org.apache.hadoop.ozone.om.OMMetrics;
import org.apache.hadoop.ozone.om.OzoneManager;
import org.apache.hadoop.ozone.om.exceptions.OMException;
import org.apache.hadoop.ozone.om.helpers.OmBucketInfo;
import org.apache.hadoop.ozone.om.helpers.OmKeyInfo;
import org.apache.hadoop.ozone.om.helpers.OmKeyLocationInfo;
import org.apache.hadoop.ozone.om.helpers.OzoneFSUtils;
import org.apache.hadoop.ozone.om.helpers.BucketLayout;
import org.apache.hadoop.ozone.om.helpers.QuotaUtil;
import org.apache.hadoop.ozone.om.request.file.OMFileRequest;
import org.apache.hadoop.ozone.om.request.util.OmResponseUtil;
import org.apache.hadoop.ozone.om.response.OMClientResponse;
import org.apache.hadoop.ozone.om.response.key.OMAllocateBlockResponseWithFSO;
import org.apache.hadoop.ozone.protocol.proto.OzoneManagerProtocolProtos;
import org.apache.hadoop.ozone.protocol.proto.OzoneManagerProtocolProtos.AllocateBlockRequest;
import org.apache.hadoop.ozone.protocol.proto.OzoneManagerProtocolProtos.AllocateBlockResponse;
import org.apache.hadoop.ozone.protocol.proto.OzoneManagerProtocolProtos.KeyArgs;
import org.apache.hadoop.ozone.protocol.proto.OzoneManagerProtocolProtos.OMRequest;
import org.apache.hadoop.ozone.protocol.proto.OzoneManagerProtocolProtos.OMResponse;
import org.apache.hadoop.ozone.security.acl.IAccessAuthorizer;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import static org.apache.hadoop.ozone.om.exceptions.OMException.ResultCodes.KEY_NOT_FOUND;
import static org.apache.hadoop.ozone.om.lock.OzoneManagerLock.Resource.BUCKET_LOCK;

/**
 * Handles allocate block request - prefix layout.
 */
public class OMAllocateBlockRequestWithFSO extends OMAllocateBlockRequest {

  private static final Logger LOG =
          LoggerFactory.getLogger(OMAllocateBlockRequestWithFSO.class);

  public OMAllocateBlockRequestWithFSO(OMRequest omRequest,
      BucketLayout bucketLayout) {
    super(omRequest, bucketLayout);
  }

  @Override
  public OMClientResponse validateAndUpdateCache(OzoneManager ozoneManager,
      long trxnLogIndex) {

    AllocateBlockRequest allocateBlockRequest =
            getOmRequest().getAllocateBlockRequest();

    KeyArgs keyArgs =
            allocateBlockRequest.getKeyArgs();

    OzoneManagerProtocolProtos.KeyLocation blockLocation =
            allocateBlockRequest.getKeyLocation();
    Preconditions.checkNotNull(blockLocation);

    String volumeName = keyArgs.getVolumeName();
    String bucketName = keyArgs.getBucketName();
    String keyName = keyArgs.getKeyName();
    long clientID = allocateBlockRequest.getClientID();

    OMMetrics omMetrics = ozoneManager.getMetrics();
    omMetrics.incNumBlockAllocateCalls();

    AuditLogger auditLogger = ozoneManager.getAuditLogger();

    Map<String, String> auditMap = buildKeyArgsAuditMap(keyArgs);
    auditMap.put(OzoneConsts.CLIENT_ID, String.valueOf(clientID));

    OMMetadataManager omMetadataManager = ozoneManager.getMetadataManager();
    String openKeyName = null;

    OMResponse.Builder omResponse = OmResponseUtil.getOMResponseBuilder(
            getOmRequest());
    OMClientResponse omClientResponse = null;

    OmKeyInfo openKeyInfo = null;
    Exception exception = null;
    OmBucketInfo omBucketInfo = null;
    boolean acquiredLock = false;

    try {
      keyArgs = resolveBucketLink(ozoneManager, keyArgs, auditMap);
      volumeName = keyArgs.getVolumeName();
      bucketName = keyArgs.getBucketName();

      // check Acl
      checkKeyAclsInOpenKeyTable(ozoneManager, volumeName, bucketName, keyName,
          IAccessAuthorizer.ACLType.WRITE, allocateBlockRequest.getClientID());

      validateBucketAndVolume(omMetadataManager, volumeName,
          bucketName);

      // Here we don't acquire bucket/volume lock because for a single client
      // allocateBlock is called in serial fashion. With this approach, it
      // won't make 'fail-fast' during race condition case on delete/rename op,
      // assuming that later it will fail at the key commit operation.
      openKeyName = getOpenKeyName(volumeName, bucketName, keyName, clientID,
              ozoneManager);
      openKeyInfo = getOpenKeyInfo(omMetadataManager, openKeyName, keyName);
      if (openKeyInfo == null) {
        throw new OMException("Open Key not found " + openKeyName,
                KEY_NOT_FOUND);
      }

      List<OmKeyLocationInfo> newLocationList = Collections.singletonList(
              OmKeyLocationInfo.getFromProtobuf(blockLocation));

      mergeOmLockDetails(
          omMetadataManager.getLock().acquireWriteLock(BUCKET_LOCK,
              volumeName, bucketName));
      acquiredLock = getOmLockDetails().isLockAcquired();
      omBucketInfo = getBucketInfo(omMetadataManager, volumeName, bucketName);
      // check bucket and volume quota
      long preAllocatedKeySize = newLocationList.size()
          * ozoneManager.getScmBlockSize();
      long hadAllocatedKeySize =
          openKeyInfo.getLatestVersionLocations().getLocationList().size()
              * ozoneManager.getScmBlockSize();
      ReplicationConfig repConfig = openKeyInfo.getReplicationConfig();
      long totalAllocatedSpace = QuotaUtil.getReplicatedSize(
          preAllocatedKeySize, repConfig) + QuotaUtil.getReplicatedSize(
          hadAllocatedKeySize, repConfig);
      checkBucketQuotaInBytes(omMetadataManager, omBucketInfo,
          totalAllocatedSpace);
      // Append new block
      openKeyInfo.appendNewBlocks(newLocationList, false);

      // Set modification time.
      openKeyInfo.setModificationTime(keyArgs.getModificationTime());

      // Set the UpdateID to current transactionLogIndex
      openKeyInfo.setUpdateID(trxnLogIndex, ozoneManager.isRatisEnabled());

      // Add to cache.
      addOpenTableCacheEntry(trxnLogIndex, omMetadataManager, openKeyName,
              openKeyInfo);

      omResponse.setAllocateBlockResponse(AllocateBlockResponse.newBuilder()
              .setKeyLocation(blockLocation).build());
      long volumeId = omMetadataManager.getVolumeId(volumeName);
      omClientResponse = getOmClientResponse(clientID, omResponse,
              openKeyInfo, omBucketInfo.copyObject(), volumeId);
      LOG.debug("Allocated block for Volume:{}, Bucket:{}, OpenKey:{}",
              volumeName, bucketName, openKeyName);
    } catch (IOException | InvalidPathException ex) {
      omMetrics.incNumBlockAllocateCallFails();
      exception = ex;
      omClientResponse = new OMAllocateBlockResponseWithFSO(
          createErrorOMResponse(omResponse, exception), getBucketLayout());
      LOG.error("Allocate Block failed. Volume:{}, Bucket:{}, OpenKey:{}. " +
              "Exception:{}", volumeName, bucketName, openKeyName, exception);
    } finally {
      if (acquiredLock) {
        mergeOmLockDetails(
            omMetadataManager.getLock().releaseWriteLock(
                BUCKET_LOCK, volumeName, bucketName));
      }
      if (omClientResponse != null) {
        omClientResponse.setOmLockDetails(getOmLockDetails());
      }
    }

    auditLog(auditLogger, buildAuditMessage(OMAction.ALLOCATE_BLOCK, auditMap,
            exception, getOmRequest().getUserInfo()));

    return omClientResponse;
  }

  private OmKeyInfo getOpenKeyInfo(OMMetadataManager omMetadataManager,
      String openKeyName, String keyName) throws IOException {
    String fileName = OzoneFSUtils.getFileName(keyName);
    return OMFileRequest.getOmKeyInfoFromFileTable(true,
            omMetadataManager, openKeyName, fileName);
  }

  private String getOpenKeyName(String volumeName, String bucketName,
      String keyName, long clientID, OzoneManager ozoneManager)
          throws IOException {
    OMMetadataManager omMetadataManager = ozoneManager.getMetadataManager();
    final long volumeId = omMetadataManager.getVolumeId(volumeName);
    final long bucketId = omMetadataManager.getBucketId(
            volumeName, bucketName);
    String fileName = OzoneFSUtils.getFileName(keyName);
    Iterator<Path> pathComponents = Paths.get(keyName).iterator();
    long parentID = OMFileRequest.getParentID(volumeId, bucketId,
            pathComponents, keyName, omMetadataManager);
    return omMetadataManager.getOpenFileName(volumeId, bucketId, parentID,
            fileName, clientID);
  }

  private void addOpenTableCacheEntry(long trxnLogIndex,
      OMMetadataManager omMetadataManager, String openKeyName,
      OmKeyInfo openKeyInfo) {
    String fileName = openKeyInfo.getFileName();
    OMFileRequest.addOpenFileTableCacheEntry(omMetadataManager, openKeyName,
            openKeyInfo, fileName, trxnLogIndex);
  }

  @NotNull
  private OMClientResponse getOmClientResponse(long clientID,
      OMResponse.Builder omResponse, OmKeyInfo openKeyInfo,
      OmBucketInfo omBucketInfo, long volumeId) {
    return new OMAllocateBlockResponseWithFSO(omResponse.build(), openKeyInfo,
            clientID, getBucketLayout(), volumeId, omBucketInfo.getObjectID());
  }
}
