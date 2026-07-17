// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.acceptance.seam.impl;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;

import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.matching.RequestPatternBuilder;
import com.github.tomakehurst.wiremock.stubbing.StubMapping;
import com.zextras.carbonio.files.FilesStackTestResource;
import com.zextras.carbonio.files.acceptance.seam.Mocks;
import com.zextras.carbonio.files.config.FilesConfig;
import com.zextras.carbonio.files.config.TestFilesConfig;
import com.zextras.carbonio.files.rest.InMemoryFilestore;
import com.zextras.carbonio.user_management.sdk.grpc.UserTypeProto;
import com.zextras.filestore.api.Filestore;
import io.quarkus.arc.Arc;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * {@link Mocks} implementation mapping the neutral seam calls onto the Quarkus test stack:
 *
 * <ul>
 *   <li>storages → the in-memory {@link InMemoryFilestore} CDI bean (flags/seed/verify);
 *   <li>user-management → the mutable in-process gRPC stub ({@link
 *       FilesStackTestResource#getUserManagementService()});
 *   <li>preview / mailbox → the dedicated preview/mailbox WireMock ({@link
 *       FilesStackTestResource#getPreviewMailboxWireMock()});
 *   <li>service-discover (Consul) → the Consul WireMock ({@link FilesStackTestResource#getWireMock()});
 *   <li>FilesConfig tunables → the mutable {@link TestFilesConfig} {@code @Mock} bean.
 * </ul>
 *
 * <p>The Guice+Netty original ({@code GuiceNettyMocks}) drove MockServer + {@code MockFilesConfig}
 * through the {@code Simulator}; this is the same contract over the Quarkus stack.
 */
class QuarkusMocks implements Mocks {

  /** id -> request pattern, so {@link #verifyPreviewServed} can verify the matching preview call. */
  private final Map<String, RequestPatternBuilder> previewExpectations = new HashMap<>();

  private InMemoryFilestore filestore() {
    return (InMemoryFilestore) Arc.container().instance(Filestore.class).get();
  }

  private TestFilesConfig filesConfig() {
    return (TestFilesConfig) Arc.container().instance(FilesConfig.class).get();
  }

  private com.github.tomakehurst.wiremock.WireMockServer previewMailbox() {
    return FilesStackTestResource.getPreviewMailboxWireMock();
  }

  // --------------------------------------------------------------------------------- storages: blob

  @Override
  public void storagesBulkDeleteSucceeds(List<String> failedIds) {
    InMemoryFilestore fs = filestore();
    fs.resetBulkDeleteFailures();
    if (failedIds != null && !failedIds.isEmpty()) {
      fs.failBulkDeleteFor(failedIds.toArray(new String[0]));
    }
  }

  @Override
  public void storagesBulkDeleteFails() {
    filestore().setBulkDeleteAlwaysThrows(true);
  }

  @Override
  public void storagesBulkDeleteReturnsNullResponse() {
    filestore().setBulkDeleteReturnsNull(true);
  }

  @Override
  public void storagesServesBlob(String nodeId, int version) {
    // Serve EXACTLY (nodeId + version) bytes, matching the legacy StoragesMockHelper#getBlob and the
    // size the acceptance tests seed on the node (sizeFor == (nodeId+version).length). A mismatched
    // length would make the download's Content-Length disagree with the streamed body and hang the
    // client until timeout.
    filestore().seedBlob(nodeId, version, (nodeId + version).getBytes(StandardCharsets.UTF_8));
  }

  @Override
  public void storagesDownloadConnectionDrops() {
    filestore().setDownloadFails(true);
  }

  @Override
  public void verifyStoragesDownloaded(String nodeId, int version) {
    if (!filestore().wasDownloaded(nodeId, version)) {
      throw new AssertionError(
          "Expected storages download for node=" + nodeId + " version=" + version + " but none");
    }
  }

  @Override
  public void verifyStoragesNeverDownloaded() {
    if (filestore().downloadCount() != 0) {
      throw new AssertionError("Expected no storages download but got " + filestore().downloadCount());
    }
  }

  @Override
  public void storagesUploadSucceeds() {
    InMemoryFilestore fs = filestore();
    fs.setUploadFails(false);
    fs.setUploadSkipsStore(false);
  }

  @Override
  public void storagesUploadFails() {
    filestore().setUploadFails(true);
  }

  @Override
  public void storagesVerifyMissing() {
    filestore().setUploadSkipsStore(true);
  }

  @Override
  public void storagesCopySucceeds() {
    filestore().setCopyFails(false);
  }

  @Override
  public void storagesCopyFails() {
    filestore().setCopyFails(true);
  }

  @Override
  public void verifyStoragesUploaded(String nodeId, int version) {
    if (!filestore().has(nodeId, version)) {
      throw new AssertionError(
          "Expected storages upload for node=" + nodeId + " version=" + version + " but none");
    }
  }

  // --------------------------------------------------------------------------------- storages: health

  @Override
  public void storagesLive() {
    filestore().setLiveness(Filestore.Liveness.OK);
  }

  @Override
  public void storagesUnreachable() {
    filestore().setLiveness(Filestore.Liveness.ERROR);
  }

  // ------------------------------------------------------------------------------- config tunables

  @Override
  public void setNotificationsEnabled(boolean enabled) {
    filesConfig().setAreNotificationsEnabled(enabled);
  }

  @Override
  public void setMaxUploadableSizeMb(Integer maxSizeMb) {
    filesConfig().setMaxUploadableSizeMb(maxSizeMb);
  }

  @Override
  public void setMaxDownloadableSizeMb(Integer maxSizeMb) {
    filesConfig().setMaxDownloadableSizeMb(maxSizeMb);
  }

  @Override
  public void setMaxNumberOfVersions(Integer maxVersions) {
    filesConfig().setMaxNumberOfVersions(maxVersions);
  }

  // -------------------------------------------------------------------------------- user-management

  @Override
  public void userManagementDown() {
    FilesStackTestResource.getUserManagementService().setDown(true);
  }

  @Override
  public void registerUser(
      String cookie, String userId, String status, boolean isGuest, boolean filesFeatureEnabled) {
    FilesStackTestResource.getUserManagementService()
        .registerToken(
            cookie,
            userId,
            status,
            isGuest ? UserTypeProto.GUEST : UserTypeProto.INTERNAL,
            filesFeatureEnabled);
  }

  // --------------------------------------------------------------------------------- preview / mailbox

  @Override
  public String previewServes(
      String pathEndpoint, String fileOwnerId, byte[] content, String mediaType) {
    var mappingBuilder =
        get(urlPathEqualTo(pathEndpoint))
            .atPriority(5)
            .withQueryParam("service_type", equalTo("files"))
            .withHeader("FileOwnerId", equalTo(fileOwnerId));
    if (pathEndpoint.contains("document")) {
      mappingBuilder = mappingBuilder.withQueryParam("lang_tag", equalTo("en"));
    }
    mappingBuilder =
        mappingBuilder.willReturn(
            aResponse().withStatus(200).withHeader("Content-Type", mediaType).withBody(content));
    StubMapping stub = previewMailbox().stubFor(mappingBuilder);

    RequestPatternBuilder verify =
        getRequestedFor(urlPathEqualTo(pathEndpoint))
            .withQueryParam("service_type", equalTo("files"))
            .withHeader("FileOwnerId", equalTo(fileOwnerId));
    if (pathEndpoint.contains("document")) {
      verify = verify.withQueryParam("lang_tag", equalTo("en"));
    }
    String id = stub.getId().toString();
    previewExpectations.put(id, verify);
    return id;
  }

  @Override
  public void verifyPreviewServed(String expectationId) {
    RequestPatternBuilder pattern = previewExpectations.remove(expectationId);
    if (pattern == null) {
      throw new AssertionError("Unknown preview expectation id: " + expectationId);
    }
    previewMailbox().verify(pattern);
  }

  @Override
  public void previewReady() {
    previewMailbox()
        .stubFor(
            get(urlPathEqualTo("/health/ready/")).atPriority(5).willReturn(aResponse().withStatus(200)));
  }

  @Override
  public void previewFails(String pathEndpoint) {
    var mappingBuilder =
        get(urlPathEqualTo(pathEndpoint)).atPriority(5).withQueryParam("service_type", equalTo("files"));
    if (pathEndpoint.contains("document")) {
      mappingBuilder = mappingBuilder.withQueryParam("lang_tag", equalTo("en"));
    }
    previewMailbox().stubFor(mappingBuilder.willReturn(aResponse().withStatus(500)));
  }

  @Override
  public void docsConnectorLive() {
    previewMailbox()
        .stubFor(
            get(urlPathEqualTo("/q/health/live")).atPriority(5).willReturn(aResponse().withStatus(200)));
  }

  @Override
  public void mailboxAccepts(String attachmentId) {
    previewMailbox()
        .stubFor(
            post(WireMock.urlPathMatching("/service/upload.*"))
                .atPriority(5)
                .willReturn(aResponse().withStatus(200).withBody("200,'null','" + attachmentId + "'")));
  }

  @Override
  public void mailboxDown() {
    previewMailbox()
        .stubFor(
            post(WireMock.urlPathMatching("/service/upload.*"))
                .atPriority(5)
                .willReturn(aResponse().withStatus(500)));
  }

  // ------------------------------------------------------------------------------- service-discover

  @Override
  public void serviceDiscoverReturns(String key, String rawValue) {
    // NOTE: on Quarkus, FilesConfig reads a BOOT-TIME Consul KV snapshot, not a live per-call read,
    // so stubbing the KV here does not retroactively change already-snapshotted config. Kept for the
    // few data-fetchers that may re-read live; scenarios depending on live re-read are findings.
    String encoded = Base64.getEncoder().encodeToString(rawValue.getBytes(StandardCharsets.UTF_8));
    FilesStackTestResource.getWireMock()
        .stubFor(
            get(urlPathEqualTo("/v1/kv/carbonio-files/" + key))
                .atPriority(1)
                .willReturn(
                    aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(
                            String.format(
                                "[{\"Key\":\"%s\",\"Value\":\"%s\"}]",
                                "carbonio-files/" + key, encoded))));
  }

  @Override
  public void serviceDiscoverConfigDown(String key) {
    FilesStackTestResource.getWireMock()
        .stubFor(
            get(urlPathEqualTo("/v1/kv/carbonio-files/" + key))
                .atPriority(1)
                .willReturn(
                    aResponse()
                        .withFault(com.github.tomakehurst.wiremock.http.Fault.CONNECTION_RESET_BY_PEER)));
  }

  // ------------------------------------------------------------------------------------------- reset

  @Override
  public void reset() {
    // Mirrors the legacy Simulator#reinitializeMocks scope: reset the external-dependency fakes
    // (storages == filestore controls, preview/mailbox + consul WireMock stubs). Does NOT clear
    // user-management fixtures or FilesConfig overrides (those, like the old MockFilesConfig/gRPC
    // server, live for the app instance and are cleared on close()).
    filestore().resetAcceptanceControls();
    FilesStackTestResource.resetPreviewMailboxStubs();
    FilesStackTestResource.resetConsulStubs();
    previewExpectations.clear();
  }
}
