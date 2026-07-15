// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.acceptance.seam;

import java.util.List;

/**
 * Neutral facade over external-dependency fakes (Storages/Preview/DocsConnector/UM). Keeps
 * {@code org.mockserver.*} types out of test bodies — they stay confined to the {@code seam.impl}
 * implementation and to {@code StoragesMockHelper}.
 *
 * <p>Extend this interface with more behavior-named methods as later migrations need additional
 * mocked capabilities (Preview thumbnails, DocsConnector, UM outage, notification toggle, etc.).
 */
public interface Mocks {

  /**
   * Storages bulk-delete succeeds; node ids listed in {@code failedIds} are reported as failed
   * (an empty list means full success). Replaces {@code StoragesMockHelper.bulkDelete(...)}.
   */
  void storagesBulkDeleteSucceeds(List<String> failedIds);

  /**
   * Storages bulk-delete endpoint returns an HTTP 500, simulating a complete PowerStore outage.
   * Replaces {@code StoragesMockHelper.bulkDeleteError()}.
   */
  void storagesBulkDeleteFails();

  /**
   * Storages bulk-delete endpoint returns HTTP 200 with a null/empty ids payload. Replaces
   * {@code StoragesMockHelper.bulkDeleteNullResponse()}.
   */
  void storagesBulkDeleteReturnsNullResponse();

  /**
   * Toggles the notifications feature flag. Replaces {@code ((MockFilesConfig)
   * injector.getInstance(FilesConfig.class)).setAreNotificationsEnabled(bool)} (special case 1 —
   * the 7 notification ITs).
   */
  void setNotificationsEnabled(boolean enabled);

  /**
   * Shuts down the in-process user-management gRPC server, simulating user-management being
   * unreachable so health checks report it as unhealthy. Replaces {@code
   * simulator.shutdownUserManagementServer()} (special case 2 — {@code HealthApiIT}). There is no
   * corresponding "bring back up": once shut down the in-process server is gone for the rest of
   * the test, matching the one-shot usage of the original {@code Simulator} method.
   */
  void userManagementDown();

  /** Storages health endpoint ({@code GET /health/live}) reports live (HTTP 200). */
  void storagesLive();

  /** Storages health endpoint ({@code GET /health/live}) reports unreachable (HTTP 502). */
  void storagesUnreachable();

  /** Preview health endpoint ({@code GET /health/ready/}) reports ready (HTTP 200). */
  void previewReady();

  /** DocsConnector health endpoint ({@code GET /q/health/live}) reports live (HTTP 200). */
  void docsConnectorLive();

  /**
   * Storages will return deterministic bytes for a download of {@code nodeId}/{@code version}.
   * Replaces {@code StoragesMockHelper.getBlob(nodeId, version)} used directly in test bodies.
   */
  void storagesServesBlob(String nodeId, int version);

  /**
   * Storages' download endpoint ({@code GET /download}) drops the connection, simulating a
   * network-level failure (production surfaces this as a 500). Distinct from {@link
   * #storagesBulkDeleteFails()}, which targets the bulk-delete endpoint, not the download one.
   */
  void storagesDownloadConnectionDrops();

  /** Verifies storages' download endpoint was hit exactly once for this {@code nodeId}/{@code version}. */
  void verifyStoragesDownloaded(String nodeId, int version);

  /** Verifies storages' download endpoint was never hit. */
  void verifyStoragesNeverDownloaded();

  /**
   * Preview/thumbnail service will respond at {@code pathEndpoint} with {@code content} typed as
   * {@code mediaType} (e.g. {@code "application/pdf"}, {@code "image/png"}, {@code "image/jpeg"}),
   * tagged with the requesting file's owner id via the {@code FileOwnerId} header the production
   * code sends. Returns an opaque expectation handle for {@link #verifyPreviewServed(String)}.
   */
  String previewServes(String pathEndpoint, String fileOwnerId, byte[] content, String mediaType);

  /** Verifies the preview/thumbnail expectation identified by {@code expectationId} was matched, then clears it. */
  void verifyPreviewServed(String expectationId);

  /**
   * Storages' upload endpoint (both the new-node/new-version POST and the overwrite PUT) succeeds
   * with a syntactically-valid response, AND the post-upload existence check ({@code
   * BlobService#verifyBlobExists}, which re-uses a plain {@code GET /download} since there is no
   * dedicated "exists" endpoint) also succeeds generically for any node id/version. Required by
   * every upload happy-path scenario. Replaces {@code StoragesMockHelper#uploadSucceeds()}.
   */
  void storagesUploadSucceeds();

  /**
   * Storages' upload endpoint returns an HTTP 500, simulating an upload failure (production maps
   * this to a {@code DependencyException} -> 500 and rolls back the node/version DB row). Replaces
   * {@code StoragesMockHelper#uploadFails()}.
   */
  void storagesUploadFails();

  /**
   * The upload itself succeeds but the post-upload existence check reports the blob missing
   * (production's {@code verifyBlobExists} == false -> {@code DependencyException} -> 500, DB row
   * rolled back). Replaces {@code StoragesMockHelper#verifyMissing()}.
   */
  void storagesVerifyMissing();

  /** Storages' copy endpoint ({@code copyFile}/{@code cloneVersion}) succeeds. Replaces {@code StoragesMockHelper#copySucceeds()}. */
  void storagesCopySucceeds();

