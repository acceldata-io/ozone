/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with this
 * work for additional information regarding copyright ownership.  The ASF
 * licenses this file to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package org.apache.hadoop.ozone;

import java.io.File;
import java.io.IOException;
import java.nio.file.Paths;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.cert.CertificateExpiredException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

import org.apache.hadoop.fs.FileUtil;
import org.apache.hadoop.hdds.DFSConfigKeysLegacy;
import org.apache.hadoop.hdds.HddsConfigKeys;
import org.apache.hadoop.hdds.conf.OzoneConfiguration;
import org.apache.hadoop.hdds.protocol.DatanodeDetails;
import org.apache.hadoop.hdds.protocol.MockDatanodeDetails;
import org.apache.hadoop.hdds.protocol.proto.SCMSecurityProtocolProtos;
import org.apache.hadoop.hdds.protocolPB.SCMSecurityProtocolClientSideTranslatorPB;
import org.apache.hadoop.hdds.security.SecurityConfig;
import org.apache.hadoop.hdds.security.x509.certificate.client.DNCertificateClient;
import org.apache.hadoop.hdds.security.x509.certificate.utils.CertificateCodec;
import org.apache.hadoop.hdds.security.x509.certificate.utils.SelfSignedCertificate;
import org.apache.hadoop.hdds.security.x509.keys.KeyCodec;
import org.apache.hadoop.security.ssl.KeyStoreTestUtil;
import org.apache.ozone.test.GenericTestUtils;
import org.apache.hadoop.util.ServicePlugin;

import org.apache.commons.io.FileUtils;

import static org.apache.hadoop.hdds.HddsConfigKeys.HDDS_X509_CA_ROTATION_ACK_TIMEOUT;
import static org.apache.hadoop.hdds.HddsConfigKeys.HDDS_X509_CA_ROTATION_CHECK_INTERNAL;
import static org.apache.hadoop.hdds.HddsConfigKeys.HDDS_X509_GRACE_DURATION_TOKEN_CHECKS_ENABLED;
import static org.apache.hadoop.hdds.HddsConfigKeys.HDDS_X509_RENEW_GRACE_DURATION;
import static org.apache.hadoop.ozone.OzoneConfigKeys.OZONE_SECURITY_ENABLED_KEY;
import static org.mockito.ArgumentMatchers.anyObject;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.apache.ozone.test.tag.Flaky;
import org.bouncycastle.cert.X509CertificateHolder;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Test class for {@link HddsDatanodeService}.
 */
public class TestHddsSecureDatanodeInit {
  private static File testDir;
  private static OzoneConfiguration conf;
  private static HddsDatanodeService service;
  private static String[] args = new String[]{};
  private static PrivateKey privateKey;
  private static PublicKey publicKey;
  private static GenericTestUtils.LogCapturer dnLogs;
  private static SecurityConfig securityConfig;
  private static KeyCodec keyCodec;
  private static CertificateCodec certCodec;
  private static X509CertificateHolder certHolder;
  private static final String DN_COMPONENT = DNCertificateClient.COMPONENT_NAME;
  private static final int CERT_LIFETIME = 15; // seconds

  private DNCertificateClient client;
  private static DatanodeDetails datanodeDetails;
  private static SCMSecurityProtocolClientSideTranslatorPB scmClient;

