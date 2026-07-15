// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.acceptance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zextras.carbonio.files.acceptance.seam.FilesTestApp;
import com.zextras.carbonio.files.acceptance.seam.impl.GuiceNettyFilesTestAppBuilder;
import com.zextras.carbonio.files.utilities.http.HttpRequest;
import com.zextras.carbonio.files.utilities.http.HttpResponse;
import java.util.List;
import java.util.Map;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Covers {@code POST /public/graphql/} introspection (Task 1.8 of the acceptance
 * coverage-expansion plan).
 *
 * <p><b>SECURITY FINDING (documented, not fixed — Phase 1 forbids {@code src/main} changes):</b>
 * unlike the authenticated {@code /graphql/} endpoint ({@code IntrospectionApiIT} confirms
 * introspection is blocked there via {@code GraphQLProvider#buildSchema}'s {@code
 * BlockedFields.newBlock().addPattern("__.*")} field-visibility transform), {@code
 * PublicGraphQLProvider#buildSchema} applies NO such transform. The public, unauthenticated
 * GraphQL endpoint therefore serves full schema introspection to anyone, with no login required.
 * These tests pin down and demonstrate the current (unblocked) behaviour.
 */
class PublicGraphQLIntrospectionApiIT {

  static FilesTestApp app;
  static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  @BeforeAll
  static void init() {
    app =
        GuiceNettyFilesTestAppBuilder.aFilesTestApp()
            .withDatabase()
            .withServiceDiscover()
            .build();
  }

  @AfterEach
  void cleanUp() {
    app.backdoor().resetDatabase();
  }

  @AfterAll
  static void cleanUpAll() {
    app.close();
  }

  @SuppressWarnings("unchecked")
  @Test
  void givenFullSchemaIntrospectionQueryOnPublicGraphqlThenItSucceedsUnauthenticated()
      throws Exception {
    // Given — the exact introspection query that IntrospectionApiIT proves is BLOCKED on the
    // authenticated /graphql/ endpoint.
    String introspectionQuery = "query introspectionQuery { __schema { types { name } } }";

    HttpRequest httpRequest = HttpRequest.of("POST", "/public/graphql/", null, introspectionQuery);

    // When
    HttpResponse httpResponse = app.send(httpRequest);

    // Then — 200, NO errors, and the full type list is disclosed (finding: not blocked here).
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(200);

    Map<String, Object> result =
        OBJECT_MAPPER.readValue(httpResponse.getBodyPayload(), Map.class);

    Assertions.assertThat(result.get("errors")).as("introspection is not blocked; no errors expected").isNull();

    Map<String, Object> data = (Map<String, Object>) result.get("data");
    Map<String, Object> schema = (Map<String, Object>) data.get("__schema");
    List<Map<String, Object>> types = (List<Map<String, Object>>) schema.get("types");

    Assertions.assertThat(types).isNotEmpty();
    Assertions.assertThat(types)
        .extracting(type -> type.get("name"))
        .as("the public schema's own types are disclosed via introspection")
        .contains("Query", "Folder", "File", "NodePage");
  }

  @SuppressWarnings("unchecked")
  @Test
  void givenTypeIntrospectionOnQueryTypeOnPublicGraphqlThenFieldNamesAreDisclosed()
      throws Exception {
    // Given — a narrower introspection query revealing the exact Query field names/shape.
    String bodyPayload =
        "query { __type(name: \\\"Query\\\") { name fields { name } } }";

    HttpRequest httpRequest = HttpRequest.of("POST", "/public/graphql/", null, bodyPayload);

    // When
    HttpResponse httpResponse = app.send(httpRequest);

    // Then
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(200);

    Map<String, Object> result =
        OBJECT_MAPPER.readValue(httpResponse.getBodyPayload(), Map.class);
    Assertions.assertThat(result.get("errors")).isNull();

    Map<String, Object> data = (Map<String, Object>) result.get("data");
    Map<String, Object> type = (Map<String, Object>) data.get("__type");
    List<Map<String, Object>> fields = (List<Map<String, Object>>) type.get("fields");

    Assertions.assertThat(fields)
        .extracting(field -> field.get("name"))
        .contains("getPublicNode", "findNodes");
  }
}
