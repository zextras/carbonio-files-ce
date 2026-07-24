// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.tasks;

import static org.assertj.core.api.Assertions.assertThat;

import com.zextras.carbonio.files.Constants.Db.RootId;
import com.zextras.carbonio.files.FilesStackTestResource;
import com.zextras.carbonio.files.dal.dao.ebean.NodeType;
import com.zextras.carbonio.files.dal.dao.ebean.Tombstone;
import com.zextras.carbonio.files.dal.repositories.interfaces.FileVersionRepository;
import com.zextras.carbonio.files.dal.repositories.interfaces.NodeRepository;
import com.zextras.carbonio.files.dal.repositories.interfaces.TombstoneRepository;
import com.zextras.filestore.api.Filestore;
import com.zextras.filestore.model.FilesIdentifier;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * P5a: integration test for {@link PurgeService}, invoking {@link PurgeService#purgeTrashedNodes}
 * / {@link PurgeService#purgeTombstones} directly (not via the {@code @Scheduled} entry point —
 * {@code %test.quarkus.scheduler.enabled=false} keeps the real timer from firing during the test
 * run anyway). Uses a real Postgres Testcontainer ({@link FilesStackTestResource}) and the app's
 * REAL {@code Filestore}/{@code StoragesClient} talking real HTTP to the {@link
 * com.zextras.carbonio.files.it.support.MockStoragesService} fake, whose failure-injection controls
 * ({@code failBulkDeleteFor}/{@code setBulkDeleteAlwaysThrows}) let the partial-failure / outage
 * paths be exercised without a real PowerStore.
 *
 * <p>Every test uses a fresh owner id and fresh node ids so bulk-delete batching never mixes
 * between tests, and asserts are always scoped to the ids the test itself created (rows left
 * behind by an intentional "keep" assertion are harmless noise for other tests, mirroring the
 * fresh-UUID convention already used by {@code BlobResourceIT}).
 */
@QuarkusTest
@QuarkusTestResource(FilesStackTestResource.class)
class PurgeServiceIT {

  @Inject PurgeService purgeService;
  @Inject NodeRepository nodeRepository;
  @Inject FileVersionRepository fileVersionRepository;
  @Inject TombstoneRepository tombstoneRepository;
  @Inject Filestore filestore;

  private static String id() {
    return UUID.randomUUID().toString();
  }

  @AfterEach
  void resetFilestoreFailures() {
    FilesStackTestResource.getStoragesService().resetBulkDeleteFailures();
  }

  /** Creates a TRASHED text-file node (ancestorIds/parentId = TRASH_ROOT) with one FileVersion. */
  private String seedTrashedFileWithVersion(String ownerId, String name, byte[] content)
      throws Exception {
    String nodeId = id();
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              nodeRepository.createNewNode(
                  nodeId,
                  ownerId,
                  ownerId,
                  RootId.TRASH_ROOT,
                  name,
                  "desc",
                  NodeType.TEXT,
                  RootId.TRASH_ROOT,
                  (long) content.length);
              fileVersionRepository.createNewFileVersion(
                  nodeId, ownerId, 1, "text/plain", content.length, "digest", false);
            });
    filestore.uploadPut(
        FilesIdentifier.of(nodeId, 1, ownerId), new ByteArrayInputStream(content), content.length);
    return nodeId;
  }

  // ------------------------------------------------------------------------------ purgeTrashedNodes

  @Test
  void purgeTrashedNodesRemovesRowAndBlobWhenBlobDeleteSucceeds() throws Exception {
    String ownerId = id();
    String nodeId =
        seedTrashedFileWithVersion(ownerId, "gone.txt", "bye".getBytes(StandardCharsets.UTF_8));

    purgeService.purgeTrashedNodes(0);

    boolean nodeStillPresent =
        QuarkusTransaction.requiringNew().call(() -> nodeRepository.getNode(nodeId).isPresent());
    assertThat(nodeStillPresent).as("node row removed after successful blob delete").isFalse();
    assertThat(FilesStackTestResource.getStoragesService().has(nodeId, 1))
        .as("blob removed after successful delete")
        .isFalse();
  }

  @Test
  void purgeTrashedNodesKeepsRowAndBlobWhenBlobDeleteFails() throws Exception {
    String ownerId = id();
    String keptNodeId =
        seedTrashedFileWithVersion(
            ownerId, "kept.txt", "kept".getBytes(StandardCharsets.UTF_8));
    String removedNodeId =
        seedTrashedFileWithVersion(
            ownerId, "removed.txt", "removed".getBytes(StandardCharsets.UTF_8));

    FilesStackTestResource.getStoragesService().failBulkDeleteFor(keptNodeId);

    purgeService.purgeTrashedNodes(0);

    // The node whose blob delete failed must NEVER be removed from the DB: this is the ordering
    // guarantee under test — a trashed node's row is deleted ONLY after its blob delete is
    // confirmed, never ahead of / independently from it.
    boolean keptNodePresent =
        QuarkusTransaction.requiringNew()
            .call(() -> nodeRepository.getNode(keptNodeId).isPresent());
    assertThat(keptNodePresent).as("row kept when blob delete failed").isTrue();
    assertThat(FilesStackTestResource.getStoragesService().has(keptNodeId, 1))
        .as("blob kept when its delete failed")
        .isTrue();

    // The other node's blob delete succeeded, so both row and blob are gone as usual.
    boolean removedNodePresent =
        QuarkusTransaction.requiringNew()
            .call(() -> nodeRepository.getNode(removedNodeId).isPresent());
    assertThat(removedNodePresent).as("unrelated row removed normally").isFalse();
    assertThat(FilesStackTestResource.getStoragesService().has(removedNodeId, 1))
        .as("unrelated blob removed normally")
        .isFalse();
  }

  // -------------------------------------------------------------------------------- purgeTombstones

  @Test
  void purgeTombstonesRemovesTombstoneAndBlobWhenBlobDeleteSucceeds() throws Exception {
    String ownerId = id();
    String nodeId = id();
    byte[] content = "tombstoned".getBytes(StandardCharsets.UTF_8);
    filestore.uploadPut(
        FilesIdentifier.of(nodeId, 1, ownerId), new ByteArrayInputStream(content), content.length);
    tombstoneRepository.createNewTombstone(nodeId, ownerId, 1);

    purgeService.purgeTombstones();

    java.util.List<Tombstone> tombstones =
        QuarkusTransaction.requiringNew().call(() -> tombstoneRepository.getTombstones());
    assertThat(tombstones)
        .filteredOn(t -> t.getNodeId().equals(nodeId))
        .as("tombstone removed after successful blob delete")
        .isEmpty();
    assertThat(FilesStackTestResource.getStoragesService().has(nodeId, 1))
        .as("blob removed after successful delete")
        .isFalse();
  }

  @Test
  void purgeTombstonesKeepsTombstoneAndBlobAndIncrementsAttemptsWhenBlobDeleteFails()
      throws Exception {
    String ownerId = id();
    String nodeId = id();
    byte[] content = "stranded".getBytes(StandardCharsets.UTF_8);
    filestore.uploadPut(
        FilesIdentifier.of(nodeId, 1, ownerId), new ByteArrayInputStream(content), content.length);
    tombstoneRepository.createNewTombstone(nodeId, ownerId, 1);
    FilesStackTestResource.getStoragesService().failBulkDeleteFor(nodeId);

    purgeService.purgeTombstones();

    java.util.List<Tombstone> tombstones =
        QuarkusTransaction.requiringNew().call(() -> tombstoneRepository.getTombstones());
    java.util.List<Tombstone> remaining =
        tombstones.stream().filter(t -> t.getNodeId().equals(nodeId)).toList();
    assertThat(remaining).as("tombstone kept when blob delete failed").hasSize(1);
    assertThat(remaining.get(0).getAttempts())
        .as("attempts incremented on a genuine per-blob failure")
        .isEqualTo(1);
    assertThat(FilesStackTestResource.getStoragesService().has(nodeId, 1))
        .as("blob kept when its delete failed")
        .isTrue();
  }
}
