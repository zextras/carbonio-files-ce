// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.acceptance.seam.impl;

import com.zextras.carbonio.files.Simulator;
import com.zextras.carbonio.files.acceptance.seam.Mocks;
import com.zextras.carbonio.files.utilities.StoragesMockHelper;

import java.util.List;

/**
 * {@link Mocks} implementation delegating to {@link StoragesMockHelper} and the {@link
 * Simulator}'s MockServer clients. {@code org.mockserver.*} types stay confined to this class and
 * to {@link StoragesMockHelper} — never in a test body.
 */
class GuiceNettyMocks implements Mocks {

  private final Simulator simulator;
  private final StoragesMockHelper storagesMockHelper;

  GuiceNettyMocks(Simulator simulator) {
    this.simulator = simulator;
    this.storagesMockHelper = new StoragesMockHelper(simulator.getStoragesMock());
  }

  @Override
  public void storagesBulkDeleteSucceeds(List<String> failedIds) {
    storagesMockHelper.bulkDelete(failedIds);
  }

  @Override
  public void storagesBulkDeleteFails() {
    storagesMockHelper.bulkDeleteError();
  }

  @Override
  public void storagesBulkDeleteReturnsNullResponse() {
    storagesMockHelper.bulkDeleteNullResponse();
  }

  @Override
  public void reset() {
    simulator.reinitializeMocks();
  }
}
