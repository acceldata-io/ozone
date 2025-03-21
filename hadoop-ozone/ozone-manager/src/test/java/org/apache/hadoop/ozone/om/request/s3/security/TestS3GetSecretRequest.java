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

package org.apache.hadoop.ozone.om.request.s3.security;

import org.apache.hadoop.hdds.conf.OzoneConfiguration;
import org.apache.hadoop.ipc.RPC;
import org.apache.hadoop.ipc.Server;
import org.apache.hadoop.ipc.Server.Call;
import org.apache.hadoop.ozone.audit.AuditLogger;
import org.apache.hadoop.ozone.audit.AuditMessage;
import org.apache.hadoop.ozone.om.OMConfigKeys;
import org.apache.hadoop.ozone.om.OmMetadataManagerImpl;
import org.apache.hadoop.ozone.om.OzoneManager;
import org.apache.hadoop.ozone.om.S3SecretLockedManager;
import org.apache.hadoop.ozone.om.OMMetrics;
import org.apache.hadoop.ozone.om.OMMultiTenantManager;
import org.apache.hadoop.ozone.om.S3SecretManagerImpl;
import org.apache.hadoop.ozone.om.TenantOp;
import org.apache.hadoop.ozone.om.S3SecretCache;
import org.apache.hadoop.ozone.om.multitenant.AuthorizerLockImpl;
import org.apache.hadoop.ozone.om.exceptions.OMException;
import org.apache.hadoop.ozone.om.exceptions.OMException.ResultCodes;
import org.apache.hadoop.ozone.om.helpers.OmDBAccessIdInfo;
import org.apache.hadoop.ozone.om.helpers.S3SecretValue;
import org.apache.hadoop.ozone.om.multitenant.Tenant;
import org.apache.hadoop.ozone.om.request.OMClientRequest;
import org.apache.hadoop.ozone.om.request.s3.tenant.OMTenantAssignUserAccessIdRequest;
import org.apache.hadoop.ozone.om.request.s3.tenant.OMTenantCreateRequest;
import org.apache.hadoop.ozone.om.response.OMClientResponse;
import org.apache.hadoop.ozone.om.response.s3.security.S3GetSecretResponse;
import org.apache.hadoop.ozone.om.response.s3.security.S3RevokeSecretResponse;
import org.apache.hadoop.ozone.om.response.s3.tenant.OMTenantAssignUserAccessIdResponse;
import org.apache.hadoop.ozone.om.response.s3.tenant.OMTenantCreateResponse;
import org.apache.hadoop.ozone.om.s3.S3SecretCacheProvider;
import org.apache.hadoop.ozone.om.upgrade.OMLayoutVersionManager;
import org.apache.hadoop.ozone.protocol.proto.OzoneManagerProtocolProtos;
import org.apache.hadoop.ozone.protocol.proto.OzoneManagerProtocolProtos.CreateTenantRequest;
import org.apache.hadoop.ozone.protocol.proto.OzoneManagerProtocolProtos.GetS3SecretRequest;
import org.apache.hadoop.ozone.protocol.proto.OzoneManagerProtocolProtos.GetS3SecretResponse;
import org.apache.hadoop.ozone.protocol.proto.OzoneManagerProtocolProtos.OMRequest;
import org.apache.hadoop.ozone.protocol.proto.OzoneManagerProtocolProtos.S3Secret;
import org.apache.hadoop.ozone.protocol.proto.OzoneManagerProtocolProtos.TenantAssignUserAccessIdRequest;
import org.apache.hadoop.ozone.protocol.proto.OzoneManagerProtocolProtos.Type;
import org.apache.hadoop.security.UserGroupInformation;
import org.apache.hadoop.security.authentication.util.KerberosName;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.UUID;

import com.google.common.base.Optional;

import static org.apache.hadoop.security.authentication.util.KerberosName.DEFAULT_MECHANISM;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.framework;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

/**
 * Test S3GetSecretRequest.
 */
public class TestS3GetSecretRequest {

  @TempDir
  private Path folder;

  private OzoneManager ozoneManager;
  private OMMetrics omMetrics;
  private AuditLogger auditLogger;
  private OmMetadataManagerImpl omMetadataManager;

