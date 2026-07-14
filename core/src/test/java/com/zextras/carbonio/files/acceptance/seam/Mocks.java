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

  /** Resets all mock expectations to a clean baseline between tests. */
  void reset();
}
