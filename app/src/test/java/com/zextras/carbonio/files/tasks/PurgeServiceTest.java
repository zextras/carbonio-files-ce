// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.tasks;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.zextras.carbonio.files.dal.dao.ebean.FileVersion;
import com.zextras.carbonio.files.dal.dao.ebean.Node;
import com.zextras.carbonio.files.dal.dao.ebean.NodeType;
import com.zextras.carbonio.files.dal.dao.ebean.Tombstone;
import com.zextras.carbonio.files.dal.dao.ebean.VersionReservation;
import com.zextras.carbonio.files.dal.locks.LockKey;
import com.zextras.carbonio.files.dal.locks.LockRetry;
import com.zextras.carbonio.files.dal.locks.LockedTree;
import com.zextras.carbonio.files.dal.locks.NodeLocks;
import com.zextras.carbonio.files.dal.repositories.impl.ebean.utilities.FileVersionSort;
import com.zextras.carbonio.files.dal.repositories.interfaces.FileVersionRepository;
import com.zextras.carbonio.files.dal.repositories.interfaces.NodeRepository;
import com.zextras.carbonio.files.dal.repositories.interfaces.OverwriteLeaseRepository;
import com.zextras.carbonio.files.dal.repositories.interfaces.ShareRepository;
import com.zextras.carbonio.files.dal.repositories.interfaces.TombstoneRepository;
import com.zextras.carbonio.files.dal.repositories.interfaces.VersionReservationRepository;
import com.zextras.filestore.api.Filestore;
import com.zextras.filestore.model.BulkDeleteRequestItem;
import com.zextras.filestore.model.BulkDeleteResponseItem;
import com.zextras.filestore.model.IdentifierType;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

/**
 * Plain JUnit (NOT {@code @QuarkusTest}) unit test for {@link PurgeService}'s orchestration/outage
 * logic, replacing both {@code com.zextras.carbonio.files.acceptance.PurgeApiIT} (which drove the
 * scheduler through the {@code AcceptancePurgeBridge} reflection hack, now deleted) and the
 * white-box {@code PurgeServiceIT} (which needed a real Postgres Testcontainer). Since {@link
 * PurgeService#purgeTrashedNodes} / {@link PurgeService#purgeTombstones} are package-private, this
 * test lives in the SAME package and calls them directly — no reflection, no Arc.
 *
 * <p><b>Coverage change (accepted, per the acceptance-to-Quarkus-tests plan's D1 rule):</b> purge
 * has no HTTP trigger, so the end-to-end "cron actually deletes rows from Postgres" is no longer
 * black-box; the exact tombstone-selection/trashed-node SQL (real {@code WHERE ... older than N
 * days}, real cascading deletes) loses real-DB coverage and is asserted here via mocked repository
 * interactions only. The orchestration/outage DECISION LOGIC (which is what actually broke in the
 * past — outage vs partial-failure vs give-up-after-N-retries) is preserved exactly.
 */
class PurgeServiceTest {

  private NodeRepository nodeRepository;
  private FileVersionRepository fileVersionRepository;
  private TombstoneRepository tombstoneRepository;
  private ShareRepository shareRepository;
  private NodeLocks nodeLocks;
  private LockedTree lockedTree;
  private LockRetry lockRetry;
  private VersionReservationRepository versionReservationRepository;
  private OverwriteLeaseRepository overwriteLeaseRepository;
  private Filestore filestore;
  private PurgeService purgeService;

  private static Node node(String id, String ownerId, NodeType type, long updatedAt) {
    return new Node(
        id,
        ownerId,
        ownerId,
        "TRASH_ROOT",
        updatedAt,
        updatedAt,
        "name-" + id,
        "desc",
        type,
        "TRASH_ROOT",
        1L);
  }

  private static VersionReservation reservation(
      String nodeId, int version, String ownerId, int attempts) {
    VersionReservation reservation = mock(VersionReservation.class);
    when(reservation.getNodeId()).thenReturn(nodeId);
    when(reservation.getVersion()).thenReturn(version);
    when(reservation.getOwnerId()).thenReturn(ownerId);
    when(reservation.getAttempts()).thenReturn(attempts);
    return reservation;
  }