  // Multi-tenant related vars
  private static final String USER_ALICE = "alice@EXAMPLE.COM";
  private static final String TENANT_ID = "finance";
  private static final String USER_BOB_SHORT = "bob";
  private static final String ACCESS_ID_BOB =
      OMMultiTenantManager.getDefaultAccessId(TENANT_ID, USER_BOB_SHORT);
  private static final String USER_CAROL = "carol@EXAMPLE.COM";

  private UserGroupInformation ugiAlice;
  private UserGroupInformation ugiCarol;

  private OMMultiTenantManager omMultiTenantManager;
  private Tenant tenant;

  @BeforeEach
  public void setUp() throws Exception {
    KerberosName.setRuleMechanism(DEFAULT_MECHANISM);
    KerberosName.setRules(
        "RULE:[2:$1@$0](.*@EXAMPLE.COM)s/@.*//\n" +
        "RULE:[1:$1@$0](.*@EXAMPLE.COM)s/@.*//\n" +
        "DEFAULT");
    ugiAlice = UserGroupInformation.createRemoteUser(USER_ALICE);
    Assertions.assertEquals("alice", ugiAlice.getShortUserName());

    ugiCarol = UserGroupInformation.createRemoteUser(USER_CAROL);
    Assertions.assertEquals("carol", ugiCarol.getShortUserName());

    ozoneManager = mock(OzoneManager.class);

    Call call = spy(new Call(1, 1, null, null,
        RPC.RpcKind.RPC_BUILTIN, new byte[] {1, 2, 3}));
    // Run as alice, so that Server.getRemoteUser() won't return null.
    when(call.getRemoteUser()).thenReturn(ugiAlice);
    Server.getCurCall().set(call);

    omMetrics = OMMetrics.create();
    OzoneConfiguration conf = new OzoneConfiguration();
    conf.set(OMConfigKeys.OZONE_OM_DB_DIRS,
        folder.toAbsolutePath().toString());
    // No need to conf.set(OzoneConfigKeys.OZONE_ADMINISTRATORS, ...) here
    //  as we did the trick earlier with mockito.
    omMetadataManager = new OmMetadataManagerImpl(conf,
        ozoneManager);
    when(ozoneManager.getMetrics()).thenReturn(omMetrics);
    when(ozoneManager.getMetadataManager()).thenReturn(omMetadataManager);
    when(ozoneManager.isRatisEnabled()).thenReturn(true);
    S3SecretLockedManager secretManager = new S3SecretLockedManager(
            new S3SecretManagerImpl(
                    omMetadataManager,
                    S3SecretCacheProvider.IN_MEMORY.get(conf)
            ),
            omMetadataManager.getLock()
    );
    when(ozoneManager.getS3SecretManager()).thenReturn(secretManager);

    auditLogger = mock(AuditLogger.class);
    when(ozoneManager.getAuditLogger()).thenReturn(auditLogger);
    doNothing().when(auditLogger).logWrite(any(AuditMessage.class));

    // Multi-tenant related initializations
    omMultiTenantManager = mock(OMMultiTenantManager.class);
    tenant = mock(Tenant.class);
    when(ozoneManager.getMultiTenantManager()).thenReturn(omMultiTenantManager);

    when(tenant.getTenantAccessPolicies()).thenReturn(new ArrayList<>());
    when(omMultiTenantManager.getAuthorizerLock())
        .thenReturn(new AuthorizerLockImpl());
    TenantOp authorizerOp = mock(TenantOp.class);
    TenantOp cacheOp = mock(TenantOp.class);
    when(omMultiTenantManager.getAuthorizerOp()).thenReturn(authorizerOp);
    when(omMultiTenantManager.getCacheOp()).thenReturn(cacheOp);

    when(omMultiTenantManager.getTenantForAccessID(USER_CAROL))
        .thenReturn(Optional.absent());
    when(omMultiTenantManager.getTenantForAccessID(USER_ALICE))
        .thenReturn(Optional.absent());
    when(omMultiTenantManager.getTenantForAccessID(ACCESS_ID_BOB))
        .thenReturn(Optional.of(ACCESS_ID_BOB));
  }

  @AfterEach
  public void tearDown() throws Exception {
    omMetrics.unRegister();
    framework().clearInlineMocks();
  }

