// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.it;

import com.zextras.carbonio.files.FilesStackTestResource;
import com.zextras.carbonio.files.TestUtils;
import com.zextras.carbonio.files.api.utilities.GraphqlCommandBuilder;
import com.zextras.carbonio.files.dal.dao.ebean.ACL;
import com.zextras.carbonio.files.it.support.AbstractFilesIT;
import io.restassured.response.Response;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class ShareCascadeApiIT extends AbstractFilesIT {

  private static final String OWNER_ID = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
  private static final String OWNER_COOKIE = "ZM_AUTH_TOKEN=fake-token";
  private static final String USER_ID = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";
  private static final String OTHER_ID = "cccccccc-cccc-cccc-cccc-cccccccccccc";

  private static final long DIRECT_EXPIRY = 1_900_000_000L;
  private static final long OLD_EXPIRY = 1_800_000_000L;
  private static final int NEW_EXPIRY = 2_000_000_000;

  private record ShareRow(short rights, boolean direct, boolean viaLink, Long expiry) {}

  @BeforeAll
  static void registerUsers() {
    FilesStackTestResource.getUserManagementService().registerToken("fake-token", OWNER_ID);
  }

  private static void createShare(String nodeId, String targetId, ACL.SharePermission permission) {
    Response response =
        graphql(
            GraphqlCommandBuilder.aMutationBuilder("createShare")
                .withString("node_id", nodeId)
                .withString("share_target_id", targetId)
                .withEnum("permission", permission)
                .withWantedResultFormat("{ created_at }")
                .build(),
            OWNER_COOKIE);
    Assertions.assertThat(TestUtils.jsonResponseToErrors(response.getBody().asString())).isEmpty();
  }

  private static void updateShares(
      String nodeId, String targetId, ACL.SharePermission permission, Integer expiresAt) {
    GraphqlCommandBuilder builder =
        GraphqlCommandBuilder.aMutationBuilder("updateShares")
            .withString("node_id", nodeId)
            .withListOfStrings("share_target_ids", new String[] {targetId})
            .withEnum("permission", permission);
    if (expiresAt != null) {
      builder = builder.withInteger("expires_at", expiresAt);
    }
    Response response =
        graphql(builder.withWantedResultFormat("{ permission }").build(), OWNER_COOKIE);
    Assertions.assertThat(TestUtils.jsonResponseToErrors(response.getBody().asString())).isEmpty();
  }

  private static void deleteShares(String nodeId, String targetId) {
    Response response =
        graphql(
            GraphqlCommandBuilder.aMutationBuilder("deleteShares")
                .withString("node_id", nodeId)
                .withListOfStrings("share_target_ids", new String[] {targetId})
                .withWantedResultFormat("")
                .build(),
            OWNER_COOKIE);
    Assertions.assertThat(TestUtils.jsonResponseToErrors(response.getBody().asString())).isEmpty();
  }

  private static String folder(String name, String parentId) {
    return seedFolder(name, parentId, OWNER_COOKIE);
  }

  private static String file(String name, String parentId) {
    return seedFile(name, parentId, "content".getBytes(StandardCharsets.UTF_8), OWNER_COOKIE);
  }

  private static ShareRow share(String nodeId, String targetId) throws SQLException {
    try (Connection connection = jdbcConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "SELECT rights, direct, created_via_link, expire_date FROM share"
                    + " WHERE node_id = ? AND target_uuid = ?")) {
      statement.setString(1, nodeId);
      statement.setString(2, targetId);
      try (ResultSet rows = statement.executeQuery()) {
        if (!rows.next()) {
          return null;
        }
        short rights = rows.getShort(1);
        boolean direct = rows.getBoolean(2);
        boolean viaLink = rows.getBoolean(3);
        long expiry = rows.getLong(4);
        return new ShareRow(rights, direct, viaLink, rows.wasNull() ? null : expiry);
      }
    }
  }

  private static void update(String sql, Object... params) throws SQLException {
    try (Connection connection = jdbcConnection();
        PreparedStatement statement = connection.prepareStatement(sql)) {
      for (int i = 0; i < params.length; i++) {
        statement.setObject(i + 1, params[i]);
      }
      statement.executeUpdate();
    }
  }

  private static void setExpiry(String nodeId, long expiry) throws SQLException {
    update(
        "UPDATE share SET expire_date = ? WHERE node_id = ? AND target_uuid = ?",
        expiry,
        nodeId,
        USER_ID);
  }

  private static void ensureInheritedShare(String nodeId, String targetId) throws SQLException {
    update(
        "INSERT INTO share (node_id, rights, timestamp, target_uuid, expire_date, direct,"
            + " created_via_link) VALUES (?, ?, 1, ?, NULL, false, false)"
            + " ON CONFLICT DO NOTHING",
        nodeId,
        ACL.SharePermission.READ_ONLY.encode(),
        targetId);
  }

  private static short rights(ACL.SharePermission permission) {
    return permission.encode();
  }

  @Test
  void upsertReachesEveryDepthAndContinuesBelowADirectShare() throws Exception {
    String s = folder("S", LOCAL_ROOT);
    String a = folder("a", s);
    String b = folder("b", a);
    String c = folder("c", b);
    String leaf = file("leaf.txt", c);
    String sibling = file("sibling.txt", s);
    String trashed = folder("trashed", s);
    String underTrashed = folder("underTrashed", trashed);
    seedTrashed(trashed, OWNER_COOKIE);

    createShare(b, USER_ID, ACL.SharePermission.READ_ONLY);
    setExpiry(b, DIRECT_EXPIRY);
    createShare(s, OTHER_ID, ACL.SharePermission.READ_ONLY);

    createShare(s, USER_ID, ACL.SharePermission.READ_AND_WRITE);

    short rw = rights(ACL.SharePermission.READ_AND_WRITE);
    Assertions.assertThat(share(s, USER_ID)).isEqualTo(new ShareRow(rw, true, false, null));
    Assertions.assertThat(share(a, USER_ID)).isEqualTo(new ShareRow(rw, false, false, null));
    Assertions.assertThat(share(sibling, USER_ID)).isEqualTo(new ShareRow(rw, false, false, null));
    Assertions.assertThat(share(b, USER_ID))
        .isEqualTo(new ShareRow(rights(ACL.SharePermission.READ_ONLY), true, false, DIRECT_EXPIRY));
    Assertions.assertThat(share(c, USER_ID)).isEqualTo(new ShareRow(rw, false, false, null));
    Assertions.assertThat(share(leaf, USER_ID)).isEqualTo(new ShareRow(rw, false, false, null));
    Assertions.assertThat(share(trashed, USER_ID)).isNull();
    Assertions.assertThat(share(underTrashed, USER_ID)).isNull();
    Assertions.assertThat(share(c, OTHER_ID))
        .isEqualTo(new ShareRow(rights(ACL.SharePermission.READ_ONLY), false, false, null));
  }

  @Test
  void upsertUpdatesInheritedSharesAndKeepsOrReplacesTheirExpiry() throws Exception {
    String s = folder("S", LOCAL_ROOT);
    String a = folder("a", s);
    String b = folder("b", a);
    String direct = folder("direct", s);
    String underDirect = folder("underDirect", direct);
    createShare(direct, USER_ID, ACL.SharePermission.READ_ONLY);
    setExpiry(direct, DIRECT_EXPIRY);
    createShare(s, USER_ID, ACL.SharePermission.READ_ONLY);
    update(
        "UPDATE share SET expire_date = ?, created_via_link = true WHERE direct = false"
            + " AND target_uuid = ? AND node_id IN (?, ?, ?)",
        OLD_EXPIRY,
        USER_ID,
        a,
        b,
        underDirect);

    update("DELETE FROM share WHERE node_id = ? AND target_uuid = ?", b, USER_ID);

    updateShares(s, USER_ID, ACL.SharePermission.READ_AND_WRITE, null);

    short rw = rights(ACL.SharePermission.READ_AND_WRITE);
    Assertions.assertThat(share(a, USER_ID)).isEqualTo(new ShareRow(rw, false, false, OLD_EXPIRY));
    Assertions.assertThat(share(b, USER_ID)).isEqualTo(new ShareRow(rw, false, false, null));
    Assertions.assertThat(share(underDirect, USER_ID))
        .isEqualTo(new ShareRow(rw, false, false, OLD_EXPIRY));
    Assertions.assertThat(share(direct, USER_ID))
        .isEqualTo(new ShareRow(rights(ACL.SharePermission.READ_ONLY), true, false, DIRECT_EXPIRY));

    updateShares(s, USER_ID, ACL.SharePermission.READ_ONLY, NEW_EXPIRY);

    short ro = rights(ACL.SharePermission.READ_ONLY);
    Assertions.assertThat(share(a, USER_ID))
        .isEqualTo(new ShareRow(ro, false, false, (long) NEW_EXPIRY));
    Assertions.assertThat(share(b, USER_ID))
        .isEqualTo(new ShareRow(ro, false, false, (long) NEW_EXPIRY));
    Assertions.assertThat(share(underDirect, USER_ID))
        .isEqualTo(new ShareRow(ro, false, false, (long) NEW_EXPIRY));
    Assertions.assertThat(share(direct, USER_ID))
        .isEqualTo(new ShareRow(ro, true, false, DIRECT_EXPIRY));
  }

  @Test
  void deleteStopsAtADirectShareAndReachesTrashedNodesOfTheCascade() throws Exception {
    String s = folder("S", LOCAL_ROOT);
    String a = folder("a", s);
    String b = file("b.txt", a);
    String d = folder("d", s);
    String e = folder("e", d);
    String g = file("g.txt", e);
    String t = folder("t", s);
    String tc = folder("tc", t);
    String tcFile = file("tc.txt", tc);
    String deepTrashed = folder("deepTrashed", a);
    String deepTrashedChild = file("deepTrashedChild.txt", deepTrashed);

    createShare(d, USER_ID, ACL.SharePermission.READ_ONLY);
    createShare(s, USER_ID, ACL.SharePermission.READ_AND_WRITE);
    createShare(s, OTHER_ID, ACL.SharePermission.READ_ONLY);
    seedTrashed(t, OWNER_COOKIE);
    seedTrashed(deepTrashed, OWNER_COOKIE);
    for (String node : new String[] {t, tc, tcFile, deepTrashed, deepTrashedChild}) {
      ensureInheritedShare(node, USER_ID);
      ensureInheritedShare(node, OTHER_ID);
    }
    Assertions.assertThat(share(g, USER_ID)).isNotNull();

    deleteShares(s, USER_ID);

    Assertions.assertThat(share(s, USER_ID)).isNull();
    for (String node : new String[] {a, b, t, tc, tcFile, deepTrashed, deepTrashedChild}) {
      Assertions.assertThat(share(node, USER_ID)).as("share of %s", node).isNull();
    }
    Assertions.assertThat(share(d, USER_ID))
        .isEqualTo(new ShareRow(rights(ACL.SharePermission.READ_ONLY), true, false, null));
    Assertions.assertThat(share(e, USER_ID)).isNotNull();
    Assertions.assertThat(share(g, USER_ID)).isNotNull();
    String[] all = {s, a, b, d, e, g, t, tc, tcFile, deepTrashed, deepTrashedChild};
    for (String node : all) {
      Assertions.assertThat(share(node, OTHER_ID)).as("other user share of %s", node).isNotNull();
    }
  }
}