  /** Storages' copy endpoint returns an HTTP 500, simulating a filestore-copy failure. Replaces {@code StoragesMockHelper#copyFails()}. */
  void storagesCopyFails();

  /** Verifies storages' upload endpoint was hit at least once for this {@code nodeId}/{@code version}. */
  void verifyStoragesUploaded(String nodeId, int version);

  /**
   * Overrides {@code FilesConfig#getMaxUploadableFileSizeInMb()} for the lifetime of this app
   * instance (falls through to the real Service-Discover-backed value when {@code null}). Drives
   * the 413 upload-size-cap path (both {@code BlobController#isRequestSizeOverLimit} for uploads).
   */
  void setMaxUploadableSizeMb(Integer maxSizeMb);

  /**
   * Overrides {@code FilesConfig#getMaxDownloadableFileSizeInMb()} for the lifetime of this app
   * instance (falls through to the real Service-Discover-backed value when {@code null}). Drives
   * the 413 download/zip-size-cap path.
   */
  void setMaxDownloadableSizeMb(Integer maxSizeMb);

  /**
   * Overrides {@code FilesConfig#getMaxNumberOfFileVersion()} for the lifetime of this app
   * instance (falls through to the real Service-Discover-backed value when {@code null}). Drives
   * the 405 version-cap check that {@code BlobService#uploadFileVersion} re-reads on every call.
   *
   * <p><b>Does NOT affect {@code keepVersions}/{@code cloneVersion}'s cap</b> — {@code
   * NodeDataFetcher} reads its own keep-cap directly from Service-Discover ONCE at construction,
   * bypassing {@code FilesConfig} entirely; use the builder's {@code withMaxNumberOfVersions(int)}
   * (set BEFORE {@code build()}) for that path instead.
   */
  void setMaxNumberOfVersions(Integer maxVersions);

  /**
   * Registers (or overwrites) a user-management fixture with explicit status/type/feature-flag
   * attributes, so {@code AuthenticationHandler}'s non-happy-path branches (inactive user, guest
   * user, feature-disabled user) become HTTP-reachable. Requires {@code withUserManagement(...)}
   * to have already been called on the builder (so the in-process UM gRPC server is running); this
   * just adds/replaces one token's fixture on it.
   *
   * @param status a UM status string (e.g. "active", "maintenance", "closed", "locked", ...),
   *     matched case-insensitively by the production mapper.
   * @param isGuest true for a GUEST user, false for INTERNAL.
   * @param filesFeatureEnabled whether "carbonioFeatureFilesEnabled" is present for this user;
   *     when false, {@code AuthenticationHandler} sees it as absent and treats it as "FALSE".
   */
  void registerUser(String cookie, String userId, String status, boolean isGuest, boolean filesFeatureEnabled);

  /**
   * Mailbox's upload endpoint ({@code POST /service/upload?fmt=raw}, used by {@code
   * ProcedureService#uploadToModule} for {@code /upload-to}) accepts the upload and reports back
   * {@code attachmentId} (mirroring the mailbox's real quirky response format).
   */
  void mailboxAccepts(String attachmentId);

  /** Mailbox's upload endpoint is unreachable/erroring, simulating the mailbox being down. */
  void mailboxDown();

  /**
   * Preview/thumbnail service responds at {@code pathEndpoint} with a non-2xx status (HTTP 500),
   * simulating the Preview microservice ITSELF failing — distinct from a permission/mime-type
   * failure (which never reaches the Preview service at all, see {@code
   * PreviewController#checkNodePermissionAndExistence}). Reaches {@code
   * PreviewController#failureResponse} via the {@code previewService.getXxx(...).onFailure(...)}
   * path (production maps this, via the SDK's {@code PreviewException} not being a {@code
   * BadRequestException}, to a {@code NoSuchElementException} -&gt; HTTP 404).
   */
  void previewFails(String pathEndpoint);

  /**
   * Stubs an arbitrary raw ServiceDiscover KV value for {@code carbonio-files/<key>} (e.g. a
   * non-numeric {@code max-number-of-versions}), read live on every call by {@code
   * ConfigDataFetcher}/{@code ServiceDiscoverHttpClient} (unlike {@code NodeDataFetcher}'s
   * construction-time read, this needs no pre-build builder knob). {@code rawValue} is sent
   * verbatim (base64-encoded, matching the real Consul KV response shape) so any string —
   * including a non-numeric one that trips {@code ConfigDataFetcher#updateMaxKeepVersionsValue}'s
   * uncaught {@code Integer.parseInt(...)} — can be exercised.
   */
  void serviceDiscoverReturns(String key, String rawValue);

  /**
   * ServiceDiscover is unreachable (connection-level outage, not merely a missing/unstubbed key)
   * for {@code carbonio-files/<key>}: the request will error before any HTTP status is even read.
   * Distinct from simply never stubbing the key (which MockServer answers with a 404, exercising
   * {@code ServiceDiscoverHttpClient#getConfig}'s "non-200 status" branch, already reachable
   * without this method) — this instead exercises its {@code catch (IOException)} branch.
   */
  void serviceDiscoverConfigDown(String key);

  /** Resets all mock expectations to a clean baseline between tests. */
  void reset();
}