  @BeforeAll
  public static void setUp() throws Exception {
    testDir = GenericTestUtils.getRandomizedTestDir();
    conf = new OzoneConfiguration();
    conf.set(HddsConfigKeys.OZONE_METADATA_DIRS, testDir.getPath());
    //conf.set(ScmConfigKeys.OZONE_SCM_NAMES, "localhost");
    String volumeDir = testDir + "/disk1";
    conf.set(DFSConfigKeysLegacy.DFS_DATANODE_DATA_DIR_KEY, volumeDir);

    conf.setBoolean(OZONE_SECURITY_ENABLED_KEY, true);
    conf.setClass(OzoneConfigKeys.HDDS_DATANODE_PLUGINS_KEY,
        TestHddsDatanodeService.MockService.class,
        ServicePlugin.class);
    conf.set(HDDS_X509_RENEW_GRACE_DURATION, "PT5S"); // 5s
    conf.set(HDDS_X509_CA_ROTATION_CHECK_INTERNAL, "PT1S"); // 1s
    conf.setBoolean(HDDS_X509_GRACE_DURATION_TOKEN_CHECKS_ENABLED, false);
    conf.set(HDDS_X509_CA_ROTATION_ACK_TIMEOUT, "PT1S"); // 1s
    securityConfig = new SecurityConfig(conf);

    service = new HddsDatanodeService(args) {
      @Override
      SCMSecurityProtocolClientSideTranslatorPB createScmSecurityClient()
          throws IOException {
        return mock(SCMSecurityProtocolClientSideTranslatorPB.class);
      }
    };
    callQuietly(() -> {
      service.start(conf);
      return null;
    });
    callQuietly(() -> {
      service.initializeCertificateClient(service.getCertificateClient());
      return null;
    });
    dnLogs = GenericTestUtils.LogCapturer.captureLogs(
        ((DNCertificateClient)service.getCertificateClient()).getLogger());
    certCodec = new CertificateCodec(securityConfig, DN_COMPONENT);
    keyCodec = new KeyCodec(securityConfig, DN_COMPONENT);
    dnLogs.clearOutput();
    privateKey = service.getCertificateClient().getPrivateKey();
    publicKey = service.getCertificateClient().getPublicKey();

    certHolder = generateX509CertHolder(new KeyPair(publicKey, privateKey),
        null, Duration.ofSeconds(CERT_LIFETIME));
    datanodeDetails = MockDatanodeDetails.randomDatanodeDetails();

    scmClient = mock(SCMSecurityProtocolClientSideTranslatorPB.class);
  }

  @AfterAll
  public static void tearDown() {
    FileUtil.fullyDelete(testDir);
  }

  @BeforeEach
  public void setUpDNCertClient() throws IOException {

    FileUtils.deleteQuietly(Paths.get(
        securityConfig.getKeyLocation(DN_COMPONENT).toString(),
        securityConfig.getPrivateKeyFileName()).toFile());
    FileUtils.deleteQuietly(Paths.get(
        securityConfig.getKeyLocation(DN_COMPONENT).toString(),
        securityConfig.getPublicKeyFileName()).toFile());
    FileUtils.deleteQuietly(Paths.get(securityConfig
        .getCertificateLocation(DN_COMPONENT).toString(),
        securityConfig.getCertificateFileName()).toFile());
    dnLogs.clearOutput();
    client = new DNCertificateClient(securityConfig, scmClient, datanodeDetails,
        certHolder.getSerialNumber().toString(), id -> { }, null);
  }

  @AfterEach
  public void tearDownClient() throws IOException {
    client.close();
  }

  @Test
  public void testSecureDnStartupCase0() {

    // Case 0: When keypair as well as certificate is missing. Initial keypair
    // boot-up. Get certificate will fail as no SCM is not running.
    Assertions.assertThrows(Exception.class,
        () -> service.initializeCertificateClient(client));

    Assertions.assertNotNull(client.getPrivateKey());
    Assertions.assertNotNull(client.getPublicKey());
    Assertions.assertNull(client.getCertificate());
    Assertions.assertTrue(dnLogs.getOutput()
        .contains("Init response: GETCERT"));
  }

  @Test
  public void testSecureDnStartupCase1() throws Exception {
    // Case 1: When only certificate is present.
    certCodec.writeCertificate(certHolder);
    RuntimeException rteException = Assertions.assertThrows(
        RuntimeException.class,
        () -> service.initializeCertificateClient(client));
    Assertions.assertTrue(rteException.getMessage()
        .contains("DN security initialization failed"));
    Assertions.assertNull(client.getPrivateKey());
    Assertions.assertNull(client.getPublicKey());
    Assertions.assertNotNull(client.getCertificate());
    Assertions.assertTrue(dnLogs.getOutput()
        .contains("Init response: FAILURE"));
  }