  private OMRequest createTenantRequest(String tenantNameStr) {

    return OMRequest.newBuilder()
        .setClientId(UUID.randomUUID().toString())
        .setCmdType(Type.CreateTenant)
        .setCreateTenantRequest(
            CreateTenantRequest.newBuilder()
                .setTenantId(tenantNameStr)
                .setVolumeName(tenantNameStr)
                .build()
        ).build();
  }

  private OMRequest assignUserToTenantRequest(
      String tenantNameStr, String userPrincipalStr, String accessIdStr) {

    return OMRequest.newBuilder()
        .setClientId(UUID.randomUUID().toString())
        .setCmdType(Type.TenantAssignUserAccessId)
        .setTenantAssignUserAccessIdRequest(
            TenantAssignUserAccessIdRequest.newBuilder()
                .setTenantId(tenantNameStr)
                .setUserPrincipal(userPrincipalStr)
                .setAccessId(accessIdStr)
                .build()
        ).build();
  }

  private OMRequest s3GetSecretRequest(String userPrincipalStr) {

    return OMRequest.newBuilder()
        .setClientId(UUID.randomUUID().toString())
        .setCmdType(Type.GetS3Secret)
        .setGetS3SecretRequest(
            GetS3SecretRequest.newBuilder()
                .setKerberosID(userPrincipalStr)
                .setCreateIfNotExist(true)
                .build()
        ).build();
  }


  @Test
  public void testS3CacheRecordsTransactionIndex() throws IOException {
    // Create secrets for "alice" and "carol".
    when(ozoneManager.isS3Admin(ugiAlice)).thenReturn(true);
    when(ozoneManager.isS3Admin(ugiCarol)).thenReturn(true);

    String userPrincipalIdAlice = USER_ALICE;
    String userPrincipalIdBob = USER_CAROL;

    // Request the creation of secrets for "alice" and "bob".
    processSuccessSecretRequest(userPrincipalIdAlice, 1, true);
    processSuccessSecretRequest(userPrincipalIdBob, 2, true);

    // Verify that the transaction index is added to the cache for "alice".
    S3SecretCache cache = ozoneManager.getS3SecretManager().cache();
    Assertions.assertNotNull(cache.get(userPrincipalIdAlice));
    Assertions.assertEquals(1,
        cache.get(userPrincipalIdAlice).getTransactionLogIndex());

    // Verify that the transaction index is added to the cache for "bob".
    Assertions.assertNotNull(cache.get(userPrincipalIdBob));
    Assertions.assertEquals(2,
        cache.get(userPrincipalIdBob).getTransactionLogIndex());
  }

  @Test
  public void testFetchSecretForRevokedUser() throws IOException {
    // Create a secret for "alice".
    // This effectively makes alice an S3 admin.
    when(ozoneManager.isS3Admin(ugiAlice)).thenReturn(true);

    String userPrincipalId = USER_ALICE;

    // Request the creation of a secret.
    S3Secret originalS3Secret =
        processSuccessSecretRequest(userPrincipalId, 1, true);

    // Revoke the secret.
    String kerberosID = originalS3Secret.getKerberosID();
    OzoneManagerProtocolProtos.RevokeS3SecretRequest revokeS3SecretRequest =
        OzoneManagerProtocolProtos.RevokeS3SecretRequest.newBuilder()
            .setKerberosID(kerberosID)
            .build();
    OMRequest revokeRequest = OMRequest.newBuilder()
        .setRevokeS3SecretRequest(revokeS3SecretRequest)
        .setClientId(UUID.randomUUID().toString())
        .setCmdType(Type.RevokeS3Secret)
        .build();
    OMClientRequest omRevokeRequest = new S3RevokeSecretRequest(revokeRequest);

    // Pre-execute the revoke request.
    omRevokeRequest.preExecute(ozoneManager);

    // Validate and update cache to revoke the secret.
    OMClientResponse omRevokeResponse = omRevokeRequest.validateAndUpdateCache(
        ozoneManager, 2);

    // Verify that the revoke operation was successful.
    Assertions.assertTrue(omRevokeResponse instanceof S3RevokeSecretResponse);
    S3RevokeSecretResponse s3RevokeSecretResponse =
        (S3RevokeSecretResponse) omRevokeResponse;
    Assertions.assertEquals(OzoneManagerProtocolProtos.Status.OK.getNumber(),
        s3RevokeSecretResponse.getOMResponse().getStatus().getNumber());

    // Fetch the revoked secret and verify its value is null.
    S3GetSecretRequest s3GetSecretRequest = new S3GetSecretRequest(
        new S3GetSecretRequest(s3GetSecretRequest(userPrincipalId)).preExecute(
            ozoneManager)
    );
    S3SecretValue s3SecretValue = omMetadataManager.getSecret(kerberosID);
    Assertions.assertNull(s3SecretValue);

    // Verify that the secret for revoked user will be set to a new one upon
    // calling getSecret request.
    OMClientResponse omClientResponse =
        s3GetSecretRequest.validateAndUpdateCache(ozoneManager, 3);

    Assertions.assertTrue(omClientResponse instanceof S3GetSecretResponse);
    S3GetSecretResponse s3GetSecretResponse =
        (S3GetSecretResponse) omClientResponse;

    // Compare the old secret value and new secret value after revoking;
    // they should not be the same.
    Assertions.assertNotEquals(originalS3Secret,
        s3GetSecretResponse.getS3SecretValue().getAwsSecret());
  }

