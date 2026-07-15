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

  /**
   * Runs one full {@code PurgeService} cycle (equivalent to what its scheduled {@code Runnable}
   * does: {@code purgeTombstones()} then {@code purgeTrashedNodes(RETENTION_TRASHED_ITEMS_IN_DAYS)}
   * — see {@code PurgeService#run()}). There is no HTTP trigger for this in production; this is a
   * deliberate, APPROVED non-HTTP backdoor (see the acceptance-coverage plan §2.5/§10) — it is the
   * only way the acceptance suite can cover purge logic at all, since a black-box request can never
   * reach a cron {@code Runnable}. Effects (tombstones cleared, trashed nodes removed) are asserted
   * via other backdoor accessors ({@link #tombstoneCount()}, {@link #nodeExists(String)}) and/or
   * HTTP reads, exactly like the white-box {@code PurgeServiceIT}/{@code PurgeTombstonesJobIT}.
   */
  void runPurge();

  /**
   * Simulates a {@code UserStatusChanged} message-broker event for {@code userId}, mirroring the
   * white-box {@code UserStatusChangedIT}'s direct {@code new
   * UserStatusChangedConsumer(nodeRepository).doHandle(new UserStatusChanged(userId, status))}.
   * The effect — the user's nodes' hidden flag flipped when transitioning to/from CLOSED — is
   * HTTP-observable (hidden nodes are excluded from {@code findNodes}). A deliberate, APPROVED
   * non-HTTP backdoor (no RabbitMQ trigger exists in the acceptance suite); see plan §2.5/§10.
   *
   * @param status a raw message-broker UM status string (e.g. "ACTIVE", "CLOSED", "MAINTENANCE"),
   *     matched case-insensitively.
   */
  void injectUserStatusChanged(String userId, String status);

  /**
   * Simulates a {@code KeyValueChanged("carbonio-files/max-number-of-versions", newMax)}
   * message-broker event, mirroring the white-box {@code MaxVersionNumberChangedIT}'s direct
   * {@code new KeyValueChangedConsumer(fileVersionRepository).doHandle(...)}. Trims the least
   * recent file versions (never the current version, never a keptForever one) for every node
   * whose version count exceeds {@code newMax}. The effect is HTTP-observable via {@link
   * #remainingVersionNumbers(String)} and/or a subsequent download of a trimmed version (404). A
   * deliberate, APPROVED non-HTTP backdoor; see plan §2.5/§10.
   */
  void injectMaxVersionNumberChanged(int newMax);
}
