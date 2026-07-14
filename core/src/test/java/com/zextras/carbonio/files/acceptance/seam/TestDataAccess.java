// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.acceptance.seam;

import com.zextras.carbonio.files.api.utilities.DatabasePopulator;

/**
 * Neutral backdoor for seeding and inspecting state without touching the DI container or the
 * ORM directly. Methods return domain-neutral primitives/DTOs only — NO {@code com.google.inject}
 * or {@code io.ebean} types are exposed here.
 *
 * <p>Every method is named for the behavioral fact it asserts, not for the mechanism. Prefer
 * asserting via the API (a GraphQL read-back) wherever the public contract can observe the
 * state; use these inspectors only where API-level observation is impossible (e.g. tombstones,
 * purge internals). Add narrow, behavior-named accessors as later migrations discover real
 * needs — do not expose a raw repository or an Injector here.
 */
public interface TestDataAccess {

  /** Fluent seeding — wraps the existing {@link DatabasePopulator}. */
  DatabasePopulator populator();

  /** Resets the DB to a clean baseline between tests (wraps {@code Simulator.resetDatabase()}). */
  void resetDatabase();

  /** True if a node row still exists. Replaces {@code nodeRepository.getNode(id).isPresent()}. */
  boolean nodeExists(String nodeId);

  /**
   * Number of tombstone rows currently in the database. There is no public API to read these;
   * replaces {@code tombstoneRepository.getTombstones().size()}.
   */
  int tombstoneCount();

  /**
   * Deletes all tombstone rows. Tombstones are not FK-linked to a node, so {@link
   * #resetDatabase()} alone does not clean them up between tests.
   */
  void clearTombstones();
}
