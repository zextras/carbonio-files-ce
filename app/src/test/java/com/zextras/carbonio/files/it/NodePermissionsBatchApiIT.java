// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.it;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zextras.carbonio.files.FilesStackTestResource;
import com.zextras.carbonio.files.dal.dao.ebean.ACL;
import com.zextras.carbonio.files.it.support.AbstractFilesIT;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class NodePermissionsBatchApiIT extends AbstractFilesIT {

  private static final String OWNER_COOKIE = "ZM_AUTH_TOKEN=fake-token";
  private static final String SHARE_TARGET_COOKIE = "ZM_AUTH_TOKEN=fake-token-b";
  private static final String SHARE_TARGET_ID = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";
  private static final String PERMISSIONS = "permissions { can_read can_write_file can_share }";

  @BeforeAll
  static void registerUsers() {
    FilesStackTestResource.getUserManagementService()
        .registerToken("fake-token", "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    FilesStackTestResource.getUserManagementService()
        .registerToken("fake-token-b", SHARE_TARGET_ID);
  }

  private static byte[] content(String text) {
    return text.getBytes(StandardCharsets.UTF_8);
  }

  private static String quoted(String value) {
    return "\\\"" + value + "\\\"";
  }

  private static void collectPermissions(JsonNode node, Map<String, List<String>> permissionsById) {
    if (node == null) {
      return;
    }
    if (node.isObject()) {
      if (node.has("id") && node.has("permissions")) {
        JsonNode permissions = node.get("permissions");
        permissionsById
            .computeIfAbsent(node.get("id").asText(), id -> new ArrayList<>())
            .add(
                (permissions.get("can_read").asBoolean() ? "r" : "-")
                    + (permissions.get("can_write_file").asBoolean() ? "w" : "-")
                    + (permissions.get("can_share").asBoolean() ? "s" : "-"));
      }
      node.forEach(child -> collectPermissions(child, permissionsById));
    } else if (node.isArray()) {
      node.forEach(child -> collectPermissions(child, permissionsById));
    }
  }

  @Test
  void givenNodesWithMixedAclsOneRequestShouldResolveEveryPermissionsFieldPositionally()
      throws Exception {
    // Given
    String sharedFolderId = seedFolder("shared-folder", LOCAL_ROOT, OWNER_COOKIE);
    String readOnlyFileId =
        seedFile("read-only.txt", sharedFolderId, content("read-only"), OWNER_COOKIE);
    String readWriteFileId =
        seedFile("read-write.txt", sharedFolderId, content("read-write"), OWNER_COOKIE);
    String subFolderId = seedFolder("sub-folder", sharedFolderId, OWNER_COOKIE);
    String subFolderFileId = seedFile("nested.txt", subFolderId, content("nested"), OWNER_COOKIE);
    seedShare(sharedFolderId, SHARE_TARGET_ID, ACL.SharePermission.READ_AND_WRITE, OWNER_COOKIE);
    seedShare(readOnlyFileId, SHARE_TARGET_ID, ACL.SharePermission.READ_ONLY, OWNER_COOKIE);
    String privateFolderId = seedFolder("private-folder", LOCAL_ROOT, OWNER_COOKIE);
    String directlySharedFileId =
        seedFile("direct.txt", privateFolderId, content("direct"), OWNER_COOKIE);
    seedShare(directlySharedFileId, SHARE_TARGET_ID, ACL.SharePermission.READ_ONLY, OWNER_COOKIE);
    String ownFolderId = seedFolder("own-folder", LOCAL_ROOT, SHARE_TARGET_COOKIE);
    String ownFileId = seedFile("own.txt", ownFolderId, content("own"), SHARE_TARGET_COOKIE);

    String children =
        "children(limit: 10, sort: NAME_ASC) { nodes { id "
            + PERMISSIONS
            + " parent { id } ... on Folder { children(limit: 10, sort: NAME_ASC) { nodes { id "
            + PERMISSIONS
            + " } } } } }";
    String query =
        "query { shared: getNode(node_id: "
            + quoted(sharedFolderId)
            + ") { id "
            + PERMISSIONS
            + " ... on Folder { "
            + children
            + " } } own: getNode(node_id: "
            + quoted(ownFolderId)
            + ") { id "
            + PERMISSIONS
            + " ... on Folder { "
            + children
            + " } } direct: getNode(node_id: "
            + quoted(directlySharedFileId)
            + ") { id parent { id } } }";

    // When
    String body = graphql(query, SHARE_TARGET_COOKIE).getBody().asString();

    // Then
    JsonNode root = new ObjectMapper().readTree(body);
    Assertions.assertThat(root.has("errors")).as(body).isFalse();
    Map<String, List<String>> seen = new HashMap<>();
    collectPermissions(root.get("data"), seen);
    Map<String, String> expected =
        Map.of(
            sharedFolderId, "rw-",
            readOnlyFileId, "r--",
            readWriteFileId, "rw-",
            subFolderId, "rw-",
            subFolderFileId, "rw-",
            ownFolderId, "rws",
            ownFileId, "rws");
    expected.forEach(
        (id, flags) -> Assertions.assertThat(seen.get(id)).as(id).isNotEmpty().containsOnly(flags));
    Assertions.assertThat(seen).containsOnlyKeys(expected.keySet());
    for (JsonNode child : root.at("/data/shared/children/nodes")) {
      Assertions.assertThat(child.at("/parent/id").asText()).isEqualTo(sharedFolderId);
    }
    Assertions.assertThat(root.at("/data/shared/children/nodes")).hasSize(3);
    Assertions.assertThat(root.at("/data/direct/id").asText()).isEqualTo(directlySharedFileId);
    Assertions.assertThat(root.at("/data/direct/parent").isNull()).isTrue();
  }
}