  private static BulkDeleteResponseItem failedItem(String nodeId, int version) {
    BulkDeleteResponseItem item = failedItem(nodeId);
    when(item.getVersion()).thenReturn(Optional.of(version));
    return item;
  }

  private static BulkDeleteResponseItem failedItem(String nodeId) {
    BulkDeleteResponseItem item = mock(BulkDeleteResponseItem.class);
    when(item.getNode()).thenReturn(nodeId);
    return item;
  }

  private void countAttemptsOn(Tombstone tombstone) {
    doAnswer(
            invocation -> {
              tombstone.setAttempts(tombstone.getAttempts() + 1);
              return null;
            })
        .when(tombstoneRepository)
        .incrementAttempts(tombstone.getNodeId(), tombstone.getVersion());
  }

  @BeforeEach
  void setUp() {
    nodeRepository = mock(NodeRepository.class);
    fileVersionRepository = mock(FileVersionRepository.class);
    tombstoneRepository = mock(TombstoneRepository.class);
    versionReservationRepository = mock(VersionReservationRepository.class);
    overwriteLeaseRepository = mock(OverwriteLeaseRepository.class);
    filestore = mock(Filestore.class);
    shareRepository = mock(ShareRepository.class);
    nodeLocks = mock(NodeLocks.class);
    lockedTree = mock(LockedTree.class);
    lockRetry = mock(LockRetry.class);
    doAnswer(
            invocation -> {
              invocation.<Runnable>getArgument(0).run();
              return null;
            })
        .when(lockRetry)
        .runInNewTransaction(any());

    purgeService = new PurgeService();
    purgeService.nodeRepository = nodeRepository;
    purgeService.fileVersionRepository = fileVersionRepository;
    purgeService.tombstoneRepository = tombstoneRepository;
    purgeService.versionReservationRepository = versionReservationRepository;
    purgeService.overwriteLeaseRepository = overwriteLeaseRepository;
    purgeService.reservationExpiry = Duration.ofHours(24);
    purgeService.fileStore = filestore;
    purgeService.shareRepository = shareRepository;
    purgeService.nodeLocks = nodeLocks;
    purgeService.lockedTree = lockedTree;
    purgeService.lockRetry = lockRetry;
  }

  // ---------------------------------------------------------------------------- purgeTrashedNodes

  private void claimOnly(Node top, Node... descendants) {
    when(nodeRepository.getTrashedTopNodeIds(anyLong())).thenReturn(List.of(top.getId()));
    when(nodeLocks.lock(any())).thenReturn(Set.of(top.getId()));
    when(nodeRepository.getNode(top.getId())).thenReturn(Optional.of(top));
    when(nodeRepository.isTrashed(top.getId())).thenReturn(true);
    List<Node> subtree = new ArrayList<>(List.of(top));
    subtree.addAll(List.of(descendants));
    when(lockedTree.collect(List.of(top))).thenReturn(subtree);
  }

  @Test
  void purgeTrashedNodesClaimsTheSubtreeWithItsTombstonesAndDeletesNoBlobItself() {
    Node folder = node("folder-1", "owner-1", NodeType.FOLDER, 1L);
    Node file = node("file-1", "owner-1", NodeType.TEXT, 1L);
    claimOnly(folder, file);
    List<FileVersion> versions =
        List.of(new FileVersion("file-1", "owner-1", 1L, 1, "text/plain", 1L, "d", false));
    when(fileVersionRepository.getFileVersions("file-1", List.of(FileVersionSort.VERSION_ASC)))
        .thenReturn(versions);

    purgeService.purgeTrashedNodes(30);

    verify(tombstoneRepository).createTombstonesBulk(versions, "owner-1");
    verify(shareRepository).deleteSharesBulk(List.of("folder-1", "file-1"));
    verify(nodeRepository).deleteNodes(List.of("folder-1", "file-1"));
    verifyNoInteractions(filestore);
  }

