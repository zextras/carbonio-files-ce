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

  /** Resets all mock expectations to a clean baseline between tests. */
  void reset();
}