  @Test
  public void testSecureDnStartupCase2() throws Exception {
    // Case 2: When private key and certificate is missing.
    keyCodec.writePublicKey(publicKey);
    RuntimeException rteException = Assertions.assertThrows(
        RuntimeException.class,
        () -> service.initializeCertificateClient(client));
    Assertions.assertTrue(rteException.getMessage()
        .contains("DN security initialization failed"));
    Assertions.assertNull(client.getPrivateKey());
    Assertions.assertNotNull(client.getPublicKey());
    Assertions.assertNull(client.getCertificate());
    Assertions.assertTrue(dnLogs.getOutput()
        .contains("Init response: FAILURE"));
  }

  @Test
  public void testSecureDnStartupCase3() throws Exception {
    // Case 3: When only public key and certificate is present.
    keyCodec.writePublicKey(publicKey);
    certCodec.writeCertificate(certHolder);
    RuntimeException rteException = Assertions.assertThrows(
        RuntimeException.class,
        () -> service.initializeCertificateClient(client));
    Assertions.assertTrue(rteException.getMessage()
        .contains("DN security initialization failed"));
    Assertions.assertNull(client.getPrivateKey());
    Assertions.assertNotNull(client.getPublicKey());
    Assertions.assertNotNull(client.getCertificate());
    Assertions.assertTrue(dnLogs.getOutput()
        .contains("Init response: FAILURE"));
  }

  @Test
  public void testSecureDnStartupCase4() throws Exception {
    // Case 4: When public key as well as certificate is missing.
    keyCodec.writePrivateKey(privateKey);
    // provide a new valid SCMGetCertResponseProto
    X509CertificateHolder newCertHolder = generateX509CertHolder(null, null,
        Duration.ofSeconds(CERT_LIFETIME));
    String pemCert = CertificateCodec.getPEMEncodedString(newCertHolder);
    // provide an invalid SCMGetCertResponseProto. Without
    // setX509CACertificate(pemCert), signAndStoreCert will throw exception.
    SCMSecurityProtocolProtos.SCMGetCertResponseProto responseProto =
        SCMSecurityProtocolProtos.SCMGetCertResponseProto
            .newBuilder().setResponseCode(SCMSecurityProtocolProtos
                .SCMGetCertResponseProto.ResponseCode.success)
            .setX509Certificate(pemCert)
            .setX509CACertificate(pemCert)
            .build();
    when(scmClient.getDataNodeCertificateChain(anyObject(), anyString()))
        .thenReturn(responseProto);
    service.initializeCertificateClient(client);
    Assertions.assertNotNull(client.getPrivateKey());
    Assertions.assertNotNull(client.getPublicKey());
    Assertions.assertNotNull(client.getCertificate());
    Assertions.assertTrue(dnLogs.getOutput()
        .contains("Init response: GETCERT"));
    dnLogs.clearOutput();
    // reset scmClient behavior
    when(scmClient.getDataNodeCertificateChain(anyObject(), anyString()))
        .thenReturn(null);
  }

  @Test
  public void testSecureDnStartupCase5() throws Exception {
    // Case 5: If private key and certificate is present.
    certCodec.writeCertificate(certHolder);
    keyCodec.writePrivateKey(privateKey);
    service.initializeCertificateClient(client);
    Assertions.assertNotNull(client.getPrivateKey());
    Assertions.assertNotNull(client.getPublicKey());
    Assertions.assertNotNull(client.getCertificate());
    Assertions.assertTrue(dnLogs.getOutput()
        .contains("Init response: SUCCESS"));
  }

  @Test
  public void testSecureDnStartupCase6() throws Exception {
    // Case 6: If key pair already exist than response should be GETCERT.
    keyCodec.writePublicKey(publicKey);
    keyCodec.writePrivateKey(privateKey);
    Assertions.assertThrows(Exception.class,
        () -> service.initializeCertificateClient(client));
    Assertions.assertNotNull(client.getPrivateKey());
    Assertions.assertNotNull(client.getPublicKey());
    Assertions.assertNull(client.getCertificate());
    Assertions.assertTrue(dnLogs.getOutput()
        .contains("Init response: GETCERT"));
  }

