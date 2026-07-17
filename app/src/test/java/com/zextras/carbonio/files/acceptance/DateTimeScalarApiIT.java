// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.acceptance;

import com.zextras.carbonio.files.FilesStackTestResource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;

import com.zextras.carbonio.files.TestUtils;
import com.zextras.carbonio.files.acceptance.seam.FilesTestApp;
import com.zextras.carbonio.files.acceptance.seam.impl.QuarkusFilesTestAppBuilder;
import com.zextras.carbonio.files.api.utilities.GraphqlCommandBuilder;
import com.zextras.carbonio.files.api.utilities.entities.PopulatorNode;
import com.zextras.carbonio.files.dal.dao.ebean.NodeType;
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
 * Gap-closing pass: {@code DateTimeScalar}'s coercion edges (the {@code DateTime} custom scalar
 * bound to, among other fields, {@code createLink(expires_at:)}). Every existing test in the suite
 * that touches {@code expires_at} passes a plain unquoted integer literal (via {@code
 * GraphqlCommandBuilder#withInteger}) — this always takes the SAME two branches:
 * {@code parseLiteral}'s {@code instanceof IntValue} (true), and never touches {@code parseValue}
 * at all (that method only runs for variable-supplied values, and no test in this suite uses
 * GraphQL {@code variables} before this file).
 *
 * <h2>{@code parseLiteral} (inline literal in the query text)</h2>
 *
 * <ul>
 *   <li>{@code instanceof StringValue} true (a QUOTED numeric literal, e.g. {@code
 *       expires_at: "1690000000"}) — never exercised before; GraphQL itself allows a string
 *       literal for a custom scalar argument, and {@code DateTimeScalar} explicitly accepts it.
 *   <li>Neither {@code StringValue} nor {@code IntValue} (e.g. a float literal, {@code
 *       expires_at: 3.14}) -&gt; {@code CoercingParseLiteralException}, surfaced by graphql-java as
 *       a document-validation error (HTTP 200, one {@code errors} entry) BEFORE any resolver runs.
 * </ul>
 *
 * <h2>{@code parseValue} (value supplied via GraphQL {@code variables})</h2>
 *
 * <p>Reachable via the real HTTP/GraphQL contract (the JSON body's top-level {@code variables}
 * field, read by {@code GraphQLRequest#buildFromPayload} and fed into {@code
 * ExecutionInput#variables}, see {@code GraphQLController}) — no seam extension needed: the
 * existing generic {@code FilesTestApp#sendForm(HttpRequest)} already forwards a raw body
 * verbatim, so a hand-built {@code {"query":...,"variables":{...}}} JSON payload reaches {@code
 * /graphql/} exactly as a real client's would. Jackson's untyped {@code Map.class} deserialization
 * (used by {@code GraphQLRequest.buildFromPayload}) maps a small JSON integer to {@code Integer},
 * a JSON integer outside the {@code int} range to {@code Long}, and a JSON string to {@code
 * String} — which is what lets each of the three source branches be triggered independently:
 *
 * <ul>
 *   <li>{@code instanceof Long} true — a variable value outside the {@code int} range.
 *   <li>{@code instanceof Integer} true — an ordinary small variable value (the realistic case a
 *       real GraphQL client would send).
 *   <li>Neither -&gt; {@code CoercingParseValueException} (e.g. a string variable value) —
 *       surfaced the same way as the {@code parseLiteral} throw case above.
 * </ul>
 */
@QuarkusTest
@QuarkusTestResource(FilesStackTestResource.class)
class DateTimeScalarApiIT {

  static FilesTestApp app;

  private static final String OWNER_ID = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
  private static final String OWNER_COOKIE = "ZM_AUTH_TOKEN=fake-token";

  @BeforeAll
  static void init() {
    app =
        QuarkusFilesTestAppBuilder.aFilesTestApp()
            .withDatabase()
            .withServiceDiscover()
            .withUserManagement(Map.of("fake-token", OWNER_ID))
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

  private String seedLinkableNode(String nodeId) {
    app.backdoor()
        .populator()
        .addNode(
            new PopulatorNode(
                nodeId,
                OWNER_ID,
                OWNER_ID,
                "LOCAL_ROOT",
                "linkable.txt",
                "",
                NodeType.TEXT,
                "LOCAL_ROOT",
                10L,
                "text/plain"));
    return nodeId;
  }

  // --- parseLiteral -------------------------------------------------------------------------

  @Test
  void givenAQuotedNumericStringLiteralExpiresAtCreateLinkShouldSucceed() {
    // Given
    String nodeId = seedLinkableNode("00000000-0000-0000-0000-000000000230");
    String bodyPayload =
        GraphqlCommandBuilder.aMutationBuilder("createLink")
            .withString("node_id", nodeId)
            .withString("expires_at", "1690000000")
            .withWantedResultFormat("{ id expires_at }")
            .build();

    // When
    HttpResponse httpResponse =
        app.send(HttpRequest.of("POST", "/graphql/", OWNER_COOKIE, bodyPayload));

    // Then — DateTimeScalar#parseLiteral's `instanceof StringValue` branch: a quoted numeric
    // literal is accepted exactly like an unquoted one.
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(200);
    Assertions.assertThat(TestUtils.jsonResponseToErrors(httpResponse.getBodyPayload())).isEmpty();
    Map<String, Object> link = TestUtils.jsonResponseToMap(httpResponse.getBodyPayload(), "createLink");
    Assertions.assertThat(link).containsEntry("expires_at", 1690000000);
  }

  @Test
  void givenAFloatLiteralExpiresAtCreateLinkShouldSurfaceACoercionError() {
    // Given — neither StringValue nor IntValue -> DateTimeScalar#parseLiteral's throw branch.
    String nodeId = seedLinkableNode("00000000-0000-0000-0000-000000000231");
    String bodyPayload =
        GraphqlCommandBuilder.aMutationBuilder("createLink")
            .withString("node_id", nodeId)
            .withEnumLiteral("expires_at", "3.14") // raw unquoted literal, not a real enum
            .withWantedResultFormat("{ id }")
            .build();

    // When
    HttpResponse httpResponse =
        app.send(HttpRequest.of("POST", "/graphql/", OWNER_COOKIE, bodyPayload));

    // Then — a document-validation error, before createLink's resolver ever runs.
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(200);
    List<String> errors = TestUtils.jsonResponseToErrors(httpResponse.getBodyPayload());
    Assertions.assertThat(errors).hasSize(1);
    Assertions.assertThat(errors.get(0)).contains("date");
    Assertions.assertThat(TestUtils.jsonResponseToValue(httpResponse.getBodyPayload(), "createLink"))
        .isEmpty();
  }

  // --- parseValue (GraphQL variables) --------------------------------------------------------

  private HttpResponse createLinkWithVariableExpiresAt(String nodeId, String rawJsonExpiresAtValue) {
    String body =
        "{\"query\":\"mutation($nodeId: ID!, $exp: DateTime) { createLink(node_id: $nodeId,"
            + " expires_at: $exp) { id expires_at } }\","
            + "\"variables\":{\"nodeId\":\""
            + nodeId
            + "\",\"exp\":"
            + rawJsonExpiresAtValue
            + "}}";
    return app.sendForm(HttpRequest.of("POST", "/graphql/", OWNER_COOKIE, body));
  }

  @Test
  void givenASmallIntegerVariableExpiresAtCreateLinkShouldSucceed() {
    // Given — Jackson deserializes a small JSON number into an Integer -> parseValue's
    // `instanceof Integer` branch (the realistic case: a real client sends a JSON number).
    String nodeId = seedLinkableNode("00000000-0000-0000-0000-000000000232");

    // When
    HttpResponse httpResponse = createLinkWithVariableExpiresAt(nodeId, "5");

    // Then
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(200);
    Assertions.assertThat(TestUtils.jsonResponseToErrors(httpResponse.getBodyPayload())).isEmpty();
    Map<String, Object> link = TestUtils.jsonResponseToMap(httpResponse.getBodyPayload(), "createLink");
    Assertions.assertThat(link).containsEntry("expires_at", 5);
  }

  @Test
  void givenALargeIntegerVariableExpiresAtCreateLinkShouldSucceed() {
    // Given — a JSON number outside the int range deserializes to a Long -> parseValue's
    // `instanceof Long` branch, never exercised by the small-integer scenario above.
    String nodeId = seedLinkableNode("00000000-0000-0000-0000-000000000233");

    // When
    HttpResponse httpResponse = createLinkWithVariableExpiresAt(nodeId, "99999999999999");

    // Then
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(200);
    Assertions.assertThat(TestUtils.jsonResponseToErrors(httpResponse.getBodyPayload())).isEmpty();
    Map<String, Object> link = TestUtils.jsonResponseToMap(httpResponse.getBodyPayload(), "createLink");
    Assertions.assertThat(link).containsEntry("expires_at", 99999999999999L);
  }

  @Test
  void givenAStringVariableExpiresAtCreateLinkShouldSurfaceACoercionError() {
    // Given — neither Long nor Integer -> DateTimeScalar#parseValue's throw branch.
    String nodeId = seedLinkableNode("00000000-0000-0000-0000-000000000234");

    // When
    HttpResponse httpResponse = createLinkWithVariableExpiresAt(nodeId, "\"not-a-timestamp\"");

    // Then
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(200);
    List<String> errors = TestUtils.jsonResponseToErrors(httpResponse.getBodyPayload());
    Assertions.assertThat(errors).hasSize(1);
    Assertions.assertThat(errors.get(0)).contains("date");
    Assertions.assertThat(TestUtils.jsonResponseToValue(httpResponse.getBodyPayload(), "createLink"))
        .isEmpty();
  }
}
