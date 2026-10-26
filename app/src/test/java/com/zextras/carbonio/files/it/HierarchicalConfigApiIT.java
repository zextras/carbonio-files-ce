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

  private static final String KEY = "test-key";
  private static final String DEFAULT_VALUE = "default-value";
  private static final String KEY_DESCRIPTION =
      "Test key of the hierarchical config skeleton (CO-4496), no behaviour";

  private static final String ADMIN_ID = "dddddddd-dddd-dddd-dddd-dddddddddd01";
  private static final String DELEGATED_ADMIN_ID = "dddddddd-dddd-dddd-dddd-dddddddddd02";
  private static final String USER_ID = "dddddddd-dddd-dddd-dddd-dddddddddd03";
  private static final String COS_ID = "cos-1";
  private static final String DOMAIN_ID = "dom-1";

  private static final String ADMIN_COOKIE = "ZM_ADMIN_AUTH_TOKEN=hcfg-admin-token";
  private static final String DELEGATED_ADMIN_COOKIE = "ZM_ADMIN_AUTH_TOKEN=hcfg-delegated-token";
  private static final String USER_COOKIE = "ZM_AUTH_TOKEN=hcfg-user-token";
  private static final String USER_TOKEN_AS_ADMIN_COOKIE = "ZM_AUTH_TOKEN=hcfg-admin-token";

  @BeforeAll
  static void registerAccounts() {
    var userManagement = FilesStackTestResource.getUserManagementService();
    userManagement.registerAccount("hcfg-admin-token", ADMIN_ID, null, null, true);
    userManagement.registerAccount("hcfg-delegated-token", DELEGATED_ADMIN_ID, null, null, false);
    userManagement.registerAccount("hcfg-user-token", USER_ID, COS_ID, DOMAIN_ID, false);
  }

  @BeforeEach
  @AfterEach
  void cleanConfigTables() throws SQLException {
    try (Connection connection = jdbcConnection();
        Statement statement = connection.createStatement()) {
      statement.execute("DELETE FROM carbonio_account_config");
      statement.execute("DELETE FROM carbonio_cos_config");
      statement.execute("DELETE FROM carbonio_domain_config");
      statement.execute("DELETE FROM carbonio_global_config");
    }
  }

  @Test
  void adminKeysListsOnlyTheDeclaredKey() {
    Response response = get("/admin/config/keys", ADMIN_COOKIE);

    assertThat(response.statusCode()).isEqualTo(200);
    List<Map<String, Object>> keys = response.jsonPath().getList("$");
    assertThat(keys).hasSize(1);
    assertThat(keys.get(0))
        .containsEntry("key", KEY)
        .containsEntry("description", KEY_DESCRIPTION)
        .containsEntry("defaultValue", DEFAULT_VALUE);
  }

  @Test
  void adminCanWriteDeclaredKeyAndOnlyThat() throws SQLException {
    assertThat(put("/admin/config/raw/global", ADMIN_COOKIE, body(KEY, "g")).statusCode())
        .isEqualTo(204);
    assertThat(globalRows()).isEqualTo(1);

    assertThat(
            put("/admin/config/raw/global", ADMIN_COOKIE, body("not-declared", "x")).statusCode())
        .isEqualTo(400);
    assertThat(put("/admin/config/raw/global", ADMIN_COOKIE, body(KEY, "  ")).statusCode())
        .isEqualTo(400);
    assertThat(globalRows()).as("only the declared key was stored").isEqualTo(1);

    Map<String, String> raw = get("/admin/config/raw/global", ADMIN_COOKIE).as(Map.class);
    assertThat(raw).containsOnly(Map.entry(KEY, "g"));
  }

  @Test
  void adminEndpointsRejectMissingWrongAndNonGlobalCredentials() throws SQLException {
    assertThat(get("/admin/config/keys", null).statusCode()).isEqualTo(401);
    assertThat(put("/admin/config/raw/global", null, body(KEY, "x")).statusCode()).isEqualTo(401);
    assertThat(get("/admin/config/keys", DELEGATED_ADMIN_COOKIE).statusCode()).isEqualTo(403);
    assertThat(put("/admin/config/raw/global", DELEGATED_ADMIN_COOKIE, body(KEY, "x")).statusCode())
        .isEqualTo(403);
    assertThat(get("/admin/config/keys", USER_TOKEN_AS_ADMIN_COOKIE).statusCode()).isEqualTo(401);
    assertThat(globalRows()).isZero();
  }

  @Test
  void valueIsResolvedThroughUserManagementIds() {
    assertThat(put("/admin/config/raw/global", ADMIN_COOKIE, body(KEY, "g")).statusCode())
        .isEqualTo(204);
    assertThat(
            put("/admin/config/raw/domain/" + DOMAIN_ID, ADMIN_COOKIE, body(KEY, "d")).statusCode())
        .isEqualTo(204);
    assertThat(put("/admin/config/raw/cos/" + COS_ID, ADMIN_COOKIE, body(KEY, "c")).statusCode())
        .isEqualTo(204);

    assertThat(userConfig()).containsOnly(Map.entry(KEY, "c"));
    assertThat(resolvedFor(USER_ID)).containsEntry("value", "c").containsEntry("source", "cos");

    assertThat(delete("/admin/config/raw/cos/" + COS_ID + "/" + KEY).statusCode()).isEqualTo(204);
    assertThat(userConfig()).containsOnly(Map.entry(KEY, "d"));
    assertThat(resolvedFor(USER_ID)).containsEntry("value", "d").containsEntry("source", "domain");

    assertThat(delete("/admin/config/raw/domain/" + DOMAIN_ID + "/" + KEY).statusCode())
        .isEqualTo(204);
    assertThat(userConfig()).containsOnly(Map.entry(KEY, "g"));
    assertThat(resolvedFor(USER_ID)).containsEntry("value", "g").containsEntry("source", "global");

    assertThat(delete("/admin/config/raw/global/" + KEY).statusCode()).isEqualTo(204);
    assertThat(userConfig()).containsOnly(Map.entry(KEY, DEFAULT_VALUE));
    assertThat(resolvedFor(USER_ID))
        .containsEntry("value", DEFAULT_VALUE)
        .containsEntry("source", "default");
  }

  @Test
  void accountOverrideWinsOverTheOtherTiers() {
    assertThat(put("/admin/config/raw/cos/" + COS_ID, ADMIN_COOKIE, body(KEY, "c")).statusCode())
        .isEqualTo(204);
    assertThat(
            put("/admin/config/raw/account/" + USER_ID, ADMIN_COOKIE, body(KEY, "a")).statusCode())
        .isEqualTo(204);

    assertThat(userConfig()).containsOnly(Map.entry(KEY, "a"));
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
    assertThat(all).containsOnlyKeys(KEY);
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

  private static int globalRows() throws SQLException {
    try (Connection connection = jdbcConnection();
        Statement statement = connection.createStatement();
        ResultSet resultSet =
            statement.executeQuery("SELECT count(*) FROM carbonio_global_config")) {
      resultSet.next();
      return resultSet.getInt(1);
    }
  }
}