  @Test
  public void testGetSecretOfAnotherUserAsAdmin() throws IOException {

    // This effectively makes alice an S3 admin.
    when(ozoneManager.isS3Admin(ugiAlice)).thenReturn(true);

    processSuccessSecretRequest(USER_CAROL, 1, true);
  }

  @Test
  public void testGetOwnSecretAsNonAdmin() throws IOException {

    // This effectively makes alice a regular user.
    when(ozoneManager.isS3Admin(ugiAlice)).thenReturn(false);

    // 2. Get secret of "alice" first time.
    S3Secret s3Secret1 = processSuccessSecretRequest(
        USER_ALICE, 1, true);

    Assertions.assertNotNull(s3Secret1);

    // 2. Get secret of "alice" (as herself) again.
    s3Secret1 = processSuccessSecretRequest(
        USER_ALICE, 2, false);

    // no secret is returned as secret already exists in the DB
    Assertions.assertNull(s3Secret1);
  }

  @Test
  public void testGetSecretOfAnotherUserAsNonAdmin() throws IOException {

    // This effectively makes alice a regular user.
    when(ozoneManager.isS3Admin(ugiAlice)).thenReturn(false);

    // Get secret of "carol@EXAMPLE.COM" (as another regular user).
    // Run preExecute, expect USER_MISMATCH
    processFailedSecretRequest(USER_CAROL);
  }

  @Test
  public void testGetSecretOfAnotherUserAsS3Admin() throws IOException {
    // This effectively makes alice an S3 admin.
    when(ozoneManager.isS3Admin(ugiAlice)).thenReturn(true);

    processSuccessSecretRequest(USER_CAROL, 1, true);
  }

  @Test
  public void testGetSecretOfAnotherUserAsOzoneAdmin() throws IOException {
    // This effectively makes alice an admin but not S3 admin.
    when(ozoneManager.isAdmin(ugiAlice)).thenReturn(true);
    when(ozoneManager.isS3Admin(ugiAlice)).thenReturn(false);

    processSuccessSecretRequest(USER_ALICE, 1, true);
    processFailedSecretRequest(USER_CAROL);
    processSuccessSecretRequest(USER_ALICE, 2, false);
  }