  @Test
  void purgeTrashedNodesPurgesTheTombstonesAfterTheClaims() throws Exception {
    Node file = node("file-1", "owner-1", NodeType.TEXT, 1L);
    claimOnly(file);
    Tombstone tombstone = new Tombstone("file-1", "owner-1", 1L, 1);
    when(tombstoneRepository.getTombstones()).thenReturn(List.of(tombstone));
    when(filestore.bulkDelete(eq(IdentifierType.files), eq("owner-1"), anyList()))
        .thenReturn(List.of());

    purgeService.purgeTrashedNodes(30);

    InOrder order = inOrder(nodeRepository, tombstoneRepository, filestore);
    order.verify(nodeRepository).deleteNodes(List.of("file-1"));
    order.verify(filestore).bulkDelete(eq(IdentifierType.files), eq("owner-1"), anyList());
    order.verify(tombstoneRepository).deleteTombstonesByNodeAndVersion("file-1", 1);
  }

  @Test
  void purgeTrashedNodesSkipsANodeThatNoLongerExists() {
    Node file = node("file-1", "owner-1", NodeType.TEXT, 1L);
    claimOnly(file);
    when(nodeLocks.lock(any())).thenReturn(Set.of());

    purgeService.purgeTrashedNodes(30);

    verify(nodeRepository, never()).deleteNodes(any());
  }

  @Test
  void purgeTrashedNodesSkipsANodeRestoredMeanwhile() {
    Node file = node("file-1", "owner-1", NodeType.TEXT, 1L);
    claimOnly(file);
    when(nodeRepository.isTrashed("file-1")).thenReturn(false);

    purgeService.purgeTrashedNodes(30);

    verify(tombstoneRepository, never()).createTombstonesBulk(any(), any());
    verifyNoInteractions(shareRepository);
    verify(nodeRepository, never()).deleteNodes(any());
  }

  @Test
  void purgeTrashedNodesSkipsANodeTouchedMeanwhile() {
    Node file = node("file-1", "owner-1", NodeType.TEXT, System.currentTimeMillis() + 1000);
    claimOnly(file);

    purgeService.purgeTrashedNodes(30);

    verify(nodeRepository, never()).deleteNodes(any());
    verifyNoInteractions(shareRepository);
  }

  @Test
  void purgeTrashedNodesGoesOnWithTheNextNodeWhenOneClaimFails() {
    Node second = node("file-2", "owner-1", NodeType.TEXT, 1L);
    when(nodeRepository.getTrashedTopNodeIds(anyLong())).thenReturn(List.of("file-1", "file-2"));
    when(nodeLocks.lock(List.of(LockKey.node("file-1"))))
        .thenThrow(new IllegalStateException("lock failure"));
    when(nodeLocks.lock(List.of(LockKey.node("file-2")))).thenReturn(Set.of("file-2"));
    when(nodeRepository.getNode("file-2")).thenReturn(Optional.of(second));
    when(nodeRepository.isTrashed("file-2")).thenReturn(true);
    when(lockedTree.collect(List.of(second))).thenReturn(List.of(second));

    purgeService.purgeTrashedNodes(30);

    verify(nodeRepository).deleteNodes(List.of("file-2"));
  }

  @Test
  void purgeTrashedNodesDoesNothingWhenThereAreNoTrashedNodes() {
    when(nodeRepository.getTrashedTopNodeIds(anyLong())).thenReturn(List.of());

    purgeService.purgeTrashedNodes(30);

    verifyNoMoreInteractions(filestore);
    verify(nodeRepository, never()).deleteNodes(any());
  }

  // ------------------------------------------------------------------------------ purgeTombstones

  @Test
  void purgeTombstonesRemovesTombstoneWhenBulkDeleteSucceeds() throws Exception {
    Tombstone tombstone = new Tombstone("node-a", "owner-1", 1L, 1);
    when(tombstoneRepository.getTombstones()).thenReturn(List.of(tombstone));
    when(filestore.bulkDelete(eq(IdentifierType.files), eq("owner-1"), anyList()))
        .thenReturn(List.of());

    purgeService.purgeTombstones();

    verify(tombstoneRepository).deleteTombstonesByNodeAndVersion("node-a", 1);
    verify(tombstoneRepository, never()).incrementAttempts(any(), anyInt());
  }

