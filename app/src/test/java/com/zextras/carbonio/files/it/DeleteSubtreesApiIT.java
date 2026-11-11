// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.it;

import com.zextras.carbonio.files.FilesStackTestResource;
import com.zextras.carbonio.files.TestUtils;
import com.zextras.carbonio.files.api.utilities.GraphqlCommandBuilder;
import com.zextras.carbonio.files.dal.dao.ebean.ACL.SharePermission;
import com.zextras.carbonio.files.it.support.AbstractFilesIT;
import io.restassured.RestAssured;
import io.restassured.response.Response;
import java.nio.charset.StandardCharsets;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class DeleteSubtreesApiIT extends AbstractFilesIT {

  private static final String A_ID = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
  private static final String B_ID = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";
  private static final String C_ID = "cccccccc-cccc-cccc-cccc-cccccccccccc";
  private static final String A_COOKIE = "ZM_AUTH_TOKEN=fake-token";
  private static final String B_COOKIE = "ZM_AUTH_TOKEN=fake-token-b";
  private static final String C_COOKIE = "ZM_AUTH_TOKEN=fake-token-c";

  private static final List<String> NODE_TABLES =
      List.of("revision", "share", "link", "collaboration_link", "custom", "trashed", "activity");

  @BeforeAll
  static void registerUsers() {
    FilesStackTestResource.getUserManagementService().registerToken("fake-token", A_ID);
    FilesStackTestResource.getUserManagementService().registerToken("fake-token-b", B_ID);
    FilesStackTestResource.getUserManagementService().registerToken("fake-token-c", C_ID);
  }

  private static byte[] content(String text) {
    return text.getBytes(StandardCharsets.UTF_8);
  }

  private record Fixture(
      String top,
      Set<String> tree,
      Set<String> folders,
      String sub,
      String deep,
      String file1,
      String file2,
      String file3,
      String trashedFileOfA,
      String trashedFileOfB,
      String trashedFolder,
      String trashedChild,
      String otherFolder,
      String otherFile,
      String bFile) {

    Set<String> files() {
      Set<String> files = new HashSet<>(tree);
      files.removeAll(folders);
      return files;
    }
  }

  private static String createCollaborationLink(String nodeId, String cookie) {
    Response response =
        graphql(
            GraphqlCommandBuilder.aMutationBuilder("createCollaborationLink")
                .withString("node_id", nodeId)
                .withEnumLiteral("permission", SharePermission.READ_ONLY.name())
                .withWantedResultFormat("{ id }")
                .build(),
            cookie);
    Map<String, Object> link =
        TestUtils.jsonResponseToMap(response.getBody().asString(), "createCollaborationLink");
    if (link == null) {
      throw new IllegalStateException("createCollaborationLink failed: " + response.getBody());
    }
    return (String) link.get("id");
  }

  private static void execute(String sql, Object... parameters) throws SQLException {
    try (Connection connection = jdbcConnection();
        PreparedStatement statement = connection.prepareStatement(sql)) {
      for (int i = 0; i < parameters.length; i++) {
        statement.setObject(i + 1, parameters[i]);
      }
      statement.executeUpdate();
    }
  }

  private static Fixture seedFixture() throws SQLException {
    String top = seedFolder("top", LOCAL_ROOT, A_COOKIE);
    String sub = seedFolder("sub", top, A_COOKIE);
    String deep = seedFolder("deep", sub, A_COOKIE);
    String file1 = seedFile("file1.txt", top, content("one"), A_COOKIE);
    seedVersion(file1, content("one-2"), "file1.txt", A_COOKIE);
    seedVersion(file1, content("one-3"), "file1.txt", A_COOKIE);
    String file2 = seedFile("file2.txt", sub, content("two"), A_COOKIE);
    seedVersion(file2, content("two-2"), "file2.txt", A_COOKIE);
    String file3 = seedFile("file3.txt", deep, content("three"), A_COOKIE);

    seedShare(top, B_ID, SharePermission.READ_AND_WRITE, A_COOKIE);
    seedShare(file2, C_ID, SharePermission.READ_ONLY, A_COOKIE);
    seedShare(deep, C_ID, SharePermission.READ_ONLY, A_COOKIE);
    seedLink(file2, A_COOKIE);
    seedLink(deep, A_COOKIE);
    createCollaborationLink(file1, A_COOKIE);
    createCollaborationLink(sub, A_COOKIE);
    seedFlag(file1, A_COOKIE);
    seedFlag(file3, B_COOKIE);
    execute(
        "INSERT INTO version_reservation (node_id, version, owner_id, reserved_at, state,"
            + " attempts) VALUES (?, 99, ?, ?, 'PENDING', 0)",
        file1,
        A_ID,
        System.currentTimeMillis());
    execute(
        "INSERT INTO activity (node_id, action_type, user_id, timestamp, info) VALUES (?, 1, ?, ?,"
            + " 'x')",
        file3,
        A_ID,
        System.currentTimeMillis());

    String trashedFileOfA = seedFile("trashed-a.txt", sub, content("ta"), A_COOKIE);
    seedShare(trashedFileOfA, C_ID, SharePermission.READ_ONLY, A_COOKIE);
    seedTrashed(trashedFileOfA, A_COOKIE);

    String trashedFolder = seedFolder("trashed-folder", deep, A_COOKIE);
    String trashedChild = seedFile("trashed-child.txt", trashedFolder, content("tc"), A_COOKIE);
    seedShare(trashedFolder, C_ID, SharePermission.READ_ONLY, A_COOKIE);
    seedShare(trashedChild, C_ID, SharePermission.READ_ONLY, A_COOKIE);
    seedTrashed(trashedFolder, A_COOKIE);

    String trashedFileOfB = seedFile("trashed-b.txt", LOCAL_ROOT, content("tb"), B_COOKIE);
    seedShare(trashedFileOfB, C_ID, SharePermission.READ_ONLY, B_COOKIE);
    seedTrashed(trashedFileOfB, B_COOKIE);
    forceTrashedOldParentId(trashedFileOfB, deep);

    String otherFolder = seedFolder("other", LOCAL_ROOT, A_COOKIE);
    String otherFile = seedFile("other.txt", otherFolder, content("o"), A_COOKIE);
    seedVersion(otherFile, content("o-2"), "other.txt", A_COOKIE);
    seedShare(otherFolder, B_ID, SharePermission.READ_ONLY, A_COOKIE);
    seedLink(otherFile, A_COOKIE);
    createCollaborationLink(otherFile, A_COOKIE);
    seedFlag(otherFile, A_COOKIE);

    String bFile = seedFile("b.txt", LOCAL_ROOT, content("b"), B_COOKIE);
    seedVersion(bFile, content("b-2"), "b.txt", B_COOKIE);
    seedShare(bFile, C_ID, SharePermission.READ_ONLY, B_COOKIE);
    seedLink(bFile, B_COOKIE);

    return new Fixture(
        top,
        Set.of(top, sub, deep, file1, file2, file3),
        Set.of(top, sub, deep),
        sub,
        deep,
        file1,
        file2,
        file3,
        trashedFileOfA,
        trashedFileOfB,
        trashedFolder,
        trashedChild,
        otherFolder,
        otherFile,
        bFile);
  }

  private static long rows(String table, Collection<String> nodeIds) throws SQLException {
    try (Connection connection = jdbcConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "SELECT count(*) FROM " + table + " WHERE node_id = ANY(?::character(36)[])")) {
      Array ids = connection.createArrayOf("varchar", nodeIds.toArray());
      statement.setArray(1, ids);
      try (ResultSet result = statement.executeQuery()) {
        result.next();
        return result.getLong(1);
      }
    }
  }

  private static Map<String, Long> footprint(Collection<String> nodeIds) throws SQLException {
    Map<String, Long> counts = new java.util.TreeMap<>();
    counts.put("node", rows("node", nodeIds));
    for (String table : NODE_TABLES) {
      counts.put(table, rows(table, nodeIds));
    }
    counts.put("tombstone", rows("tombstone", nodeIds));
    counts.put("version_reservation", rows("version_reservation", nodeIds));
    return counts;
  }

  private static Set<String> versionsOf(Collection<String> fileIds) throws SQLException {
    Set<String> result = new HashSet<>();
    try (Connection connection = jdbcConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "SELECT trim(n.node_id), r.version, n.owner_id FROM revision r JOIN node n ON"
                    + " n.node_id = r.node_id WHERE n.node_id = ANY(?::character(36)[])")) {
      statement.setArray(1, connection.createArrayOf("varchar", fileIds.toArray()));
      try (ResultSet resultSet = statement.executeQuery()) {
        while (resultSet.next()) {
          result.add(
              resultSet.getString(1) + ":" + resultSet.getInt(2) + ":" + resultSet.getString(3));
        }
      }
    }
    return result;
  }

  private static Set<String> tombstones() throws SQLException {
    Set<String> result = new HashSet<>();
    try (Connection connection = jdbcConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "SELECT trim(node_id), version, owner_id, attempts FROM tombstone");
        ResultSet resultSet = statement.executeQuery()) {
      while (resultSet.next()) {
        Assertions.assertThat(resultSet.getInt(4)).isZero();
        result.add(
            resultSet.getString(1) + ":" + resultSet.getInt(2) + ":" + resultSet.getString(3));
      }
    }
    return result;
  }

  private static Response deleteNodes(String... ids) {
    return graphql(
        GraphqlCommandBuilder.aMutationBuilder("deleteNodes")
            .withListOfStrings("node_ids", ids)
            .withWantedResultFormat("")
            .build(),
        A_COOKIE);
  }

  private static Response deleteAccount(String userId) {
    return RestAssured.given()
        .contentType("application/json")
        .body("{\"userId\":\"" + userId + "\"}")
        .delete("/internal/nodes");
  }

  private static Set<String> survivorsOutsideTheTree(Fixture f) {
    Set<String> ids = new HashSet<>(Set.of(f.otherFolder, f.otherFile, f.bFile));
    ids.addAll(Set.of(f.trashedFileOfA, f.trashedFileOfB, f.trashedFolder, f.trashedChild));
    return ids;
  }

  private static void assertBlobsExist(Collection<String> fileIds, boolean expected)
      throws SQLException {
    for (String entry : versionsOf(fileIds)) {
      String[] parts = entry.split(":");
      Assertions.assertThat(
              FilesStackTestResource.getStoragesService().has(parts[0], Integer.parseInt(parts[1])))
          .as("blob %s", entry)
          .isEqualTo(expected);
    }
  }

  private static void assertPreconditions(Fixture f) throws SQLException {
    Map<String, Long> before = footprint(f.tree);
    before.forEach(
        (table, count) -> {
          if (!"tombstone".equals(table) && !"trashed".equals(table)) {
            Assertions.assertThat(count).as("seeded rows in %s", table).isPositive();
          }
        });
    Assertions.assertThat(rows("node", Set.of(f.trashedFileOfA, f.trashedFileOfB))).isEqualTo(2);
    for (String trashed : Set.of(f.trashedFileOfA, f.trashedFileOfB, f.trashedFolder)) {
      Assertions.assertThat(rows("share", Set.of(trashed)))
          .as("shares of %s", trashed)
          .isPositive();
    }
    Assertions.assertThat(rows("trashed", Set.of(f.trashedFileOfA, f.trashedFileOfB))).isEqualTo(2);
  }

  private static void assertTreeGone(Fixture f) throws SQLException {
    Assertions.assertThat(rows("node", f.tree)).isZero();
    for (String table : NODE_TABLES) {
      Assertions.assertThat(rows(table, f.tree)).as("rows left in %s", table).isZero();
    }
    Assertions.assertThat(rows("version_reservation", Set.of(f.file1))).isEqualTo(1);
  }

  @Test
  void deleteNodesRemovesTheSubtreeAndTombstonesEveryRevisionUnderItsOwner() throws SQLException {
    Fixture f = seedFixture();
    assertPreconditions(f);
    Set<String> expectedTombstones = versionsOf(f.files());
    Assertions.assertThat(expectedTombstones).hasSize(6);
    Map<String, Long> outsideBefore = footprint(survivorsOutsideTheTree(f));
    Set<String> trashedByOldParent = Set.of(f.trashedFileOfA, f.trashedFileOfB, f.trashedFolder);
    long sharesOfTrashed = rows("share", trashedByOldParent);
    Map<String, Long> trashedChildBefore = footprint(Set.of(f.trashedChild));
    FilesStackTestResource.getStoragesService().setBulkDeleteAlwaysThrows(true);

    Response response = deleteNodes(f.top);

    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    Assertions.assertThat(TestUtils.jsonResponseToErrors(response.getBody().asString())).isEmpty();
    assertTreeGone(f);
    Assertions.assertThat(tombstones()).isEqualTo(expectedTombstones);
    Map<String, Long> outsideAfter = footprint(survivorsOutsideTheTree(f));
    outsideBefore.forEach(
        (table, count) -> {
          if ("share".equals(table)) {
            Assertions.assertThat(outsideAfter.get(table)).isEqualTo(count - sharesOfTrashed);
          } else {
            Assertions.assertThat(outsideAfter.get(table)).as(table).isEqualTo(count);
          }
        });
    Assertions.assertThat(rows("share", trashedByOldParent)).isZero();
    Assertions.assertThat(footprint(Set.of(f.trashedChild))).isEqualTo(trashedChildBefore);
    Assertions.assertThat(rows("trashed", Set.of(f.trashedFileOfA, f.trashedFileOfB))).isEqualTo(2);
    Assertions.assertThat(rows("node", trashedByOldParent)).isEqualTo(3);
  }

  @Test
  void deleteNodesDeletesTheBlobsOfTheSubtreeAfterCommit() throws SQLException {
    Fixture f = seedFixture();
    Set<String> files = f.files();
    assertBlobsExist(files, true);

    Response response = deleteNodes(f.top);

    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    assertTreeGone(f);
    assertBlobsExist(files, false);
    Assertions.assertThat(tombstones()).isEmpty();
    Assertions.assertThat(FilesStackTestResource.getStoragesService().has(f.otherFile, 1)).isTrue();
    Assertions.assertThat(FilesStackTestResource.getStoragesService().has(f.bFile, 2)).isTrue();
  }

  @Test
  void deleteNodesOfSeveralRootsRemovesEachSubtreeOnce() throws SQLException {
    Fixture f = seedFixture();
    Set<String> expected = versionsOf(f.files());
    FilesStackTestResource.getStoragesService().setBulkDeleteAlwaysThrows(true);

    Response response = deleteNodes(f.top, f.deep, f.file3, f.file1);

    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    Assertions.assertThat(TestUtils.jsonResponseToErrors(response.getBody().asString())).isEmpty();
    assertTreeGone(f);
    Assertions.assertThat(tombstones()).isEqualTo(expected);
  }

  @Test
  void deleteNodesOfOverlappingRootsDeletesEachBlobAndWritesEachTombstoneOnce()
      throws SQLException {
    Fixture f = seedFixture();
    Set<String> expected = versionsOf(f.files());
    Set<String> expectedKeys = new HashSet<>();
    expected.forEach(
        entry -> expectedKeys.add(entry.substring(0, entry.lastIndexOf(':')).replace(':', '/')));

    Response response = deleteNodes(f.top, f.sub, f.deep, f.file3, f.file1);

    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    List<String> requested = FilesStackTestResource.getStoragesService().bulkDeleteRequests();
    Assertions.assertThat(requested).doesNotHaveDuplicates();
    Assertions.assertThat(requested).containsExactlyInAnyOrderElementsOf(expectedKeys);
  }

  @Test
  void deleteNodesKeepsAnExistingTombstoneOfAnotherOwnerUnchanged() throws SQLException {
    Fixture f = seedFixture();
    execute(
        "INSERT INTO tombstone (node_id, owner_id, timestamp, version, attempts) VALUES (?, ?,"
            + " 12345, 1, 2)",
        f.file1,
        B_ID);
    FilesStackTestResource.getStoragesService().setBulkDeleteAlwaysThrows(true);

    Response response = deleteNodes(f.top);

    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    try (Connection connection = jdbcConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "SELECT owner_id, timestamp, attempts FROM tombstone WHERE node_id = ? AND version"
                    + " = 1")) {
      statement.setString(1, f.file1);
      try (ResultSet rows = statement.executeQuery()) {
        Assertions.assertThat(rows.next()).isTrue();
        Assertions.assertThat(rows.getString(1)).isEqualTo(B_ID);
        Assertions.assertThat(rows.getLong(2)).isEqualTo(12345L);
        Assertions.assertThat(rows.getInt(3)).isEqualTo(2);
        Assertions.assertThat(rows.next()).isFalse();
      }
    }
    assertTreeGone(f);
  }

  @Test
  void deleteNodesStillDeletesTheBlobOfARevisionThatAlreadyHadATombstone() throws SQLException {
    Fixture f = seedFixture();
    execute(
        "INSERT INTO tombstone (node_id, owner_id, timestamp, version, attempts) VALUES (?, ?,"
            + " 12345, 1, 2)",
        f.file1,
        B_ID);
    Assertions.assertThat(FilesStackTestResource.getStoragesService().has(f.file1, 1)).isTrue();

    Response response = deleteNodes(f.top);

    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    Assertions.assertThat(FilesStackTestResource.getStoragesService().has(f.file1, 1)).isFalse();
    Assertions.assertThat(FilesStackTestResource.getStoragesService().bulkDeleteRequests())
        .contains(f.file1 + "/1");
  }

  @Test
  void deleteNodesOfAnInnerFolderKeepsTheRestOfTheTree() throws SQLException {
    Fixture f = seedFixture();
    Set<String> inner = Set.of(f.deep, f.file3);
    Set<String> expected = versionsOf(Set.of(f.file3));
    Set<String> kept = new HashSet<>(f.tree);
    kept.removeAll(inner);
    Map<String, Long> keptBefore = footprint(kept);
    long sharesOfTrashedUnderSub = rows("share", Set.of(f.trashedFileOfA));
    Assertions.assertThat(sharesOfTrashedUnderSub).isPositive();
    FilesStackTestResource.getStoragesService().setBulkDeleteAlwaysThrows(true);

    Response response = deleteNodes(f.deep);

    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    Assertions.assertThat(rows("node", inner)).isZero();
    for (String table : NODE_TABLES) {
      Assertions.assertThat(rows(table, inner)).as(table).isZero();
    }
    Assertions.assertThat(tombstones()).isEqualTo(expected);
    Map<String, Long> keptAfter = footprint(kept);
    keptBefore.forEach(
        (table, count) -> {
          if (!"tombstone".equals(table)) {
            Assertions.assertThat(keptAfter.get(table)).as(table).isEqualTo(count);
          }
        });
    Assertions.assertThat(rows("share", Set.of(f.trashedFileOfB))).isZero();
    Assertions.assertThat(rows("share", Set.of(f.trashedFolder))).isZero();
    Assertions.assertThat(rows("share", Set.of(f.trashedFileOfA)))
        .isEqualTo(sharesOfTrashedUnderSub);
  }

  @Test
  void deleteAllNodesOfAUserRemovesTheirNodesAndTombstonesTheirRevisions() throws SQLException {
    Fixture f = seedFixture();
    assertPreconditions(f);
    Set<String> ownedByA = new HashSet<>(f.tree);
    ownedByA.addAll(
        Set.of(f.trashedFileOfA, f.trashedFolder, f.trashedChild, f.otherFolder, f.otherFile));
    Set<String> expected = versionsOf(ownedByA);
    Set<String> bNodes = Set.of(f.bFile, f.trashedFileOfB);
    Map<String, Long> bBefore = footprint(Set.of(f.bFile));
    FilesStackTestResource.getStoragesService().setBulkDeleteAlwaysThrows(true);

    Response response = deleteAccount(A_ID);

    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    Assertions.assertThat(response.jsonPath().getBoolean("deleted")).isTrue();
    assertTreeGone(f);
    Assertions.assertThat(rows("node", ownedByA)).isZero();
    for (String table : NODE_TABLES) {
      Assertions.assertThat(rows(table, ownedByA)).as(table).isZero();
    }
    Assertions.assertThat(tombstones()).isEqualTo(expected);
    Assertions.assertThat(rows("node", bNodes)).isEqualTo(2);
    Assertions.assertThat(footprint(Set.of(f.bFile))).isEqualTo(bBefore);
    Assertions.assertThat(rows("trashed", Set.of(f.trashedFileOfB))).isEqualTo(1);
    Assertions.assertThat(rows("share", Set.of(f.trashedFileOfB))).isZero();
  }

  @Test
  void deleteAllNodesOfAUserDeletesTheirBlobsAfterCommitAndKeepsOtherUsersBlobs()
      throws SQLException {
    Fixture f = seedFixture();
    Set<String> ownedByA = new HashSet<>(f.tree);
    ownedByA.addAll(Set.of(f.trashedFileOfA, f.trashedChild, f.otherFile));
    assertBlobsExist(ownedByA, true);

    Response response = deleteAccount(A_ID);

    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    assertBlobsExist(ownedByA, false);
    Assertions.assertThat(tombstones()).isEmpty();
    Assertions.assertThat(FilesStackTestResource.getStoragesService().has(f.bFile, 1)).isTrue();
    Assertions.assertThat(FilesStackTestResource.getStoragesService().has(f.bFile, 2)).isTrue();
    Assertions.assertThat(FilesStackTestResource.getStoragesService().has(f.trashedFileOfB, 1))
        .isTrue();
  }

  @Test
  void deleteAllNodesOfAUserTombstonesTheFilesOfOtherOwnersInTheirFoldersUnderTheirOwner()
      throws SQLException {
    Fixture f = seedFixture();
    execute("UPDATE node SET owner_id = ? WHERE node_id = ?", B_ID, f.file3);
    FilesStackTestResource.getStoragesService().setBulkDeleteAlwaysThrows(true);

    Response response = deleteAccount(A_ID);

    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    Assertions.assertThat(rows("node", Set.of(f.file3, f.deep))).isZero();
    Assertions.assertThat(tombstones()).contains(f.file3 + ":1:" + B_ID);
  }
}