  @Test
  public void testGetOwnSecretTwice() throws IOException {

    // This effectively makes alice an S3 Admin.
    when(ozoneManager.isS3Admin(ugiAlice)).thenReturn(true);
    String userPrincipalId = USER_ALICE;

    S3GetSecretRequest s3GetSecretRequest =
        new S3GetSecretRequest(
            new S3GetSecretRequest(
                s3GetSecretRequest(userPrincipalId)
            ).preExecute(ozoneManager)
        );
    // Run validateAndUpdateCache for the first time
    OMClientResponse omClientResponse1 =
        s3GetSecretRequest.validateAndUpdateCache(ozoneManager, 1);
    // Check response type and cast
    Assertions.assertTrue(omClientResponse1 instanceof S3GetSecretResponse);
    final S3GetSecretResponse s3GetSecretResponse1 =
        (S3GetSecretResponse) omClientResponse1;
    // Secret is returned the first time
    final S3SecretValue s3SecretValue1 =
        s3GetSecretResponse1.getS3SecretValue();
    Assertions.assertEquals(userPrincipalId, s3SecretValue1.getKerberosID());
    final String awsSecret1 = s3SecretValue1.getAwsSecret();
    Assertions.assertNotNull(awsSecret1);

    final GetS3SecretResponse getS3SecretResponse1 =
        s3GetSecretResponse1.getOMResponse().getGetS3SecretResponse();
    // The secret inside should be the same.
    final S3Secret s3Secret2 = getS3SecretResponse1.getS3Secret();
    Assertions.assertEquals(userPrincipalId, s3Secret2.getKerberosID());

    // Run validateAndUpdateCache for the second time
    OMClientResponse omClientResponse2 =
        s3GetSecretRequest.validateAndUpdateCache(ozoneManager, 2);
    // Check response type and cast
    Assertions.assertTrue(omClientResponse2 instanceof S3GetSecretResponse);
    final S3GetSecretResponse s3GetSecretResponse2 =
        (S3GetSecretResponse) omClientResponse2;
    // no secret is returned as it is the second time
    Assertions.assertNull(s3GetSecretResponse2.getS3SecretValue());
  }

  @Test
  public void testGetSecretWithTenant() throws IOException {

    // This effectively makes alice an S3 admin.
    when(ozoneManager.isS3Admin(ugiAlice)).thenReturn(true);
    // Make alice a non-delegated admin
    when(omMultiTenantManager.isTenantAdmin(ugiAlice, TENANT_ID, false))
        .thenReturn(true);

    // Init LayoutVersionManager to prevent NPE in checkLayoutFeature
    final OMLayoutVersionManager lvm =
        new OMLayoutVersionManager(OMLayoutVersionManager.maxLayoutVersion());
    when(ozoneManager.getVersionManager()).thenReturn(lvm);

    // 1. CreateTenantRequest: Create tenant "finance".
    long txLogIndex = 1;
    // Run preExecute
    OMTenantCreateRequest omTenantCreateRequest =
        new OMTenantCreateRequest(
            new OMTenantCreateRequest(
                createTenantRequest(TENANT_ID)
            ).preExecute(ozoneManager)
        );
    // Run validateAndUpdateCache
    OMClientResponse omClientResponse =
        omTenantCreateRequest.validateAndUpdateCache(ozoneManager, txLogIndex);
    // Check response type and cast
    Assertions.assertTrue(omClientResponse instanceof OMTenantCreateResponse);
    final OMTenantCreateResponse omTenantCreateResponse =
        (OMTenantCreateResponse) omClientResponse;
    // Check response
    Assertions.assertTrue(omTenantCreateResponse.getOMResponse().getSuccess());
    Assertions.assertEquals(TENANT_ID,
        omTenantCreateResponse.getOmDBTenantState().getTenantId());


    // 2. AssignUserToTenantRequest: Assign "bob@EXAMPLE.COM" to "finance".
    ++txLogIndex;

    // Additional mock setup needed to pass accessId check
    when(ozoneManager.getMultiTenantManager()).thenReturn(omMultiTenantManager);

    // Run preExecute
    OMTenantAssignUserAccessIdRequest omTenantAssignUserAccessIdRequest =
        new OMTenantAssignUserAccessIdRequest(
            new OMTenantAssignUserAccessIdRequest(
                assignUserToTenantRequest(TENANT_ID,
                    USER_BOB_SHORT, ACCESS_ID_BOB)
            ).preExecute(ozoneManager)
        );

    when(omMultiTenantManager.getTenantVolumeName(TENANT_ID))
        .thenReturn(TENANT_ID);
    // Run validateAndUpdateCache
    omClientResponse =
        omTenantAssignUserAccessIdRequest.validateAndUpdateCache(ozoneManager, txLogIndex);

    // Check response type and cast
    Assertions.assertTrue(
        omClientResponse instanceof OMTenantAssignUserAccessIdResponse);
    final OMTenantAssignUserAccessIdResponse
        omTenantAssignUserAccessIdResponse =
        (OMTenantAssignUserAccessIdResponse) omClientResponse;

    // Check response - successful as secret is created for the first time
    Assertions.assertTrue(omTenantAssignUserAccessIdResponse.getOMResponse()
        .getSuccess());
    Assertions.assertTrue(omTenantAssignUserAccessIdResponse.getOMResponse()
        .hasTenantAssignUserAccessIdResponse());
    final OmDBAccessIdInfo omDBAccessIdInfo =
        omTenantAssignUserAccessIdResponse.getOmDBAccessIdInfo();
    Assertions.assertNotNull(omDBAccessIdInfo);
    final S3SecretValue originalS3Secret =
        omTenantAssignUserAccessIdResponse.getS3Secret();
    Assertions.assertNotNull(originalS3Secret);


    // 3. S3GetSecretRequest: Get secret of "bob@EXAMPLE.COM" (as an admin).
    ++txLogIndex;

    // Run preExecute
    S3GetSecretRequest s3GetSecretRequest =
        new S3GetSecretRequest(
            new S3GetSecretRequest(
                s3GetSecretRequest(ACCESS_ID_BOB)
            ).preExecute(ozoneManager)
        );

    // Run validateAndUpdateCache
    omClientResponse =
        s3GetSecretRequest.validateAndUpdateCache(ozoneManager, txLogIndex);

    // Check response type and cast
    Assertions.assertTrue(omClientResponse instanceof S3GetSecretResponse);
    final S3GetSecretResponse s3GetSecretResponse =
        (S3GetSecretResponse) omClientResponse;

    // Check response
    Assertions.assertTrue(s3GetSecretResponse.getOMResponse().getSuccess());
    /*
       getS3SecretValue() should be null in this case because
       the entry is already inserted to DB in the previous request.
       The entry will get overwritten if it isn't null.
       See {@link S3GetSecretResponse#addToDBBatch}.
     */
    Assertions.assertNull(s3GetSecretResponse.getS3SecretValue());
  }