  @Test
  void purgeTombstonesKeepsAndIncrementsAttemptsOnAGenuinePartialFailure() throws Exception {
    Tombstone keptFailing = new Tombstone("node-fail", "owner-2", 1L, 1);
    Tombstone succeeding = new Tombstone("node-ok", "owner-2", 1L, 1);
    when(tombstoneRepository.getTombstones()).thenReturn(List.of(keptFailing, succeeding));
    List<BulkDeleteResponseItem> partialFailure = List.of(failedItem("node-fail"));
    when(filestore.bulkDelete(eq(IdentifierType.files), eq("owner-2"), anyList()))
        .thenReturn(partialFailure);

    purgeService.purgeTombstones();

    verify(tombstoneRepository).deleteTombstonesByNodeAndVersion("node-ok", 1);
    verify(tombstoneRepository).incrementAttempts("node-fail", 1);
    verify(tombstoneRepository, never()).deleteTombstonesByNodeAndVersion("node-fail", 1);
  }

  @Test
  void purgeTombstonesKeepsWithoutTouchingAttemptsOnACompleteOutage() throws Exception {
    Tombstone tombstone = new Tombstone("node-b", "owner-3", 1L, 1);
    when(tombstoneRepository.getTombstones()).thenReturn(List.of(tombstone));
    when(filestore.bulkDelete(eq(IdentifierType.files), eq("owner-3"), anyList()))
        .thenThrow(new RuntimeException("connection refused"));

    purgeService.purgeTombstones();

    verify(tombstoneRepository, never()).deleteTombstonesByNodeAndVersion(any(), any());
    verify(tombstoneRepository, never()).incrementAttempts(any(), anyInt());
    assertThat(tombstone.getAttempts()).isEqualTo(0);
  }

  @Test
  void purgeTombstonesKeepsWithoutTouchingAttemptsWhenBulkDeleteReturnsNull() throws Exception {
    // A null/empty response is NOT a success signal (happens on connection failure) and must be
    // treated like an outage, never as "all deleted".
    Tombstone tombstone = new Tombstone("node-c", "owner-4", 1L, 1);
    when(tombstoneRepository.getTombstones()).thenReturn(List.of(tombstone));
    when(filestore.bulkDelete(eq(IdentifierType.files), eq("owner-4"), anyList())).thenReturn(null);

    purgeService.purgeTombstones();

    verify(tombstoneRepository, never()).deleteTombstonesByNodeAndVersion(any(), any());
    verify(tombstoneRepository, never()).incrementAttempts(any(), anyInt());
    assertThat(tombstone.getAttempts()).isEqualTo(0);
  }

  @Test
  void purgeTombstonesNeverDropsATombstoneAcrossThreeConsecutiveOutageCycles() throws Exception {
    // An outage must NEVER burn the retry budget, no matter how many cycles pass: attempts stay
    // untouched at 0 for every one of 3 consecutive purge runs while storages is down.
    Tombstone tombstone = new Tombstone("node-d", "owner-5", 1L, 1);
    when(tombstoneRepository.getTombstones()).thenReturn(List.of(tombstone));
    when(filestore.bulkDelete(eq(IdentifierType.files), eq("owner-5"), anyList()))
        .thenThrow(new RuntimeException("connection refused"));

    for (int run = 1; run <= 3; run++) {
      purgeService.purgeTombstones();
      assertThat(tombstone.getAttempts()).as("attempts after outage run " + run).isEqualTo(0);
    }
    verify(tombstoneRepository, never()).deleteTombstonesByNodeAndVersion(any(), any());
    verify(tombstoneRepository, never()).incrementAttempts(any(), anyInt());
  }

  @Test
  void purgeTombstonesDropsTheBlobAsAnOrphanAfterTheThirdConsecutivePartialFailure()
      throws Exception {
    // MAX_TOMBSTONE_RETRIES = 3: a blob PowerStore keeps genuinely rejecting (HTTP 200, listed in
    // the failed-ids response — NOT an outage) is dropped as an accepted orphan on the 3rd run.
    Tombstone tombstone = new Tombstone("node-e", "owner-6", 1L, 1);
    when(tombstoneRepository.getTombstones()).thenReturn(List.of(tombstone));
    countAttemptsOn(tombstone);
    List<BulkDeleteResponseItem> partialFailure = List.of(failedItem("node-e"));
    when(filestore.bulkDelete(eq(IdentifierType.files), eq("owner-6"), anyList()))
        .thenReturn(partialFailure);

    purgeService.purgeTombstones(); // run 1 -> attempts=1, kept
    assertThat(tombstone.getAttempts()).isEqualTo(1);
    purgeService.purgeTombstones(); // run 2 -> attempts=2, kept
    assertThat(tombstone.getAttempts()).isEqualTo(2);
    purgeService.purgeTombstones(); // run 3 -> attempts+1==3==MAX -> orphan accepted, dropped

    verify(tombstoneRepository, times(3)).getTombstones();
    verify(tombstoneRepository).deleteTombstonesByNodeAndVersion("node-e", 1);
    verify(tombstoneRepository, times(2)).incrementAttempts("node-e", 1);
  }

