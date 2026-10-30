// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.zextras.carbonio.files.FilesStackTestResource;
import com.zextras.carbonio.files.it.support.AbstractFilesIT;
import io.restassured.RestAssured;
import io.restassured.response.Response;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class HierarchicalConfigApiIT extends AbstractFilesIT {

  private static final String KEY = "public-links-enabled";
  private static final String DEFAULT_VALUE = "true";
  private static final String KEY_DESCRIPTION =
      "Whether the user may create and update public links, Smart Links included";

  private static final String ADMIN_ID = "dddddddd-dddd-dddd-dddd-dddddddddd01";
  private static final String NON_GLOBAL_ADMIN_ID = "dddddddd-dddd-dddd-dddd-dddddddddd02";
  private static final String USER_ID = "dddddddd-dddd-dddd-dddd-dddddddddd03";
  private static final String COS_ID = "cos-1";

  private static final String ADMIN_COOKIE = "ZM_ADMIN_AUTH_TOKEN=hcfg-admin-token";
  private static final String NON_GLOBAL_ADMIN_COOKIE = "ZM_ADMIN_AUTH_TOKEN=hcfg-delegated-token";
  private static final String USER_COOKIE = "ZM_AUTH_TOKEN=hcfg-user-token";
  private static final String ADMIN_TOKEN_IN_USER_COOKIE = "ZM_AUTH_TOKEN=hcfg-admin-token";

  @BeforeAll
  static void registerAccounts() {
    var userManagement = FilesStackTestResource.getUserManagementService();
    userManagement.registerAccount("hcfg-admin-token", ADMIN_ID, null, true);
    userManagement.registerAccount("hcfg-delegated-token", NON_GLOBAL_ADMIN_ID, null, false);
    userManagement.registerAccount("hcfg-user-token", USER_ID, COS_ID, false);
  }

  @BeforeEach
  @AfterEach
  void cleanConfigTables() throws SQLException {
    clearHierarchicalConfig();
  }

  @Test
  void adminKeysListsTheFiveSharingKeys() {
    Response response = get("/admin/config/keys", ADMIN_COOKIE);

    assertThat(response.statusCode()).isEqualTo(200);
    List<Map<String, Object>> keys = response.jsonPath().getList("$");
    assertThat(keys)
        .extracting(k -> k.get("key"))
        .containsExactly(
            "account-shares-enabled",
            "collaboration-links-enabled",
            "public-links-enabled",
            "share-reshare-permission-enabled",
            "share-write-permission-enabled");
    assertThat(keys).allSatisfy(k -> assertThat(k).containsEntry("defaultValue", DEFAULT_VALUE));
    assertThat(keys)
        .filteredOn(k -> KEY.equals(k.get("key")))
        .singleElement()
        .satisfies(k -> assertThat(k).containsEntry("description", KEY_DESCRIPTION));
  }

  @Test
  @SuppressWarnings("unchecked")
  void adminCanWriteDeclaredKeyAndOnlyThat() throws SQLException {
    String path = "/admin/config/raw/account/" + USER_ID;
    assertThat(put(path, ADMIN_COOKIE, body(KEY, "a")).statusCode()).isEqualTo(204);
    assertThat(accountRows()).isEqualTo(1);

    assertThat(put(path, ADMIN_COOKIE, body("not-declared", "x")).statusCode()).isEqualTo(400);
    assertThat(put(path, ADMIN_COOKIE, body(KEY, "  ")).statusCode()).isEqualTo(400);
    assertThat(accountRows()).as("only the declared key was stored").isEqualTo(1);

    Map<String, String> raw = get(path, ADMIN_COOKIE).as(Map.class);
    assertThat(raw).containsOnly(Map.entry(KEY, "a"));
  }

  @Test
  void domainScopeIsRejectedAndTheGlobalRoutesAreGone() throws SQLException {
    Response domain = put("/admin/config/raw/domain/x", ADMIN_COOKIE, body(KEY, "d"));
    assertThat(domain.statusCode()).isEqualTo(400);
    assertThat(domain.asString()).contains("Unknown scope (expected account|cos)");

    assertThat(get("/admin/config/raw/global", ADMIN_COOKIE).statusCode()).isEqualTo(404);
    assertThat(put("/admin/config/raw/global", ADMIN_COOKIE, body(KEY, "g")).statusCode())
        .isEqualTo(404);
    assertThat(delete("/admin/config/raw/global/" + KEY).statusCode()).isEqualTo(405);
    assertThat(accountRows()).isZero();
  }

  @Test
  void adminEndpointsRejectMissingWrongAndNonGlobalCredentials() throws SQLException {
    assertThat(get("/admin/config/keys", null).statusCode()).isEqualTo(401);
    assertThat(put("/admin/config/raw/cos/" + COS_ID, null, body(KEY, "x")).statusCode())
        .isEqualTo(401);
    // The 403 reflects the skeleton's /myself-based resolveAdmin; it changes when the
    // admin-session check lands.
    assertThat(get("/admin/config/keys", NON_GLOBAL_ADMIN_COOKIE).statusCode()).isEqualTo(403);
    assertThat(
            put("/admin/config/raw/cos/" + COS_ID, NON_GLOBAL_ADMIN_COOKIE, body(KEY, "x"))
                .statusCode())
        .isEqualTo(403);
    assertThat(get("/admin/config/keys", ADMIN_TOKEN_IN_USER_COOKIE).statusCode()).isEqualTo(401);
    assertThat(accountRows()).isZero();
  }

  @Test
  void valueIsResolvedThroughUserManagementIds() {
    assertThat(userConfig()).hasSize(5).containsEntry(KEY, DEFAULT_VALUE);
    assertThat(resolvedFor(USER_ID))
        .containsEntry("value", DEFAULT_VALUE)
        .containsEntry("source", "default");

    assertThat(put("/admin/config/raw/cos/" + COS_ID, ADMIN_COOKIE, body(KEY, "c")).statusCode())
        .isEqualTo(204);
    assertThat(userConfig()).hasSize(5).containsEntry(KEY, "c");
    assertThat(resolvedFor(USER_ID)).containsEntry("value", "c").containsEntry("source", "cos");

    assertThat(
            put("/admin/config/raw/account/" + USER_ID, ADMIN_COOKIE, body(KEY, "a")).statusCode())
        .isEqualTo(204);
    assertThat(userConfig()).hasSize(5).containsEntry(KEY, "a");
    assertThat(resolvedFor(USER_ID)).containsEntry("value", "a").containsEntry("source", "account");

    assertThat(delete("/admin/config/raw/account/" + USER_ID + "/" + KEY).statusCode())
        .isEqualTo(204);
    assertThat(userConfig()).hasSize(5).containsEntry(KEY, "c");
    assertThat(resolvedFor(USER_ID)).containsEntry("value", "c").containsEntry("source", "cos");

    assertThat(delete("/admin/config/raw/cos/" + COS_ID + "/" + KEY).statusCode()).isEqualTo(204);
    assertThat(userConfig()).hasSize(5).containsEntry(KEY, DEFAULT_VALUE);
    assertThat(resolvedFor(USER_ID))
        .containsEntry("value", DEFAULT_VALUE)
        .containsEntry("source", "default");
  }

  @Test
  void accountOverrideWinsOverTheCos() {
    assertThat(put("/admin/config/raw/cos/" + COS_ID, ADMIN_COOKIE, body(KEY, "c")).statusCode())
        .isEqualTo(204);
    assertThat(
            put("/admin/config/raw/account/" + USER_ID, ADMIN_COOKIE, body(KEY, "a")).statusCode())
        .isEqualTo(204);

    assertThat(userConfig()).hasSize(5).containsEntry(KEY, "a");
    assertThat(resolvedFor(USER_ID)).containsEntry("value", "a").containsEntry("source", "account");
  }

  @Test
  void adminResolvedViewOfUnknownAccountIsNotFound() {
    assertThat(get("/admin/config?accountId=unknown-account", ADMIN_COOKIE).statusCode())
        .isEqualTo(404);
  }

  @Test
  void userConfigWithoutCookieIsUnauthorized() {
    assertThat(get("/config", null).statusCode()).isEqualTo(401);
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> userConfig() {
    Response response = get("/config", USER_COOKIE);
    assertThat(response.statusCode()).isEqualTo(200);
    return response.as(Map.class);
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> resolvedFor(String accountId) {
    Response response = get("/admin/config?accountId=" + accountId, ADMIN_COOKIE);
    assertThat(response.statusCode()).isEqualTo(200);
    Map<String, Object> all = response.as(Map.class);
    assertThat(all).hasSize(5).containsKey(KEY);
    return (Map<String, Object>) all.get(KEY);
  }

  private static String body(String key, String value) {
    return "{\"key\":\"" + key + "\",\"value\":\"" + value + "\"}";
  }

  private static Response get(String path, String cookie) {
    var request = RestAssured.given();
    if (cookie != null) {
      request = request.header("Cookie", cookie);
    }
    return request.get(path);
  }

  private static Response put(String path, String cookie, String json) {
    var request = RestAssured.given().contentType("application/json").body(json);
    if (cookie != null) {
      request = request.header("Cookie", cookie);
    }
    return request.put(path);
  }

  private static Response delete(String path) {
    return RestAssured.given().header("Cookie", ADMIN_COOKIE).delete(path);
  }

  private static int accountRows() throws SQLException {
    try (Connection connection = jdbcConnection();
        Statement statement = connection.createStatement();
        ResultSet resultSet =
            statement.executeQuery("SELECT count(*) FROM carbonio_account_config")) {
      resultSet.next();
      return resultSet.getInt(1);
    }
  }
}
