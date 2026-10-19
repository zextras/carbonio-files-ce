// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.it;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zextras.carbonio.files.FilesStackTestResource;
import com.zextras.carbonio.files.it.support.AbstractFilesIT;
import io.restassured.response.Response;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Introspection is blocked in production, as on main (where {@code BlockedFields} hid {@code
 * __.*}): the graphql extension's prod default {@code
 * quarkus.smallrye-graphql.field-visibility=no-introspection} makes {@code __schema} and {@code
 * __type} fail validation, for anonymous and authenticated callers alike, while {@code __typename}
 * stays available.
 */
class IntrospectionApiIT extends AbstractFilesIT {

  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  private static final String REQUESTER_ID = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
  private static final String REQUESTER_COOKIE = "ZM_AUTH_TOKEN=fake-token";

  @BeforeAll
  static void registerUsers() {
    FilesStackTestResource.getUserManagementService().registerToken("fake-token", REQUESTER_ID);
  }

  private static JsonNode body(Response response) throws Exception {
    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    return OBJECT_MAPPER.readTree(response.getBody().asString());
  }

  private static void assertRejected(JsonNode body, String field) {
    Assertions.assertThat(body.path("errors")).isNotEmpty();
    body.path("errors")
        .forEach(
            error ->
                Assertions.assertThat(error.path("extensions").path("classification").asText())
                    .isEqualTo("ValidationError"));
    JsonNode data = body.path("data");
    Assertions.assertThat(data.isMissingNode() || data.isNull() || data.path(field).isMissingNode())
        .as("no %s data is disclosed", field)
        .isTrue();
  }

  @Test
  void givenAnAnonymousCallerSchemaIntrospectionShouldBeRejected() throws Exception {
    JsonNode body = body(publicGraphql("query { __schema { mutationType { fields { name } } } }"));

    assertRejected(body, "__schema");
  }

  @Test
  void givenAnAnonymousCallerTypeIntrospectionShouldBeRejected() throws Exception {
    JsonNode body = body(publicGraphql("query { __type(name: \\\"Mutation\\\") { name } }"));

    assertRejected(body, "__type");
  }

  @Test
  void givenAnAuthenticatedCallerSchemaIntrospectionShouldBeRejected() throws Exception {
    JsonNode body =
        body(graphql("query introspectionQuery { __schema { types { name } } }", REQUESTER_COOKIE));

    assertRejected(body, "__schema");
  }

  @Test
  void givenAnAuthenticatedCallerTypeIntrospectionShouldBeRejected() throws Exception {
    JsonNode body =
        body(graphql("query { __type(name: \\\"Mutation\\\") { name } }", REQUESTER_COOKIE));

    assertRejected(body, "__type");
  }

  @Test
  void givenTypenameFieldsTheyShouldStillResolve() throws Exception {
    JsonNode body =
        body(
            graphql(
                "query { __typename getNode(node_id: \\\"LOCAL_ROOT\\\") { __typename id } }",
                REQUESTER_COOKIE));

    Assertions.assertThat(body.path("errors").isMissingNode() || body.path("errors").isEmpty())
        .isTrue();
    Assertions.assertThat(body.path("data").path("__typename").asText()).isEqualTo("Query");
    Assertions.assertThat(body.path("data").path("getNode").path("__typename").asText())
        .isEqualTo("Folder");
  }

  @Test
  void givenAnAnonymousCallerTypenameShouldStillResolve() throws Exception {
    JsonNode body = body(publicGraphql("query { __typename }"));

    Assertions.assertThat(body.path("data").path("__typename").asText()).isEqualTo("Query");
  }
}
