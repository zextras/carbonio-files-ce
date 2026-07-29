// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.tasks;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.zextras.carbonio.files.dal.dao.ebean.FileVersion;
import com.zextras.carbonio.files.dal.dao.ebean.Node;
import com.zextras.carbonio.files.dal.dao.ebean.NodeType;
import com.zextras.carbonio.files.dal.dao.ebean.Tombstone;
import com.zextras.carbonio.files.dal.repositories.impl.ebean.utilities.FileVersionSort;
import com.zextras.carbonio.files.dal.repositories.interfaces.FileVersionRepository;
import com.zextras.carbonio.files.dal.repositories.interfaces.NodeRepository;
import com.zextras.carbonio.files.dal.repositories.interfaces.TombstoneRepository;
import com.zextras.filestore.api.Filestore;
import com.zextras.filestore.model.BulkDeleteResponseItem;
import com.zextras.filestore.model.IdentifierType;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

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
  private Filestore filestore;
  private PurgeService purgeService;

  private static Node trashedNode(String id, String ownerId, NodeType type, String ancestorIds) {
    long now = System.currentTimeMillis();
    return new Node(
        id, ownerId, ownerId, "TRASH_ROOT", now, now, "name-" + id, "desc", type, ancestorIds, 1L);
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
    filestore = mock(Filestore.class);

    purgeService = new PurgeService();
    purgeService.nodeRepository = nodeRepository;
    purgeService.fileVersionRepository = fileVersionRepository;
    purgeService.tombstoneRepository = tombstoneRepository;
    purgeService.fileStore = filestore;
  }

  // ---------------------------------------------------------------------------- purgeTrashedNodes

  @Test
  void purgeTrashedNodesDeletesNodeWhenBulkDeleteSucceeds() throws Exception {
    Node file = trashedNode("node-1", "owner-1", NodeType.TEXT, "TRASH_ROOT");
    when(nodeRepository.getAllTrashedNodes(anyLong())).thenReturn(List.of(file));
    when(fileVersionRepository.getFileVersions("node-1", List.of(FileVersionSort.VERSION_ASC)))
        .thenReturn(List.of(new FileVersion("node-1", "owner-1", 1L, 1, "text/plain", 1L, "d", false)));
    when(filestore.bulkDelete(eq(IdentifierType.files), eq("owner-1"), anyList()))
        .thenReturn(List.of());

    purgeService.purgeTrashedNodes(30);

    verify(nodeRepository).deleteNodes(List.of("node-1"));
  }

  @Test
  void purgeTrashedNodesKeepsNodeWhenBulkDeleteThrows() throws Exception {
    Node file = trashedNode("node-2", "owner-2", NodeType.TEXT, "TRASH_ROOT");
    when(nodeRepository.getAllTrashedNodes(anyLong())).thenReturn(List.of(file));
    when(fileVersionRepository.getFileVersions("node-2", List.of(FileVersionSort.VERSION_ASC)))
        .thenReturn(List.of(new FileVersion("node-2", "owner-2", 1L, 1, "text/plain", 1L, "d", false)));
    when(filestore.bulkDelete(eq(IdentifierType.files), eq("owner-2"), anyList()))
        .thenThrow(new RuntimeException("storages down"));

    purgeService.purgeTrashedNodes(30);

    verify(nodeRepository, never()).deleteNodes(any());
  }

  @Test
  void purgeTrashedNodesKeepsAFolderWhenOneOfItsChildFilesFailedToDelete() throws Exception {
    // A folder and its child file are trashed together; the child's blob delete fails. The folder
    // itself never generates a bulk-delete request (folders have no blob), but it must NOT be
    // removed from the DB while a descendant file's blob deletion is still unconfirmed.
    Node folder = trashedNode("folder-1", "owner-3", NodeType.FOLDER, "LOCAL_ROOT");
    Node child = trashedNode("child-1", "owner-3", NodeType.TEXT, "LOCAL_ROOT,folder-1");
    when(nodeRepository.getAllTrashedNodes(anyLong())).thenReturn(List.of(folder, child));
    when(fileVersionRepository.getFileVersions("child-1", List.of(FileVersionSort.VERSION_ASC)))
        .thenReturn(List.of(new FileVersion("child-1", "owner-3", 1L, 1, "text/plain", 1L, "d", false)));
    when(filestore.bulkDelete(eq(IdentifierType.files), eq("owner-3"), anyList()))
        .thenThrow(new RuntimeException("storages down"));

    purgeService.purgeTrashedNodes(30);

    verify(nodeRepository, never()).deleteNodes(any());
  }

  @Test
  void purgeTrashedNodesDoesNothingWhenThereAreNoTrashedNodes() {
    when(nodeRepository.getAllTrashedNodes(anyLong())).thenReturn(List.of());

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
    when(filestore.bulkDelete(eq(IdentifierType.files), eq("owner-4"), anyList()))
        .thenReturn(null);

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
  void purgeTombstonesDropsTheBlobAsAnOrphanAfterTheThirdConsecutivePartialFailure() throws Exception {
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
  void purgeTombstonesDoesNothingWhenThereAreNoTombstones() {
    when(tombstoneRepository.getTombstones()).thenReturn(List.of());

    purgeService.purgeTombstones();

    verifyNoMoreInteractions(filestore);
  }
}