  @Test
  void purgeTombstonesRemovesNormallyWhenABlobRecoversOnTheSecondRun() throws Exception {
    // Retry-and-recover: a blob that fails once but succeeds on the second run is removed
    // normally, well before the retry cap — the counter is not a one-way ratchet toward deletion.
    Tombstone tombstone = new Tombstone("node-f", "owner-7", 1L, 1);
    when(tombstoneRepository.getTombstones()).thenReturn(List.of(tombstone));
    countAttemptsOn(tombstone);
    List<BulkDeleteResponseItem> partialFailure = List.of(failedItem("node-f"));
    when(filestore.bulkDelete(eq(IdentifierType.files), eq("owner-7"), anyList()))
        .thenReturn(partialFailure);

    purgeService.purgeTombstones(); // run 1 -> attempts=1, kept
    assertThat(tombstone.getAttempts()).isEqualTo(1);

    when(filestore.bulkDelete(eq(IdentifierType.files), eq("owner-7"), anyList()))
        .thenReturn(List.of());
    purgeService.purgeTombstones(); // run 2 -> succeeds -> removed, nowhere near the retry cap

    verify(tombstoneRepository).deleteTombstonesByNodeAndVersion("node-f", 1);
  }

  @Test
  void purgeTombstonesDoesNothingWhenThereAreNoTombstones() {
    when(tombstoneRepository.getTombstones()).thenReturn(List.of());

    purgeService.purgeTombstones();

    verifyNoMoreInteractions(filestore);
  }

  // ------------------------------------------------------------------- purgeExpiredReservations

  @Test
  void purgeExpiredReservationsClaimsTheReservationsOlderThanTheExpiry() {
    when(versionReservationRepository.getPurging()).thenReturn(List.of());

    purgeService.purgeExpiredReservations();

    verify(versionReservationRepository).claimExpired(Duration.ofHours(24));
  }

  @Test
  void purgeExpiredReservationsRemovesTheReservationsWhoseBlobsWereDeleted() throws Exception {
    VersionReservation first = reservation("node-a", 2, "owner-1", 0);
    VersionReservation second = reservation("node-b", 4, "owner-1", 0);
    when(versionReservationRepository.getPurging()).thenReturn(List.of(first, second));
    when(filestore.bulkDelete(eq(IdentifierType.files), eq("owner-1"), anyList()))
        .thenReturn(List.of());

    purgeService.purgeExpiredReservations();

    ArgumentCaptor<List<BulkDeleteRequestItem>> items = ArgumentCaptor.forClass(List.class);
    verify(filestore).bulkDelete(eq(IdentifierType.files), eq("owner-1"), items.capture());
    assertThat(items.getValue())
        .extracting(BulkDeleteRequestItem::getNode, BulkDeleteRequestItem::getVersion)
        .containsExactly(tuple("node-a", Optional.of(2)), tuple("node-b", Optional.of(4)));
    verify(versionReservationRepository).remove(first);
    verify(versionReservationRepository).remove(second);
    verify(versionReservationRepository, never()).recordFailedAttempt(any());
  }

  @Test
  void purgeExpiredReservationsCountsAnAttemptOnlyForTheVersionReportedAsNotDeleted()
      throws Exception {
    VersionReservation failing = reservation("node-a", 2, "owner-1", 0);
    VersionReservation deleted = reservation("node-a", 3, "owner-1", 0);
    when(versionReservationRepository.getPurging()).thenReturn(List.of(failing, deleted));
    List<BulkDeleteResponseItem> notDeleted = List.of(failedItem("node-a", 2));
    when(filestore.bulkDelete(eq(IdentifierType.files), eq("owner-1"), anyList()))
        .thenReturn(notDeleted);

    purgeService.purgeExpiredReservations();

    verify(versionReservationRepository).recordFailedAttempt(failing);
    verify(versionReservationRepository, never()).remove(failing);
    verify(versionReservationRepository).remove(deleted);
  }

