// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.it;

import com.zextras.carbonio.files.FilesStackTestResource;
import com.zextras.carbonio.files.TestUtils;
import com.zextras.carbonio.files.api.utilities.GraphqlCommandBuilder;
import com.zextras.carbonio.files.it.support.AbstractFilesIT;
import io.restassured.RestAssured;
import io.restassured.response.Response;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Gap-closing pass: {@code DateTimeScalar}'s coercion edges (the {@code DateTime} custom scalar
 * bound to, among other fields, {@code createLink(expires_at:)}), rewritten as an out-of-process
 * {@code @QuarkusIntegrationTest} on {@link AbstractFilesIT}. All 5 methods and their assertions
 * are preserved verbatim; only the seeding mechanism (API calls capturing server-generated ids) and
 * transport (raw RestAssured POST for the {@code variables}-driven tests, which need a hand-built
 * {@code {"query":...,"variables":{...}}} JSON payload the {@link AbstractFilesIT#graphql} helper
 * does not support) changed.
 *
 * <h2>{@code parseLiteral} (inline literal in the query text)</h2>
 *
 * <ul>
 *   <li>{@code instanceof StringValue} true (a QUOTED numeric literal, e.g. {@code expires_at:
 *       "1690000000"}).
 *   <li>Neither {@code StringValue} nor {@code IntValue} (e.g. a float literal) -&gt; {@code
 *       CoercingParseLiteralException}, surfaced as a document-validation error.
 * </ul>
 *
 * <h2>{@code parseValue} (value supplied via GraphQL {@code variables})</h2>
 *
 * <p>Reachable via the real HTTP/GraphQL contract (the JSON body's top-level {@code variables}
 * field): {@code instanceof Long} (a variable value outside the {@code int} range), {@code
 * instanceof Integer} (an ordinary small variable value), neither -&gt; {@code
 * CoercingParseValueException} (e.g. a string variable value).
 */
class DateTimeScalarApiIT extends AbstractFilesIT {

  private static final String OWNER_ID = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
  private static final String OWNER_COOKIE = "ZM_AUTH_TOKEN=fake-token";

  @BeforeAll
  static void registerUsers() {
    FilesStackTestResource.getUserManagementService().registerToken("fake-token", OWNER_ID);
  }

  private String seedLinkableNode() {
    return seedFile(
        "linkable.txt", LOCAL_ROOT, "0123456789".getBytes(StandardCharsets.UTF_8), OWNER_COOKIE);
  }

  // --- parseLiteral -------------------------------------------------------------------------

  @Test
  void givenAQuotedNumericStringLiteralExpiresAtCreateLinkShouldSucceed() {
    // Given
    String nodeId = seedLinkableNode();
    String bodyPayload =
        GraphqlCommandBuilder.aMutationBuilder("createLink")
            .withString("node_id", nodeId)
            .withString("expires_at", "1690000000")
            .withWantedResultFormat("{ id expires_at }")
            .build();

    // When
    Response response = graphql(bodyPayload, OWNER_COOKIE);

    // Then — DateTimeScalar#parseLiteral's `instanceof StringValue` branch: a quoted numeric
    // literal is accepted exactly like an unquoted one.
    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    Assertions.assertThat(TestUtils.jsonResponseToErrors(response.getBody().asString())).isEmpty();
    Map<String, Object> link =
        TestUtils.jsonResponseToMap(response.getBody().asString(), "createLink");
    Assertions.assertThat(link).containsEntry("expires_at", 1690000000);
  }

  @Test
  void givenAFloatLiteralExpiresAtCreateLinkShouldSurfaceACoercionError() {
    // Given — neither StringValue nor IntValue -> DateTimeScalar#parseLiteral's throw branch.
    String nodeId = seedLinkableNode();
    String bodyPayload =
        GraphqlCommandBuilder.aMutationBuilder("createLink")
            .withString("node_id", nodeId)
            .withEnumLiteral("expires_at", "3.14") // raw unquoted literal, not a real enum
            .withWantedResultFormat("{ id }")
            .build();

    // When
    Response response = graphql(bodyPayload, OWNER_COOKIE);

    // Then — a document-validation error, before createLink's resolver ever runs.
    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    List<String> errors = TestUtils.jsonResponseToErrors(response.getBody().asString());
    Assertions.assertThat(errors).hasSize(1);
    Assertions.assertThat(errors.get(0)).contains("date");
    Assertions.assertThat(
            TestUtils.jsonResponseToValue(response.getBody().asString(), "createLink"))
        .isEmpty();
  }

  // --- parseValue (GraphQL variables) --------------------------------------------------------

  private Response createLinkWithVariableExpiresAt(String nodeId, String rawJsonExpiresAtValue) {
    String body =
        "{\"query\":\"mutation($nodeId: ID!, $exp: DateTime) { createLink(node_id: $nodeId,"
            + " expires_at: $exp) { id expires_at } }\","
            + "\"variables\":{\"nodeId\":\""
            + nodeId
            + "\",\"exp\":"
            + rawJsonExpiresAtValue
            + "}}";
    return RestAssured.given()
        .contentType("application/json")
        .header("Cookie", OWNER_COOKIE)
        .body(body)
        .post("/graphql/");
  }

  @Test
  void givenASmallIntegerVariableExpiresAtCreateLinkShouldSucceed() {
    // Given — Jackson deserializes a small JSON number into an Integer -> parseValue's
    // `instanceof Integer` branch (the realistic case: a real client sends a JSON number).
    String nodeId = seedLinkableNode();

    // When
    Response response = createLinkWithVariableExpiresAt(nodeId, "5");

    // Then
    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    Assertions.assertThat(TestUtils.jsonResponseToErrors(response.getBody().asString())).isEmpty();
    Map<String, Object> link =
        TestUtils.jsonResponseToMap(response.getBody().asString(), "createLink");
    Assertions.assertThat(link).containsEntry("expires_at", 5);
  }

  @Test
  void givenALargeIntegerVariableExpiresAtCreateLinkShouldSucceed() {
    // Given — a JSON number outside the int range deserializes to a Long -> parseValue's
    // `instanceof Long` branch, never exercised by the small-integer scenario above.
    String nodeId = seedLinkableNode();

    // When
    Response response = createLinkWithVariableExpiresAt(nodeId, "99999999999999");

    // Then
    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    Assertions.assertThat(TestUtils.jsonResponseToErrors(response.getBody().asString())).isEmpty();
    Map<String, Object> link =
        TestUtils.jsonResponseToMap(response.getBody().asString(), "createLink");
    Assertions.assertThat(link).containsEntry("expires_at", 99999999999999L);
  }

  @Test
  void givenAStringVariableExpiresAtCreateLinkShouldSurfaceACoercionError() {
    // Given — neither Long nor Integer -> DateTimeScalar#parseValue's throw branch.
    String nodeId = seedLinkableNode();

    // When
    Response response = createLinkWithVariableExpiresAt(nodeId, "\"not-a-timestamp\"");

    // Then
    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    List<String> errors = TestUtils.jsonResponseToErrors(response.getBody().asString());
    Assertions.assertThat(errors).hasSize(1);
    Assertions.assertThat(errors.get(0)).contains("date");
    Assertions.assertThat(
            TestUtils.jsonResponseToValue(response.getBody().asString(), "createLink"))
        .isEmpty();
  }
}
