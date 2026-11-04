// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.tasks;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
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
import com.zextras.carbonio.files.dal.repositories.impl.Transactions;
import com.zextras.carbonio.files.dal.repositories.impl.ebean.utilities.FileVersionSort;
import com.zextras.carbonio.files.dal.repositories.interfaces.FileVersionRepository;
import com.zextras.carbonio.files.dal.repositories.interfaces.NodeRepository;
import com.zextras.carbonio.files.dal.repositories.interfaces.OverwriteLeaseRepository;
import com.zextras.carbonio.files.dal.repositories.interfaces.TombstoneRepository;
import com.zextras.carbonio.files.dal.repositories.interfaces.VersionReservationRepository;
import com.zextras.carbonio.files.rest.services.BlobLifecycle;
import com.zextras.filestore.api.Filestore;
import com.zextras.filestore.model.BulkDeleteResponseItem;
import com.zextras.filestore.model.IdentifierType;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
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
  private VersionReservationRepository versionReservationRepository;
  private OverwriteLeaseRepository overwriteLeaseRepository;
  private Filestore filestore;
  private Transactions transactions;
  private PurgeService purgeService;

  private static Node trashedNode(String id, String ownerId, NodeType type, String ancestorIds) {
    long now = System.currentTimeMillis();
    return new Node(
        id, ownerId, ownerId, "TRASH_ROOT", now, now, "name-" + id, "desc", type, ancestorIds, 1L);
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

  @BeforeEach
  void setUp() {
    nodeRepository = mock(NodeRepository.class);
    fileVersionRepository = mock(FileVersionRepository.class);
    tombstoneRepository = mock(TombstoneRepository.class);
    versionReservationRepository = mock(VersionReservationRepository.class);
    overwriteLeaseRepository = mock(OverwriteLeaseRepository.class);
    filestore = mock(Filestore.class);

    purgeService = new PurgeService();
    purgeService.nodeRepository = nodeRepository;
    purgeService.fileVersionRepository = fileVersionRepository;
    purgeService.tombstoneRepository = tombstoneRepository;
    purgeService.versionReservationRepository = versionReservationRepository;
    purgeService.overwriteLeaseRepository = overwriteLeaseRepository;
    purgeService.reservationExpiry = Duration.ofHours(24);
    transactions = mock(Transactions.class);
    try {
      when(transactions.inNewTransaction(any()))
          .thenAnswer(invocation -> invocation.<Callable<?>>getArgument(0).call());
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
    purgeService.transactions = transactions;
    purgeService.blobLifecycle =
        new BlobLifecycle(
            filestore, tombstoneRepository, versionReservationRepository, transactions);
  }

  // ---------------------------------------------------------------------------- purgeTrashedNodes

  private static FileVersion version(String nodeId, String owner, int version) {
    return new FileVersion(nodeId, owner, 1L, version, "text/plain", 1L, "d", false);
  }

  private Node folderF;
  private Node fileA;
  private Node subS;
  private Node fileB;
  private List<FileVersion> aVersions;
  private List<FileVersion> bVersions;

  private void givenTrashedTree() {
    folderF = trashedNode("folder-f", "owner-1", NodeType.FOLDER, "TRASH_ROOT");
    fileA = trashedNode("file-a", "owner-1", NodeType.TEXT, "TRASH_ROOT,folder-f");
    subS = trashedNode("sub-s", "owner-1", NodeType.FOLDER, "TRASH_ROOT,folder-f");
    fileB = trashedNode("file-b", "owner-2", NodeType.TEXT, "TRASH_ROOT,folder-f,sub-s");
    aVersions = List.of(version("file-a", "owner-1", 1), version("file-a", "owner-1", 2));
    bVersions = List.of(version("file-b", "owner-2", 1));
    when(nodeRepository.getTrashedTopNodes(anyLong())).thenReturn(List.of(folderF));
    when(nodeRepository.getSubtree(folderF)).thenReturn(List.of(folderF, fileA, subS, fileB));
    when(fileVersionRepository.getFileVersions("file-a", List.of(FileVersionSort.VERSION_ASC)))
        .thenReturn(aVersions);
    when(fileVersionRepository.getFileVersions("file-b", List.of(FileVersionSort.VERSION_ASC)))
        .thenReturn(bVersions);
  }

  @Test
  void purgeTrashedNodesWritesTombstonesAndDeletesRowsBeforeAnyBlobDelete() throws Exception {
    givenTrashedTree();
    when(filestore.bulkDelete(any(), any(), anyList())).thenReturn(List.of());

    purgeService.purgeTrashedNodes(30);

    InOrder order = inOrder(transactions, tombstoneRepository, nodeRepository, filestore);
    order.verify(transactions).inNewTransaction(any());
    order.verify(tombstoneRepository).createTombstonesBulk(aVersions, "owner-1");
    order.verify(tombstoneRepository).createTombstonesBulk(bVersions, "owner-2");
    order
        .verify(nodeRepository)
        .deleteNodesAndShares(List.of("folder-f", "file-a", "sub-s", "file-b"));
    order.verify(filestore).bulkDelete(eq(IdentifierType.files), eq("owner-1"), anyList());
    order.verify(filestore).bulkDelete(eq(IdentifierType.files), eq("owner-2"), anyList());
    verify(tombstoneRepository).deleteTombstonesByNodeAndVersion("file-a", 1);
    verify(tombstoneRepository).deleteTombstonesByNodeAndVersion("file-a", 2);
    verify(tombstoneRepository).deleteTombstonesByNodeAndVersion("file-b", 1);
  }

  @Test
  void purgeTrashedNodesOnlyAsksForTheTopNodesOlderThanTheRetention() {
    when(nodeRepository.getTrashedTopNodes(anyLong())).thenReturn(List.of());

    purgeService.purgeTrashedNodes(30);

    ArgumentCaptor<Long> ts = ArgumentCaptor.forClass(Long.class);
    verify(nodeRepository).getTrashedTopNodes(ts.capture());
    assertThat(ts.getValue())
        .isLessThan(System.currentTimeMillis() - 29L * 86400 * 1000)
        .isGreaterThan(System.currentTimeMillis() - 31L * 86400 * 1000);
    verify(nodeRepository, never()).deleteNodesAndShares(any());
    verifyNoInteractions(filestore);
  }

  @Test
  void purgeTrashedNodesDoesNotDeleteAnyBlobWhenTheDatabaseTransactionFails() throws Exception {
    givenTrashedTree();
    doThrow(new RuntimeException("db down")).when(nodeRepository).deleteNodesAndShares(any());

    purgeService.purgeTrashedNodes(30);

    verifyNoInteractions(filestore);
  }

  @Test
  void purgeTrashedNodesContinuesWithTheNextTopNodeWhenOneFails() throws Exception {
    givenTrashedTree();
    Node other = trashedNode("file-c", "owner-3", NodeType.TEXT, "TRASH_ROOT");
    when(nodeRepository.getTrashedTopNodes(anyLong())).thenReturn(List.of(folderF, other));
    List<FileVersion> cVersions = List.of(version("file-c", "owner-3", 1));
    when(fileVersionRepository.getFileVersions("file-c", List.of(FileVersionSort.VERSION_ASC)))
        .thenReturn(cVersions);
    when(nodeRepository.getSubtree(other)).thenReturn(List.of(other));
    doThrow(new RuntimeException("db down"))
        .when(nodeRepository)
        .deleteNodesAndShares(List.of("folder-f", "file-a", "sub-s", "file-b"));
    when(filestore.bulkDelete(any(), any(), anyList())).thenReturn(List.of());

    purgeService.purgeTrashedNodes(30);

    verify(nodeRepository).deleteNodesAndShares(List.of("file-c"));
    verify(filestore).bulkDelete(eq(IdentifierType.files), eq("owner-3"), anyList());
    verify(filestore, never()).bulkDelete(eq(IdentifierType.files), eq("owner-1"), anyList());
  }

  @Test
  void purgeTrashedNodesKeepsTheTombstoneOfABlobThatFailedToDeleteWithoutCountingAnAttempt()
      throws Exception {
    givenTrashedTree();
    when(filestore.bulkDelete(eq(IdentifierType.files), eq("owner-1"), anyList()))
        .thenReturn(List.of());
    List<BulkDeleteResponseItem> failedB = List.of(failedItem("file-b", 1));
    when(filestore.bulkDelete(eq(IdentifierType.files), eq("owner-2"), anyList()))
        .thenReturn(failedB);

    purgeService.purgeTrashedNodes(30);

    verify(tombstoneRepository).createTombstonesBulk(bVersions, "owner-2");
    verify(tombstoneRepository, never()).deleteTombstonesByNodeAndVersion("file-b", 1);
    verify(tombstoneRepository, never()).updateTombstone(any());
    verify(tombstoneRepository).deleteTombstonesByNodeAndVersion("file-a", 1);
  }

  @Test
  void purgeTrashedNodesKeepsTheTombstonesWhenBulkDeleteReturnsNullOrThrowsNpe() throws Exception {
    givenTrashedTree();
    when(filestore.bulkDelete(eq(IdentifierType.files), eq("owner-1"), anyList())).thenReturn(null);
    when(filestore.bulkDelete(eq(IdentifierType.files), eq("owner-2"), anyList()))
        .thenThrow(new NullPointerException("ids"));

    purgeService.purgeTrashedNodes(30);

    verify(nodeRepository).deleteNodesAndShares(any());
    verify(tombstoneRepository, never()).deleteTombstonesByNodeAndVersion(any(), any());
    verify(tombstoneRepository, never()).updateTombstone(any());
  }

  @Test
  void purgeTrashedNodesPurgesAChildTouchedAfterItsTrashedTopWithTheTop() throws Exception {
    givenTrashedTree();
    fileA.setUpdatedAt(System.currentTimeMillis() + 1000);
    when(filestore.bulkDelete(any(), any(), anyList())).thenReturn(List.of());

    purgeService.purgeTrashedNodes(30);

    verify(nodeRepository).deleteNodesAndShares(List.of("folder-f", "file-a", "sub-s", "file-b"));
  }

  @Test
  void purgeTrashedNodesDoesNothingWhenThereAreNoTrashedNodes() {
    when(nodeRepository.getTrashedTopNodes(anyLong())).thenReturn(List.of());

    purgeService.purgeTrashedNodes(30);

    verifyNoMoreInteractions(filestore);
    verify(nodeRepository, never()).deleteNodesAndShares(any());
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
    verify(tombstoneRepository, never()).updateTombstone(any());
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
    ArgumentCaptor<Tombstone> captor = ArgumentCaptor.forClass(Tombstone.class);
    verify(tombstoneRepository).updateTombstone(captor.capture());
    assertThat(captor.getValue().getNodeId()).isEqualTo("node-fail");
    assertThat(captor.getValue().getAttempts()).isEqualTo(1);
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
    verify(tombstoneRepository, never()).updateTombstone(any());
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
    verify(tombstoneRepository, never()).updateTombstone(any());
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
    verify(tombstoneRepository, never()).updateTombstone(any());
  }

  @Test
  void purgeTombstonesDropsTheBlobAsAnOrphanAfterTheThirdConsecutivePartialFailure()
      throws Exception {
    // MAX_TOMBSTONE_RETRIES = 3: a blob PowerStore keeps genuinely rejecting (HTTP 200, listed in
    // the failed-ids response — NOT an outage) is dropped as an accepted orphan on the 3rd run.
    Tombstone tombstone = new Tombstone("node-e", "owner-6", 1L, 1);
    when(tombstoneRepository.getTombstones()).thenReturn(List.of(tombstone));
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
    verify(tombstoneRepository, times(2)).updateTombstone(any());
  }

  @Test
  void purgeTombstonesRemovesNormallyWhenABlobRecoversOnTheSecondRun() throws Exception {
    // Retry-and-recover: a blob that fails once but succeeds on the second run is removed
    // normally, well before the retry cap — the counter is not a one-way ratchet toward deletion.
    Tombstone tombstone = new Tombstone("node-f", "owner-7", 1L, 1);
    when(tombstoneRepository.getTombstones()).thenReturn(List.of(tombstone));
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
  void purgeTombstonesMatchesTheFailedItemsByNodeAndVersion() throws Exception {
    Tombstone failing = new Tombstone("n1", "owner-9", 1L, 2);
    Tombstone deleted = new Tombstone("n1", "owner-9", 1L, 3);
    when(tombstoneRepository.getTombstones()).thenReturn(List.of(failing, deleted));
    List<BulkDeleteResponseItem> notDeleted = List.of(failedItem("n1", 2));
    when(filestore.bulkDelete(eq(IdentifierType.files), eq("owner-9"), anyList()))
        .thenReturn(notDeleted);

    purgeService.purgeTombstones();

    verify(tombstoneRepository).deleteTombstonesByNodeAndVersion("n1", 3);
    verify(tombstoneRepository, never()).deleteTombstonesByNodeAndVersion("n1", 2);
    assertThat(failing.getAttempts()).isEqualTo(1);
    assertThat(deleted.getAttempts()).isEqualTo(0);
  }

  @Test
  void purgeTombstonesTreatsAnItemWithoutVersionAsFailingEveryVersionOfTheNode() throws Exception {
    Tombstone first = new Tombstone("n1", "owner-9", 1L, 2);
    Tombstone second = new Tombstone("n1", "owner-9", 1L, 3);
    when(tombstoneRepository.getTombstones()).thenReturn(List.of(first, second));
    List<BulkDeleteResponseItem> notDeleted = List.of(failedItem("n1"));
    when(filestore.bulkDelete(eq(IdentifierType.files), eq("owner-9"), anyList()))
        .thenReturn(notDeleted);

    purgeService.purgeTombstones();

    verify(tombstoneRepository, never()).deleteTombstonesByNodeAndVersion(any(), any());
    assertThat(first.getAttempts()).isEqualTo(1);
    assertThat(second.getAttempts()).isEqualTo(1);
  }

  @Test
  void purgeTombstonesKeepsEverythingAndCountsNothingWhenTheBodyIsMalformed() throws Exception {
    Tombstone tombstone = new Tombstone("n1", "owner-9", 1L, 2);
    when(tombstoneRepository.getTombstones()).thenReturn(List.of(tombstone));
    when(filestore.bulkDelete(eq(IdentifierType.files), eq("owner-9"), anyList()))
        .thenThrow(new NullPointerException("malformed"));

    purgeService.purgeTombstones();

    verify(tombstoneRepository, never()).deleteTombstonesByNodeAndVersion(any(), any());
    verify(tombstoneRepository, never()).updateTombstone(any());
    assertThat(tombstone.getAttempts()).isEqualTo(0);
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
  void
      purgeExpiredReservationsTurnsAnUnpublishedReservationIntoATombstoneWithoutTouchingTheStore() {
    VersionReservation unpublished = reservation("node-a", 2, "owner-1", 0);
    when(versionReservationRepository.getPurging()).thenReturn(List.of(unpublished));
    when(fileVersionRepository.getFileVersion("node-a", 2)).thenReturn(Optional.empty());

    purgeService.purgeExpiredReservations();

    verify(transactions, times(1)).inNewTransaction(any());
    verify(tombstoneRepository).createNewTombstone("node-a", "owner-1", 2);
    verify(versionReservationRepository).remove(unpublished);
    verifyNoInteractions(filestore);
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
    verify(tombstoneRepository, never()).createNewTombstone(any(), any(), any());
    verifyNoMoreInteractions(filestore);
  }

  @Test
  void purgeExpiredOverwriteLeasesRemovesOnlyTheRowsExpiredForOverAnHour() {
    purgeService.purgeExpiredOverwriteLeases();

    verify(overwriteLeaseRepository).removeExpired(Duration.ofHours(1));
    verifyNoInteractions(filestore);
  }
}
