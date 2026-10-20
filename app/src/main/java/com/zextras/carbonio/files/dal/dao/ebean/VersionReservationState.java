// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.dal.dao.ebean;

/**
 * Lifecycle of a {@link VersionReservation}: confirmable while PENDING, owned by the purge once
 * PURGING.
 */
public enum VersionReservationState {
  PENDING,
  PURGING
}
