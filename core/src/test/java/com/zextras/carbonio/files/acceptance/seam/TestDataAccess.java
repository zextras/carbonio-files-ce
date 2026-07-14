// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.acceptance.seam;

import com.zextras.carbonio.files.api.utilities.DatabasePopulator;

import java.util.List;

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
   * Number of tombstone rows for a specific node. There is no public API to read these; replaces
   * {@code tombstoneRepository.getTombstones().stream().filter(t ->
   * t.getNodeId().equals(nodeId)).count()}.
   */
  int tombstoneCountForNode(String nodeId);

  /**
   * Deletes all tombstone rows. Tombstones are not FK-linked to a node, so {@link
   * #resetDatabase()} alone does not clean them up between tests.
   */
  void clearTombstones();

  /**
   * Flushes the in-memory file-version cache. Not FK/row-linked state, so {@link
   * #resetDatabase()} alone does not clean it up between tests; replaces {@code
   * simulator.clearFileVersionCache()} (used directly by {@code PreviewApiIT}/{@code
   * ThumbnailApiIT}'s {@code @AfterEach}).
   */
  void clearFileVersionCache();

  /**
   * True if a share exists for this node/user pair. Replaces {@code
   * shareRepository.getShare(nodeId, userId).isPresent()}.
   */
  boolean shareExists(String nodeId, String userId);

  /**
   * Remaining version numbers for a node, ascending. There is no public API to read these
   * directly as a flat list; replaces {@code fileVersionRepository.getFileVersions(nodeId,
   * List.of(FileVersionSort.VERSION_ASC)).stream().map(FileVersion::getVersion)}.
   */
  List<Integer> remainingVersionNumbers(String nodeId);

  /**
   * Builds a base64-encoded, tampered {@code findNodes} page-token with a fixed keySet/folderId/
   * sort payload and NO {@code signature} field at all. Decoding it server-side fails signature
   * verification. Replaces hand-rolled construction of {@code NodeSQLCondition}/{@code
   * SQLExpression}/{@code SortOrder} (Ebean-era DAL internals) directly in a test body — used by
   * {@code PublicFindNodesApiIT}'s "hacked page token without signature" test.
   */
  String forgeTamperedPageTokenMissingSignature();

  /**
   * Builds the same tampered cursor as {@link #forgeTamperedPageTokenMissingSignature()} but with
   * an explicit, incorrect {@code signature} field set to {@code wrongSignature}. Used by {@code
   * PublicFindNodesApiIT}'s "hacked page token with wrong signature" test.
   */
  String forgeTamperedPageTokenWithWrongSignature(String wrongSignature);
}
