// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.tasks;

import static org.assertj.core.api.Assertions.assertThat;

import com.zextras.carbonio.files.FilesStackTestResource;
import com.zextras.carbonio.files.dal.dao.ebean.ACL;
import com.zextras.carbonio.files.dal.dao.ebean.ACL.SharePermission;
import com.zextras.carbonio.files.dal.dao.ebean.FileVersion;
import com.zextras.carbonio.files.dal.dao.ebean.Node;
import com.zextras.carbonio.files.dal.dao.ebean.NodeCustomAttributes;
import com.zextras.carbonio.files.dal.dao.ebean.NodeType;
import com.zextras.carbonio.files.dal.dao.ebean.Share;
import com.zextras.carbonio.files.dal.dao.ebean.TrashedNode;
import com.zextras.carbonio.files.it.support.MockStoragesService;
import com.zextras.carbonio.files.message_broker.consumers.KeyValueChangedConsumer;
import com.zextras.carbonio.message_broker.events.services.service_discover.KeyValueChanged;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** The trash purge and the max-versions trim against the real database and storage client. */
@QuarkusTest
@QuarkusTestResource(FilesStackTestResource.class)
class BlobCleanupDalTest {

  private static final String OWNER = "11111111-1111-1111-1111-111111111111";
  private static final String SHAREE = "22222222-2222-2222-2222-222222222222";
  private static final long FORTY_DAYS_AGO = System.currentTimeMillis() - 40L * 86400 * 1000;

  @Inject PurgeService purgeService;
  @Inject KeyValueChangedConsumer keyValueChangedConsumer;
  @Inject EntityManager entityManager;

  private final MockStoragesService storages = FilesStackTestResource.getStoragesService();

  @AfterEach
  void resetStorages() {
    storages.reset();
  }

  private static String id() {
    return UUID.randomUUID().toString();
  }

  private void node(String id, String parent, String ancestors, NodeType type, long updatedAt) {
    Node node =
        new Node(id, OWNER, OWNER, parent, updatedAt, updatedAt, id, "", type, ancestors, 1L);
    if (type != NodeType.FOLDER) {
      node.setCurrentVersion(1);
    }
    entityManager.persist(node);
  }

  private void version(String nodeId, int version) {
    entityManager.persist(
        new FileVersion(nodeId, OWNER, FORTY_DAYS_AGO, version, "text/plain", 1L, "d", false));
    storages.seed(nodeId, version, "blob".getBytes(StandardCharsets.UTF_8));
  }

  private long count(String table, String nodeId) {
    return QuarkusTransaction.requiringNew()
        .call(
            () ->
                ((Number)
                        entityManager
                            .createNativeQuery(
                                "SELECT count(*) FROM " + table + " WHERE node_id = :id")
                            .setParameter("id", nodeId)
                            .getSingleResult())
                    .longValue());
  }

  /** folder (trashed top, shared, flagged) → file a (v1, v2) and sub → file b (v1). */
  private String[] givenAnExpiredTrashedTree() {
    String folder = id();
    String fileA = id();
    String sub = id();
    String fileB = id();
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              node(folder, "TRASH_ROOT", "TRASH_ROOT", NodeType.FOLDER, FORTY_DAYS_AGO);
              node(fileA, folder, "TRASH_ROOT," + folder, NodeType.TEXT, FORTY_DAYS_AGO);
              node(sub, folder, "TRASH_ROOT," + folder, NodeType.FOLDER, FORTY_DAYS_AGO);
              node(fileB, sub, "TRASH_ROOT," + folder + "," + sub, NodeType.TEXT, FORTY_DAYS_AGO);
              entityManager.persist(new TrashedNode(folder, "LOCAL_ROOT"));
              version(fileA, 1);
              version(fileA, 2);
              version(fileB, 1);
              entityManager.persist(
                  new Share(
                      fileA,
                      SHAREE,
                      ACL.decode(SharePermission.READ_ONLY),
                      FORTY_DAYS_AGO,
                      false,
                      false,
                      null));
              entityManager.persist(new NodeCustomAttributes(folder, SHAREE, true));
            });
    return new String[] {folder, fileA, sub, fileB};
  }

  @Test
  void anExpiredTrashedTreeLosesItsRowsAndThenItsBlobs() {
    String[] tree = givenAnExpiredTrashedTree();

    purgeService.purgeTrashedNodes(30);

    for (String nodeId : tree) {
      assertThat(count("node", nodeId)).as("node %s", nodeId).isZero();
      assertThat(count("tombstone", nodeId)).as("tombstones of %s", nodeId).isZero();
    }
    assertThat(count("revision", tree[1])).isZero();
    assertThat(count("share", tree[1])).isZero();
    assertThat(count("custom", tree[0])).isZero();
    assertThat(count("trashed", tree[0])).isZero();
    assertThat(storages.has(tree[1], 1)).isFalse();
    assertThat(storages.has(tree[1], 2)).isFalse();
    assertThat(storages.has(tree[3], 1)).isFalse();
  }

  @Test
  void aBlobTheStoreFailsToDeleteKeepsItsTombstoneForTheTombstonePurge() {
    String[] tree = givenAnExpiredTrashedTree();
    storages.failBulkDeleteForVersion(tree[3], 1);

    purgeService.purgeTrashedNodes(30);

    assertThat(count("node", tree[3])).isZero();
    assertThat(count("tombstone", tree[3])).isEqualTo(1);
    assertThat(count("tombstone", tree[1])).isZero();
    assertThat(storages.has(tree[3], 1)).isTrue();

    storages.resetBulkDeleteFailures();
    purgeService.purgeTombstones();

    assertThat(count("tombstone", tree[3])).isZero();
    assertThat(storages.has(tree[3], 1)).isFalse();
  }

  @Test
  void aRecentlyTrashedTreeIsKept() {
    String folder = id();
    String file = id();
    long now = System.currentTimeMillis();
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              node(folder, "TRASH_ROOT", "TRASH_ROOT", NodeType.FOLDER, now);
              node(file, folder, "TRASH_ROOT," + folder, NodeType.TEXT, FORTY_DAYS_AGO);
              version(file, 1);
            });

    purgeService.purgeTrashedNodes(30);

    assertThat(count("node", folder)).isEqualTo(1);
    assertThat(count("node", file)).isEqualTo(1);
    assertThat(storages.has(file, 1)).isTrue();
  }

  @Test
  void aLowerMaxVersionsTombstonesTheTrimmedVersionsAndThePurgeDeletesTheirBlobs() {
    String file = id();
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              node(file, "LOCAL_ROOT", "LOCAL_ROOT", NodeType.TEXT, FORTY_DAYS_AGO);
              for (int v = 1; v <= 4; v++) {
                version(file, v);
              }
              entityManager.find(Node.class, file).setCurrentVersion(4);
            });

    keyValueChangedConsumer.doHandle(
        new KeyValueChanged("carbonio-files/max-number-of-versions", "2"));

    assertThat(count("revision", file)).isEqualTo(2);
    assertThat(count("tombstone", file)).isEqualTo(2);
    assertThat(storages.has(file, 1)).isTrue();

    purgeService.purgeTombstones();

    assertThat(count("tombstone", file)).isZero();
    assertThat(storages.has(file, 1)).isFalse();
    assertThat(storages.has(file, 2)).isFalse();
    assertThat(storages.has(file, 3)).isTrue();
    assertThat(storages.has(file, 4)).isTrue();
  }
}
