// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.tasks;

import static org.assertj.core.api.Assertions.assertThat;

import com.zextras.carbonio.files.FilesStackTestResource;
import io.quarkus.arc.Arc;
import io.quarkus.arc.ManagedContext;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

@QuarkusTest
@QuarkusTestResource(FilesStackTestResource.class)
class PurgeTrashedTreeDalTest {

  private static final String OWNER_1 = UUID.randomUUID().toString();
  private static final String OWNER_2 = UUID.randomUUID().toString();
  private static final String SHARE_TARGET = UUID.randomUUID().toString();
  private static final long THIRTY_ONE_DAYS_AGO = System.currentTimeMillis() - 31L * 86400 * 1000;
  private static final List<String> NODE_TABLES =
      List.of("revision", "share", "link", "collaboration_link", "custom", "trashed", "activity");

  @Inject PurgeService purgeService;
  @Inject EntityManager entityManager;

  @AfterEach
  void deleteFixtures() {
    FilesStackTestResource.getStoragesService().reset();
    inTransaction(
        "DELETE FROM tombstone WHERE owner_id IN ('" + OWNER_1 + "', '" + OWNER_2 + "')",
        "DELETE FROM node WHERE owner_id IN ('" + OWNER_1 + "', '" + OWNER_2 + "')");
  }

