// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.it;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zextras.carbonio.files.it.support.AbstractFilesIT;
import io.restassured.response.Response;
import java.util.List;
import java.util.Map;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * {@code com.zextras.carbonio.files.acceptance.PublicGraphQLIntrospectionApiIT} rewritten as an
 * out-of-process {@code @QuarkusIntegrationTest} on {@link AbstractFilesIT}: covers {@code POST
 * /public/graphql/} introspection. Also folds {@code graphql/GraphQLWiringIT} (schema
 * introspection is HTTP-level and fully overlaps this class). Doubles as a native-smoke surface
 * under {@code -Dnative}.
 *
 * <p><b>SECURITY FINDING (carried over, not fixed — test-only task, no {@code src/main}
 * changes):</b> unlike the authenticated {@code /graphql/} endpoint ({@link IntrospectionApiIT}
 * confirms introspection is blocked there via {@code GraphQLProvider#buildSchema}'s {@code
 * BlockedFields.newBlock().addPattern("__.*")} field-visibility transform), {@code
 * PublicGraphQLProvider#buildSchema} applies NO such transform. The public, unauthenticated
 * GraphQL endpoint therefore serves full schema introspection to anyone, with no login required.
 * These tests pin down and demonstrate the current (unblocked) behaviour.
 *
 * <p>Both methods and their assertions are preserved verbatim; only the transport ({@link
 * #publicGraphql}) changed — no seeding is needed (introspection needs no data).
 */
class PublicGraphQLIntrospectionApiIT extends AbstractFilesIT {

  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  @SuppressWarnings("unchecked")
  @Test
  void givenFullSchemaIntrospectionQueryOnPublicGraphqlThenItSucceedsUnauthenticated() throws Exception {
    // Given — the exact introspection query that IntrospectionApiIT proves is BLOCKED on the
    // authenticated /graphql/ endpoint.
    String introspectionQuery = "query introspectionQuery { __schema { types { name } } }";

    // When
    Response response = publicGraphql(introspectionQuery);

    // Then — 200, NO errors, and the full type list is disclosed (finding: not blocked here).
    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);

    Map<String, Object> result = OBJECT_MAPPER.readValue(response.getBody().asString(), Map.class);

    Assertions.assertThat(result.get("errors"))
        .as("introspection is not blocked; no errors expected")
        .isNull();

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
  void givenTypeIntrospectionOnQueryTypeOnPublicGraphqlThenFieldNamesAreDisclosed() throws Exception {
    // Given — a narrower introspection query revealing the exact Query field names/shape.
    String query = "query { __type(name: \\\"Query\\\") { name fields { name } } }";

    // When
    Response response = publicGraphql(query);

    // Then
    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);

    Map<String, Object> result = OBJECT_MAPPER.readValue(response.getBody().asString(), Map.class);
    Assertions.assertThat(result.get("errors")).isNull();

    Map<String, Object> data = (Map<String, Object>) result.get("data");
    Map<String, Object> type = (Map<String, Object>) data.get("__type");
    List<Map<String, Object>> fields = (List<Map<String, Object>>) type.get("fields");

    Assertions.assertThat(fields)
        .extracting(field -> field.get("name"))
        .contains("getPublicNode", "findNodes");
  }
}