  @Test
  public void testSecureDnStartupCase7() throws Exception {
    // Case 7 When keypair and certificate is present.
    keyCodec.writePublicKey(publicKey);
    keyCodec.writePrivateKey(privateKey);
    certCodec.writeCertificate(certHolder);

    service.initializeCertificateClient(client);
    Assertions.assertNotNull(client.getPrivateKey());
    Assertions.assertNotNull(client.getPublicKey());
    Assertions.assertNotNull(client.getCertificate());
    Assertions.assertTrue(dnLogs.getOutput()
        .contains("Init response: SUCCESS"));
  }

  /**
   * Invoke a callable; Ignore all exception.
   * @param closure closure to execute
   * @return
   */
  public static void callQuietly(Callable closure) {
    try {
      closure.call();
    } catch (Throwable e) {
      e.printStackTrace();
      // Ignore all Throwable,
    }
  }

  @Test
  public void testCertificateRotation() throws Exception {
    // save the certificate on dn
    certCodec.writeCertificate(certHolder);

    Duration gracePeriod = securityConfig.getRenewalGracePeriod();
    X509CertificateHolder newCertHolder = generateX509CertHolder(null,
        LocalDateTime.now().plus(gracePeriod),
        Duration.ofSeconds(CERT_LIFETIME));
    String pemCert = CertificateCodec.getPEMEncodedString(newCertHolder);
    SCMSecurityProtocolProtos.SCMGetCertResponseProto responseProto =
        SCMSecurityProtocolProtos.SCMGetCertResponseProto
            .newBuilder().setResponseCode(SCMSecurityProtocolProtos
                .SCMGetCertResponseProto.ResponseCode.success)
            .setX509Certificate(pemCert)
            .setX509CACertificate(pemCert)
            .setX509RootCACertificate(pemCert)
            .build();
    when(scmClient.getDataNodeCertificateChain(anyObject(), anyString()))
        .thenReturn(responseProto);

    List<String> rootCaList = new ArrayList<>();
    rootCaList.add(pemCert);
    when(scmClient.getAllRootCaCertificates()).thenReturn(rootCaList);
    // check that new cert ID should not equal to current cert ID
    String certId = newCertHolder.getSerialNumber().toString();
    Assertions.assertFalse(certId.equals(
        client.getCertificate().getSerialNumber().toString()));

    // start monitor task to renew key and cert
    client.startCertificateRenewerService();

    // check after renew, client will have the new cert ID
    GenericTestUtils.waitFor(() -> {
      String newCertId = client.getCertificate().getSerialNumber().toString();
      return newCertId.equals(certId);
    }, 1000, CERT_LIFETIME * 1000);
    PrivateKey privateKey1 = client.getPrivateKey();
    PublicKey publicKey1 = client.getPublicKey();
    String caCertId1 = client.getCACertificate().getSerialNumber().toString();
    String rootCaCertId1 =
        client.getRootCACertificate().getSerialNumber().toString();

    // test the second time certificate rotation, generate a new cert
    newCertHolder = generateX509CertHolder(null, null,
        Duration.ofSeconds(CERT_LIFETIME));
    rootCaList.remove(pemCert);
    pemCert = CertificateCodec.getPEMEncodedString(newCertHolder);
    responseProto = SCMSecurityProtocolProtos.SCMGetCertResponseProto
        .newBuilder().setResponseCode(SCMSecurityProtocolProtos
            .SCMGetCertResponseProto.ResponseCode.success)
        .setX509Certificate(pemCert)
        .setX509CACertificate(pemCert)
        .setX509RootCACertificate(pemCert)
        .build();
    when(scmClient.getDataNodeCertificateChain(anyObject(), anyString()))
        .thenReturn(responseProto);
    rootCaList.add(pemCert);
    when(scmClient.getAllRootCaCertificates()).thenReturn(rootCaList);
    String certId2 = newCertHolder.getSerialNumber().toString();

    // check after renew, client will have the new cert ID
    GenericTestUtils.waitFor(() -> {
      String newCertId = client.getCertificate().getSerialNumber().toString();
      return newCertId.equals(certId2);
    }, 1000, CERT_LIFETIME * 1000);
    Assertions.assertFalse(client.getPrivateKey().equals(privateKey1));
    Assertions.assertFalse(client.getPublicKey().equals(publicKey1));
    Assertions.assertFalse(client.getCACertificate().getSerialNumber()
        .toString().equals(caCertId1));
    Assertions.assertFalse(client.getRootCACertificate().getSerialNumber()
        .toString().equals(rootCaCertId1));
  }