  private void inTransaction(String... statements) {
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              for (String statement : statements) {
                entityManager.createNativeQuery(statement).executeUpdate();
              }
            });
  }

  private long count(String sql) {
    return QuarkusTransaction.requiringNew()
        .call(() -> ((Number) entityManager.createNativeQuery(sql).getSingleResult()).longValue());
  }

  @SuppressWarnings("unchecked")
  private Set<String> strings(String sql) {
    return QuarkusTransaction.requiringNew()
        .call(() -> new HashSet<>(entityManager.createNativeQuery(sql).getResultList()));
  }

  private String folder(String owner, String parent, String ancestors, long timestamp) {
    String id = UUID.randomUUID().toString();
    inTransaction(
        "INSERT INTO node (owner_id, node_id, folder_id, name, node_type, node_category,"
            + " description, creation_timestamp, updated_timestamp, creator_id, editor_id,"
            + " ancestor_ids, size) VALUES ('"
            + owner
            + "', '"
            + id
            + "', '"
            + parent
            + "', 'd-"
            + id
            + "', 'FOLDER', 0, '', "
            + timestamp
            + ", "
            + timestamp
            + ", '"
            + owner
            + "', '"
            + owner
            + "', '"
            + ancestors
            + "', 0)");
    return id;
  }

  private void files(String owner, String parent, String ancestors, int number, int twoVersions) {
    inTransaction(
        "INSERT INTO node (owner_id, node_id, folder_id, name, node_type, node_category,"
            + " description, creation_timestamp, updated_timestamp, creator_id, editor_id,"
            + " current_version, ancestor_ids, size) SELECT '"
            + owner
            + "', CAST(gen_random_uuid() AS TEXT), '"
            + parent
            + "', 'f' || g, 'TEXT', 1, '', "
            + THIRTY_ONE_DAYS_AGO
            + ", "
            + THIRTY_ONE_DAYS_AGO
            + ", '"
            + owner
            + "', '"
            + owner
            + "', 1, '"
            + ancestors
            + "', 1 FROM generate_series(1, "
            + number
            + ") g",
        "INSERT INTO revision (node_id, version, mime_type, size, digest, editor_id, timestamp)"
            + " SELECT node_id, v, 'text/plain', 1, 'd', owner_id, "
            + THIRTY_ONE_DAYS_AGO
            + " FROM node CROSS JOIN generate_series(1, 2) v WHERE folder_id = '"
            + parent
            + "' AND node_type = 'TEXT' AND (v = 1 OR CAST(substr(name, 2) AS INT) <= "
            + twoVersions
            + ")");
  }

  private void trash(String id, String oldParent) {
    inTransaction(
        "INSERT INTO trashed (node_id, parent_id) VALUES ('" + id + "', '" + oldParent + "')");
  }

  private String idsOf(String sqlWhere) {
    return "SELECT trim(node_id) FROM node WHERE " + sqlWhere;
  }

  private void purgeInRequestContext() {
    ManagedContext requestContext = Arc.container().requestContext();
    requestContext.activate();
    try {
      purgeService.purgeTrashedNodes(30);
    } finally {
      requestContext.terminate();
    }
  }

  private record Fixture(
      String top, String sub, String survivor, String otherExpiredTop, String freshTop) {}

  private Fixture seed() {
    String top = folder(OWNER_1, "TRASH_ROOT", "TRASH_ROOT", THIRTY_ONE_DAYS_AGO);
    trash(top, "LOCAL_ROOT");
    String sub = folder(OWNER_1, top, "TRASH_ROOT," + top, THIRTY_ONE_DAYS_AGO);
    String deep = folder(OWNER_2, sub, "TRASH_ROOT," + top + "," + sub, THIRTY_ONE_DAYS_AGO);
    files(OWNER_1, top, "TRASH_ROOT," + top, 600, 100);
    files(OWNER_2, sub, "TRASH_ROOT," + top + "," + sub, 300, 50);
    files(OWNER_2, deep, "TRASH_ROOT," + top + "," + sub + "," + deep, 250, 10);
    inTransaction(
        "INSERT INTO share (node_id, rights, timestamp, target_uuid, direct, created_via_link)"
            + " SELECT node_id, 1, 1, '"
            + SHARE_TARGET
            + "', true, false FROM node WHERE folder_id IN ('"
            + top
            + "', '"
            + sub
            + "') AND node_type = 'TEXT' AND name IN ('f1', 'f2')",
        "INSERT INTO share (node_id, rights, timestamp, target_uuid, direct, created_via_link)"
            + " VALUES ('"
            + sub
            + "', 1, 1, '"
            + SHARE_TARGET
            + "', true, false)",
        "INSERT INTO link (id, node_id, public_id, created_at) SELECT CAST(gen_random_uuid() AS"
            + " TEXT), node_id, 'p' || substr(CAST(node_id AS TEXT), 1, 8), 1 FROM node WHERE"
            + " folder_id = '"
            + deep
            + "' AND name = 'f1'",
        "INSERT INTO collaboration_link (id, node_id, invitation_id, created_at, permissions)"
            + " SELECT gen_random_uuid(), node_id, substr(CAST(node_id AS TEXT), 1, 8), now(), 1"
            + " FROM node WHERE folder_id = '"
            + deep
            + "' AND name = 'f2'",
        "INSERT INTO custom (node_id, user_id, star, extra) SELECT node_id, '"
            + SHARE_TARGET
            + "', true, '' FROM node WHERE folder_id = '"
            + top
            + "' AND name = 'f3'",
        "INSERT INTO activity (node_id, action_type, user_id, timestamp, info) SELECT node_id, 1,"
            + " '"
            + OWNER_1
            + "', 1, 'x' FROM node WHERE folder_id = '"
            + top
            + "' AND name = 'f4'");

    String survivor = folder(OWNER_1, "TRASH_ROOT", "TRASH_ROOT", System.currentTimeMillis());
    trash(survivor, sub);
    inTransaction(
        "INSERT INTO share (node_id, rights, timestamp, target_uuid, direct, created_via_link)"
            + " VALUES ('"
            + survivor
            + "', 1, 1, '"
            + SHARE_TARGET
            + "', true, false)");
    String freshTop = folder(OWNER_1, "TRASH_ROOT", "TRASH_ROOT", System.currentTimeMillis());
    trash(freshTop, "LOCAL_ROOT");
    files(OWNER_1, freshTop, "TRASH_ROOT," + freshTop, 3, 1);

    String otherExpiredTop = folder(OWNER_2, "TRASH_ROOT", "TRASH_ROOT", THIRTY_ONE_DAYS_AGO);
    trash(otherExpiredTop, "LOCAL_ROOT");
    files(OWNER_2, otherExpiredTop, "TRASH_ROOT," + otherExpiredTop, 4, 2);
    return new Fixture(top, sub, survivor, otherExpiredTop, freshTop);
  }

  private String inList(Set<String> ids) {
    return String.join(", ", ids.stream().map(id -> "'" + id + "'").toList());
  }

  private Set<String> purgedNodeIds(Fixture f) {
    return strings(
        "SELECT trim(node_id) FROM node WHERE node_id IN ('"
            + f.top
            + "', '"
            + f.otherExpiredTop
            + "') OR ancestor_ids LIKE '%"
            + f.top
            + "%' OR ancestor_ids LIKE '%"
            + f.otherExpiredTop
            + "%'");
  }

  @Test
  void purgesEveryExpiredTreeWithAllItsRowsAndTombstonesEveryRevision() {
    Fixture f = seed();
    Set<String> purged = purgedNodeIds(f);
    assertThat(purged).hasSize(3 + 600 + 300 + 250 + 1 + 4);
    String purgedList = inList(purged);
    Set<String> expectedTombstones =
        strings(
            "SELECT trim(n.node_id) || ':' || r.version || ':' || n.owner_id FROM revision r JOIN"
                + " node n ON n.node_id = r.node_id WHERE trim(n.node_id) IN ("
                + purgedList
                + ")");
    assertThat(expectedTombstones).hasSizeGreaterThan(1200);
    for (String table : NODE_TABLES) {
      if (!"trashed".equals(table)) {
        assertThat(
                count(
                    "SELECT count(*) FROM "
                        + table
                        + " WHERE trim(node_id) IN ("
                        + purgedList
                        + ")"))
            .as("seeded rows in %s", table)
            .isPositive();
      }
    }
    long freshRows =
        count(
            "SELECT count(*) FROM node WHERE owner_id = '"
                + OWNER_1
                + "' AND (node_id = '"
                + f.freshTop
                + "' OR folder_id = '"
                + f.freshTop
                + "')");
    assertThat(count("SELECT count(*) FROM share WHERE node_id = '" + f.survivor + "'"))
        .isEqualTo(1);
    FilesStackTestResource.getStoragesService().setBulkDeleteAlwaysThrows(true);

    purgeInRequestContext();

    assertThat(count("SELECT count(*) FROM node WHERE trim(node_id) IN (" + purgedList + ")"))
        .isZero();
    for (String table : NODE_TABLES) {
      assertThat(
              count(
                  "SELECT count(*) FROM " + table + " WHERE trim(node_id) IN (" + purgedList + ")"))
          .as("rows left in %s", table)
          .isZero();
    }
    assertThat(
            strings(
                "SELECT trim(node_id) || ':' || version || ':' || owner_id FROM tombstone WHERE"
                    + " owner_id IN ('"
                    + OWNER_1
                    + "', '"
                    + OWNER_2
                    + "')"))
        .isEqualTo(expectedTombstones);
    assertThat(
            count(
                "SELECT count(*) FROM node WHERE owner_id = '"
                    + OWNER_1
                    + "' AND (node_id = '"
                    + f.freshTop
                    + "' OR folder_id = '"
                    + f.freshTop
                    + "')"))
        .isEqualTo(freshRows);
    assertThat(
            count(
                "SELECT count(*) FROM revision WHERE trim(node_id) IN ("
                    + inList(strings(idsOf("folder_id = '" + f.freshTop + "'")))
                    + ")"))
        .isEqualTo(4);
    assertThat(count("SELECT count(*) FROM node WHERE node_id = '" + f.survivor + "'"))
        .isEqualTo(1);
    assertThat(count("SELECT count(*) FROM trashed WHERE node_id = '" + f.survivor + "'"))
        .isEqualTo(1);
    assertThat(count("SELECT count(*) FROM share WHERE node_id = '" + f.survivor + "'")).isZero();
  }

  @Test
  void purgeDeletesTheBlobsOfThePurgedRevisionsAfterCommit() {
    Fixture f = seed();
    Set<String> purged = purgedNodeIds(f);
    String purgedList = inList(purged);
    Set<String> fileIds =
        strings(
            "SELECT trim(node_id) FROM node WHERE node_type = 'TEXT' AND trim(node_id) IN ("
                + purgedList
                + ")");
    Set<String> expectedKeys =
        strings(
            "SELECT trim(node_id) || '/' || version FROM revision WHERE trim(node_id) IN ("
                + purgedList
                + ")");
    assertThat(expectedKeys).isNotEmpty();

    purgeInRequestContext();

    assertThat(FilesStackTestResource.getStoragesService().bulkDeleteRequests())
        .containsExactlyInAnyOrderElementsOf(expectedKeys);
    assertThat(count("SELECT count(*) FROM node WHERE trim(node_id) IN (" + purgedList + ")"))
        .isZero();
    assertThat(
            count(
                "SELECT count(*) FROM tombstone WHERE owner_id IN ('"
                    + OWNER_1
                    + "', '"
                    + OWNER_2
                    + "')"))
        .isZero();
    assertThat(fileIds).hasSize(600 + 300 + 250 + 4);
  }
}