  private S3Secret processSuccessSecretRequest(
      String userPrincipalId,
      int txLogIndex,
      boolean shouldHaveResponse) throws IOException {
    S3GetSecretRequest s3GetSecretRequest =
        new S3GetSecretRequest(
            new S3GetSecretRequest(
                s3GetSecretRequest(userPrincipalId)
            ).preExecute(ozoneManager)
        );

    // Run validateAndUpdateCache
    OMClientResponse omClientResponse =
        s3GetSecretRequest.validateAndUpdateCache(ozoneManager, txLogIndex);

    // Check response type and cast
    Assertions.assertTrue(omClientResponse instanceof S3GetSecretResponse);
    final S3GetSecretResponse s3GetSecretResponse =
        (S3GetSecretResponse) omClientResponse;

    // Check response
    if (shouldHaveResponse) {
      // Check response
      final S3SecretValue s3SecretValue =
          s3GetSecretResponse.getS3SecretValue();
      Assertions.assertEquals(userPrincipalId, s3SecretValue.getKerberosID());
      final String awsSecret1 = s3SecretValue.getAwsSecret();
      Assertions.assertNotNull(awsSecret1);

      final GetS3SecretResponse getS3SecretResponse =
          s3GetSecretResponse.getOMResponse().getGetS3SecretResponse();
      // The secret inside should be the same.
      final S3Secret s3Secret = getS3SecretResponse.getS3Secret();
      Assertions.assertEquals(userPrincipalId, s3Secret.getKerberosID());
      return s3Secret;
    } else {
      Assertions.assertNull(s3GetSecretResponse.getS3SecretValue());
    }
    return null;

  }

  private void processFailedSecretRequest(String userPrincipalId)
      throws IOException {
    try {
      new S3GetSecretRequest(
          s3GetSecretRequest(userPrincipalId)
      ).preExecute(ozoneManager);
    } catch (OMException omEx) {
      Assertions.assertEquals(ResultCodes.USER_MISMATCH, omEx.getResult());
      return;
    }

    Assertions.fail("Should have thrown OMException because alice should not " +
        "have the permission to get bob's secret in this test case!");
  }
}