  @Test
  void purgeExpiredReservationsDropsAReservationAtItsThirdNotDeletedAnswer() throws Exception {
    VersionReservation failing = reservation("node-a", 2, "owner-1", 2);
    when(versionReservationRepository.getPurging()).thenReturn(List.of(failing));
    List<BulkDeleteResponseItem> notDeleted = List.of(failedItem("node-a", 2));
    when(filestore.bulkDelete(eq(IdentifierType.files), eq("owner-1"), anyList()))
        .thenReturn(notDeleted);

    purgeService.purgeExpiredReservations();

    verify(versionReservationRepository).remove(failing);
    verify(versionReservationRepository, never()).recordFailedAttempt(any());
  }

  @Test
  void purgeExpiredReservationsLeavesTheReservationsUntouchedOnAnOutage() throws Exception {
    VersionReservation reservation = reservation("node-a", 2, "owner-1", 2);
    when(versionReservationRepository.getPurging()).thenReturn(List.of(reservation));
    when(filestore.bulkDelete(eq(IdentifierType.files), eq("owner-1"), anyList()))
        .thenThrow(new RuntimeException("connection refused"));

    purgeService.purgeExpiredReservations();

    verify(versionReservationRepository, never()).remove(any());
    verify(versionReservationRepository, never()).recordFailedAttempt(any());
  }

  @Test
  void purgeExpiredReservationsLeavesTheReservationsUntouchedWhenBulkDeleteReturnsNull()
      throws Exception {
    VersionReservation reservation = reservation("node-a", 2, "owner-1", 2);
    when(versionReservationRepository.getPurging()).thenReturn(List.of(reservation));
    when(filestore.bulkDelete(eq(IdentifierType.files), eq("owner-1"), anyList())).thenReturn(null);

    purgeService.purgeExpiredReservations();

    verify(versionReservationRepository, never()).remove(any());
    verify(versionReservationRepository, never()).recordFailedAttempt(any());
  }

  @Test
  void purgeExpiredReservationsCallsTheStoragePerBlobOwner() throws Exception {
    VersionReservation ofFirstOwner = reservation("node-a", 2, "owner-1", 0);
    VersionReservation ofSecondOwner = reservation("node-b", 2, "owner-2", 0);
    when(versionReservationRepository.getPurging())
        .thenReturn(List.of(ofFirstOwner, ofSecondOwner));
    when(filestore.bulkDelete(eq(IdentifierType.files), anyString(), anyList()))
        .thenReturn(List.of());

    purgeService.purgeExpiredReservations();

    verify(filestore).bulkDelete(eq(IdentifierType.files), eq("owner-1"), anyList());
    verify(filestore).bulkDelete(eq(IdentifierType.files), eq("owner-2"), anyList());
    verify(versionReservationRepository).remove(ofFirstOwner);
    verify(versionReservationRepository).remove(ofSecondOwner);
  }

  @Test
  void purgeExpiredReservationsNeverDeletesTheBlobOfAnExistingVersion() {
    VersionReservation published = reservation("node-a", 2, "owner-1", 0);
    when(versionReservationRepository.getPurging()).thenReturn(List.of(published));
    when(fileVersionRepository.getFileVersion("node-a", 2))
        .thenReturn(
            Optional.of(new FileVersion("node-a", "owner-1", 1L, 2, "text/plain", 1L, "d", false)));

    purgeService.purgeExpiredReservations();

    verify(versionReservationRepository).remove(published);
    verifyNoMoreInteractions(filestore);
  }

  @Test
  void purgeExpiredOverwriteLeasesRemovesOnlyTheRowsExpiredForOverAnHour() {
    purgeService.purgeExpiredOverwriteLeases();

    verify(overwriteLeaseRepository).removeExpired(Duration.ofHours(1));
    verifyNoInteractions(filestore);
  }
}