  /**
   * Test unexpected SCMGetCertResponseProto returned from SCM.
   */
  @Test
  @Flaky("HDDS-8873")
  public void testCertificateRotationRecoverableFailure() throws Exception {
    // save the certificate on dn
    certCodec.writeCertificate(certHolder);

    Duration gracePeriod = securityConfig.getRenewalGracePeriod();
    X509CertificateHolder newCertHolder = generateX509CertHolder(null,
        LocalDateTime.now().plus(gracePeriod),
        Duration.ofSeconds(CERT_LIFETIME));
    String pemCert = CertificateCodec.getPEMEncodedString(newCertHolder);
    // provide an invalid SCMGetCertResponseProto. Without
    // setX509CACertificate(pemCert), signAndStoreCert will throw exception.
    SCMSecurityProtocolProtos.SCMGetCertResponseProto responseProto =
        SCMSecurityProtocolProtos.SCMGetCertResponseProto
            .newBuilder().setResponseCode(SCMSecurityProtocolProtos
                .SCMGetCertResponseProto.ResponseCode.success)
            .setX509Certificate(pemCert)
            .build();
    when(scmClient.getDataNodeCertificateChain(anyObject(), anyString()))
        .thenReturn(responseProto);

    // check that new cert ID should not equal to current cert ID
    String certId = newCertHolder.getSerialNumber().toString();
    Assertions.assertFalse(certId.equals(
        client.getCertificate().getSerialNumber().toString()));

    // start monitor task to renew key and cert
    client.startCertificateRenewerService();

    // certificate failed to renew, client still hold the old expired cert.
    Thread.sleep(CERT_LIFETIME * 1000);
    Assertions.assertFalse(certId.equals(
        client.getCertificate().getSerialNumber().toString()));
    try {
      client.getCertificate().checkValidity();
    } catch (Exception e) {
      Assertions.assertTrue(e instanceof CertificateExpiredException);
    }

    // provide a new valid SCMGetCertResponseProto
    newCertHolder = generateX509CertHolder(null, null,
        Duration.ofSeconds(CERT_LIFETIME));
    pemCert = CertificateCodec.getPEMEncodedString(newCertHolder);
    responseProto = SCMSecurityProtocolProtos.SCMGetCertResponseProto
        .newBuilder().setResponseCode(SCMSecurityProtocolProtos
            .SCMGetCertResponseProto.ResponseCode.success)
        .setX509Certificate(pemCert)
        .setX509CACertificate(pemCert)
        .build();
    when(scmClient.getDataNodeCertificateChain(anyObject(), anyString()))
        .thenReturn(responseProto);
    String certId2 = newCertHolder.getSerialNumber().toString();

    // check after renew, client will have the new cert ID
    GenericTestUtils.waitFor(() -> {
      String newCertId = client.getCertificate().getSerialNumber().toString();
      return newCertId.equals(certId2);
    }, 1000, CERT_LIFETIME * 1000);
  }

  private static X509CertificateHolder generateX509CertHolder(KeyPair keyPair,
      LocalDateTime startDate, Duration certLifetime) throws Exception {
    if (keyPair == null) {
      keyPair = KeyStoreTestUtil.generateKeyPair("RSA");
    }
    LocalDateTime start = startDate == null ? LocalDateTime.now() : startDate;
    LocalDateTime end = start.plus(certLifetime);
    return SelfSignedCertificate.newBuilder()
        .setBeginDate(start)
        .setEndDate(end)
        .setClusterID("cluster")
        .setKey(keyPair)
        .setSubject("localhost")
        .setConfiguration(securityConfig)
        .setScmID("test")
        .build();
  }
}
