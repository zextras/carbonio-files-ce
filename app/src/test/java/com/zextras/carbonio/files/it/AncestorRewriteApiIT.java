// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.it;

import com.zextras.carbonio.files.FilesStackTestResource;
import com.zextras.carbonio.files.TestUtils;
import com.zextras.carbonio.files.api.utilities.GraphqlCommandBuilder;
import com.zextras.carbonio.files.it.support.AbstractFilesIT;
import io.restassured.response.Response;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class AncestorRewriteApiIT extends AbstractFilesIT {

  private static final String USER_ID = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
  private static final String COOKIE = "ZM_AUTH_TOKEN=fake-token";
  private static final String TRASH_ROOT = "TRASH_ROOT";

  private record Row(String ancestors, String parentId, long updatedAt) {}

  private static final class Tree {
    String top;
    String f1;
    String f2;
    String f3;
    String leaf;
    String file1;
    String file2;
    String topFile;
    String marked;
    String markedFile;
    final Map<String, List<String>> chains = new LinkedHashMap<>();

    void index() {
      chains.put(f1, List.of(top));
      chains.put(f2, List.of(top, f1));
      chains.put(f3, List.of(top, f1, f2));
      chains.put(leaf, List.of(top, f1, f2, f3));
      chains.put(file1, List.of(top, f1));
      chains.put(file2, List.of(top, f1, f2));
      chains.put(topFile, List.of(top));
      chains.put(marked, List.of(top));
      chains.put(markedFile, List.of(top, marked));
    }
  }

  @BeforeAll
  static void registerUsers() {
    FilesStackTestResource.getUserManagementService().registerToken("fake-token", USER_ID);
  }

  private static String folder(String name, String parentId) {
    return seedFolder(name, parentId, COOKIE);
  }

  private static String file(String name, String parentId) {
    return seedFile(name, parentId, "content".getBytes(StandardCharsets.UTF_8), COOKIE);
  }

  private static Tree buildTree() throws SQLException {
    Tree tree = new Tree();
    tree.top = folder("top", LOCAL_ROOT);
    tree.f1 = folder("f1", tree.top);
    tree.f2 = folder("f2", tree.f1);
    tree.f3 = folder("f3", tree.f2);
    tree.leaf = file("leaf.txt", tree.f3);
    tree.file1 = file("file1.txt", tree.f1);
    tree.file2 = file("file2.txt", tree.f2);
    tree.topFile = file("topFile.txt", tree.top);
    tree.marked = folder("marked", tree.top);
    tree.markedFile = file("markedFile.txt", tree.marked);
    try (Connection connection = jdbcConnection();
        PreparedStatement statement =
            connection.prepareStatement("INSERT INTO trashed (node_id, parent_id) VALUES (?, ?)")) {
      statement.setString(1, tree.marked);
      statement.setString(2, tree.top);
      statement.executeUpdate();
    }
    tree.index();
    return tree;
  }

  private static Row row(String nodeId) throws SQLException {
    try (Connection connection = jdbcConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "SELECT ancestor_ids, folder_id, updated_timestamp FROM node WHERE node_id = ?")) {
      statement.setString(1, nodeId);
      try (ResultSet rows = statement.executeQuery()) {
        Assertions.assertThat(rows.next()).isTrue();
        String parent = rows.getString(2);
        return new Row(rows.getString(1), parent == null ? null : parent.trim(), rows.getLong(3));
      }
    }
  }

  private static Map<String, Long> timestamps(Tree tree) throws SQLException {
    Map<String, Long> result = new LinkedHashMap<>();
    for (String id : tree.chains.keySet()) {
      result.put(id, row(id).updatedAt());
    }
    return result;
  }

  private static void assertDescendants(Tree tree, String topAncestors) throws SQLException {
    for (Map.Entry<String, List<String>> entry : tree.chains.entrySet()) {
      String expected = topAncestors + "," + String.join(",", entry.getValue());
      Assertions.assertThat(row(entry.getKey()).ancestors())
          .as("ancestor_ids of %s", entry.getKey())
          .isEqualTo(expected);
    }
    Assertions.assertThat(row(tree.f1).parentId()).isEqualTo(tree.top);
    Assertions.assertThat(row(tree.f2).parentId()).isEqualTo(tree.f1);
    Assertions.assertThat(row(tree.f3).parentId()).isEqualTo(tree.f2);
    Assertions.assertThat(row(tree.leaf).parentId()).isEqualTo(tree.f3);
    Assertions.assertThat(row(tree.marked).parentId()).isEqualTo(tree.top);
    Assertions.assertThat(row(tree.markedFile).parentId()).isEqualTo(tree.marked);
  }

  private static void assertTimestampsAdvanced(Map<String, Long> before) throws SQLException {
    for (String id : before.keySet()) {
      Assertions.assertThat(row(id).updatedAt())
          .as("updated_timestamp of %s", id)
          .isGreaterThan(before.get(id));
    }
  }

  private static void mutate(String mutationName, String[] ids, String destination) {
    GraphqlCommandBuilder builder =
        GraphqlCommandBuilder.aMutationBuilder(mutationName).withListOfStrings("node_ids", ids);
    if (destination != null) {
      builder = builder.withString("destination_id", destination);
    }
    String payload = builder.withWantedResultFormat(destination == null ? "" : "{ id }").build();
    Response response = graphql(payload, COOKIE);
    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    Assertions.assertThat(TestUtils.jsonResponseToErrors(response.getBody().asString())).isEmpty();
  }

  private static void trash(String... ids) {
    tickClock();
    mutate("trashNodes", ids, null);
  }

  private static void restore(String... ids) {
    tickClock();
    Response response =
        graphql(
            GraphqlCommandBuilder.aMutationBuilder("restoreNodes")
                .withListOfStrings("node_ids", ids)
                .withWantedResultFormat("{ id }")
                .build(),
            COOKIE);
    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    Assertions.assertThat(TestUtils.jsonResponseToErrors(response.getBody().asString())).isEmpty();
  }

  private static void move(String destination, String... ids) {
    tickClock();
    mutate("moveNodes", ids, destination);
  }

  @Test
  void givenADeepTreeTrashRestoreAndMoveShouldRewriteEveryDescendantPath() throws SQLException {
    Tree tree = buildTree();
    assertDescendants(tree, "LOCAL_ROOT");

    Map<String, Long> before = timestamps(tree);
    trash(tree.top);
    Assertions.assertThat(row(tree.top).ancestors()).isEqualTo(TRASH_ROOT);
    assertDescendants(tree, TRASH_ROOT);
    assertTimestampsAdvanced(before);

    before = timestamps(tree);
    restore(tree.top);
    Assertions.assertThat(row(tree.top).ancestors()).isEqualTo("LOCAL_ROOT");
    assertDescendants(tree, "LOCAL_ROOT");
    assertTimestampsAdvanced(before);

    String d1 = folder("d1", LOCAL_ROOT);
    String d2 = folder("d2", d1);
    String d3 = folder("d3", d2);
    before = timestamps(tree);
    move(d3, tree.top);
    String movedTopAncestors = "LOCAL_ROOT," + d1 + "," + d2 + "," + d3;
    Assertions.assertThat(row(tree.top).ancestors()).isEqualTo(movedTopAncestors);
    assertDescendants(tree, movedTopAncestors);
    assertTimestampsAdvanced(before);

    before = timestamps(tree);
    move(LOCAL_ROOT, tree.top);
    Assertions.assertThat(row(tree.top).ancestors()).isEqualTo("LOCAL_ROOT");
    assertDescendants(tree, "LOCAL_ROOT");
    assertTimestampsAdvanced(before);

    before = timestamps(tree);
    trash(tree.top);
    restore(tree.top);
    move(d1, tree.top);
    assertDescendants(tree, "LOCAL_ROOT," + d1);
    assertTimestampsAdvanced(before);
  }

  @Test
  void givenASubtreeTrashedFromANestedFolderRestoreShouldRewriteEveryDescendantPath()
      throws SQLException {
    String d1 = folder("d1", LOCAL_ROOT);
    String d2 = folder("d2", d1);
    String d3 = folder("d3", d2);
    String sub = folder("sub", d3);
    String subChild = folder("subChild", sub);
    String subGrandChild = folder("subGrandChild", subChild);
    String subFile = file("subFile.txt", sub);
    String childFile = file("childFile.txt", subChild);
    String grandChildFile = file("grandChildFile.txt", subGrandChild);

    String parentPath = "LOCAL_ROOT," + d1 + "," + d2 + "," + d3;
    Map<String, String> expected = new LinkedHashMap<>();
    expected.put(sub, parentPath);
    expected.put(subChild, parentPath + "," + sub);
    expected.put(subFile, parentPath + "," + sub);
    expected.put(subGrandChild, parentPath + "," + sub + "," + subChild);
    expected.put(childFile, parentPath + "," + sub + "," + subChild);
    expected.put(grandChildFile, parentPath + "," + sub + "," + subChild + "," + subGrandChild);
    for (Map.Entry<String, String> entry : expected.entrySet()) {
      Assertions.assertThat(row(entry.getKey()).ancestors()).isEqualTo(entry.getValue());
    }

    trash(sub);
    Assertions.assertThat(row(sub).ancestors()).isEqualTo(TRASH_ROOT);
    Assertions.assertThat(row(subChild).ancestors()).isEqualTo(TRASH_ROOT + "," + sub);

    Map<String, Long> before = new LinkedHashMap<>();
    for (String id : expected.keySet()) {
      before.put(id, row(id).updatedAt());
    }
    restore(sub);

    Assertions.assertThat(row(sub).parentId()).isEqualTo(d3);
    for (Map.Entry<String, String> entry : expected.entrySet()) {
      Assertions.assertThat(row(entry.getKey()).ancestors())
          .as("ancestor_ids of %s", entry.getKey())
          .isEqualTo(entry.getValue());
    }
    assertTimestampsAdvanced(before);
  }

  @Test
  void givenANodeAndItsDescendantTrashedTogetherTheFinalPathsShouldBeConsistent()
      throws SQLException {
    for (boolean descendantFirst : new boolean[] {false, true}) {
      Tree tree = buildTree();
      if (descendantFirst) {
        trash(tree.f2, tree.top);
      } else {
        trash(tree.top, tree.f2);
      }

      Assertions.assertThat(row(tree.top).ancestors()).isEqualTo(TRASH_ROOT);
      Assertions.assertThat(row(tree.top).parentId()).isEqualTo(TRASH_ROOT);
      Assertions.assertThat(row(tree.f2).ancestors()).isEqualTo(TRASH_ROOT);
      Assertions.assertThat(row(tree.f2).parentId()).isEqualTo(TRASH_ROOT);
      Assertions.assertThat(row(tree.f1).ancestors()).isEqualTo(TRASH_ROOT + "," + tree.top);
      Assertions.assertThat(row(tree.file1).ancestors())
          .isEqualTo(TRASH_ROOT + "," + tree.top + "," + tree.f1);
      Assertions.assertThat(row(tree.topFile).ancestors()).isEqualTo(TRASH_ROOT + "," + tree.top);
      Assertions.assertThat(row(tree.marked).ancestors()).isEqualTo(TRASH_ROOT + "," + tree.top);
      Assertions.assertThat(row(tree.markedFile).ancestors())
          .isEqualTo(TRASH_ROOT + "," + tree.top + "," + tree.marked);
      Assertions.assertThat(row(tree.f3).ancestors()).isEqualTo(TRASH_ROOT + "," + tree.f2);
      Assertions.assertThat(row(tree.file2).ancestors()).isEqualTo(TRASH_ROOT + "," + tree.f2);
      Assertions.assertThat(row(tree.leaf).ancestors())
          .isEqualTo(TRASH_ROOT + "," + tree.f2 + "," + tree.f3);
    }
  }

  @Test
  void givenANodeAndItsDescendantMovedTogetherTheFinalPathsShouldBeConsistent()
      throws SQLException {
    for (boolean descendantFirst : new boolean[] {false, true}) {
      Tree tree = buildTree();
      String d1 = folder("d1", LOCAL_ROOT);
      String d2 = folder("d2", d1);
      if (descendantFirst) {
        move(d2, tree.f2, tree.top);
      } else {
        move(d2, tree.top, tree.f2);
      }

      String destination = "LOCAL_ROOT," + d1 + "," + d2;
      Assertions.assertThat(row(tree.top).ancestors()).isEqualTo(destination);
      Assertions.assertThat(row(tree.top).parentId()).isEqualTo(d2);
      Assertions.assertThat(row(tree.f2).ancestors()).isEqualTo(destination);
      Assertions.assertThat(row(tree.f2).parentId()).isEqualTo(d2);
      Assertions.assertThat(row(tree.f1).ancestors()).isEqualTo(destination + "," + tree.top);
      Assertions.assertThat(row(tree.file1).ancestors())
          .isEqualTo(destination + "," + tree.top + "," + tree.f1);
      Assertions.assertThat(row(tree.topFile).ancestors()).isEqualTo(destination + "," + tree.top);
      Assertions.assertThat(row(tree.marked).ancestors()).isEqualTo(destination + "," + tree.top);
      Assertions.assertThat(row(tree.markedFile).ancestors())
          .isEqualTo(destination + "," + tree.top + "," + tree.marked);
      Assertions.assertThat(row(tree.f3).ancestors()).isEqualTo(destination + "," + tree.f2);
      Assertions.assertThat(row(tree.file2).ancestors()).isEqualTo(destination + "," + tree.f2);
      Assertions.assertThat(row(tree.leaf).ancestors())
          .isEqualTo(destination + "," + tree.f2 + "," + tree.f3);
    }
  }
}
