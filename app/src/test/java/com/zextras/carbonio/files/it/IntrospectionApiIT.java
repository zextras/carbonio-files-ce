// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.it;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zextras.carbonio.files.FilesStackTestResource;
import com.zextras.carbonio.files.it.support.AbstractFilesIT;
import io.restassured.response.Response;
import java.util.List;
import java.util.Map;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * {@code com.zextras.carbonio.files.acceptance.IntrospectionApiIT} rewritten as an out-of-process
 * {@code @QuarkusIntegrationTest} on {@link AbstractFilesIT}.
 *
 * <p><b>Behavior change (code-first cutover, legitimate):</b> the legacy graphql-java stack blocked
 * {@code __schema}/{@code __type} introspection on the authenticated {@code /graphql/} endpoint via
 * {@code GraphQLProvider#buildSchema}'s {@code BlockedFields.newBlock().addPattern("__.*")}
 * field-visibility transform. The SmallRye code-first engine has no equivalent mechanism: it serves
 * a single unified schema on {@code /graphql}, and graphql-java's {@code BlockedFields} API does
 * not exist in the SmallRye runtime. The pragmatic resolution is to accept introspection as ALLOWED
 * on the unified schema and update this test accordingly.
 *
 * <p>The old test's secondary purpose ("Doubles as a native-smoke surface under {@code -Dnative}
 * (broad schema-reflection coverage)") is preserved: the introspection query verifies that the
 * SmallRye schema is correctly exposed and contains the expected types.
 */
class IntrospectionApiIT extends AbstractFilesIT {

  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  private static final String REQUESTER_ID = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
  private static final String REQUESTER_COOKIE = "ZM_AUTH_TOKEN=fake-token";

  @BeforeAll
  static void registerUsers() {
    FilesStackTestResource.getUserManagementService().registerToken("fake-token", REQUESTER_ID);
  }

  /**
   * SmallRye serves the unified schema without introspection blocking on the authenticated
   * endpoint. The introspection query succeeds and discloses the schema types.
   *
   * <p>Changed from the legacy expectation (errors non-empty, "Validation error") to the new
   * SmallRye expectation (no errors, full schema disclosed) — legitimate consequence of the
   * code-first cutover removing the {@code BlockedFields} graphql-java transform.
   */
  @SuppressWarnings("unchecked")
  @Test
  void givenIntrospectionIsAllowedWhenIntrospectionQueryIsSentToAuthEndpointThenItSucceeds()
      throws Exception {
    // Given — the same query that was blocked by the old BlockedFields transform.
    String introspectionQuery = "query introspectionQuery { __schema { types { name } } }";

    // When
    Response response = graphql(introspectionQuery, REQUESTER_COOKIE);

    // Then — 200, NO errors (SmallRye does not block __schema on the authenticated endpoint).
    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);

    Map<String, Object> result = OBJECT_MAPPER.readValue(response.getBody().asString(), Map.class);

    Assertions.assertThat(result.get("errors"))
        .as(
            "SmallRye does not block introspection on /graphql/ — no errors expected (changed from"
                + " the graphql-java BlockedFields behaviour)")
        .isNull();

    Map<String, Object> data = (Map<String, Object>) result.get("data");
    Map<String, Object> schema = (Map<String, Object>) data.get("__schema");
    List<Map<String, Object>> types = (List<Map<String, Object>>) schema.get("types");

    Assertions.assertThat(types).isNotEmpty();
    Assertions.assertThat(types)
        .extracting(type -> type.get("name"))
        .as("the unified schema's own types are disclosed via introspection on /graphql/")
        .contains("Query", "Mutation");
  }
}
