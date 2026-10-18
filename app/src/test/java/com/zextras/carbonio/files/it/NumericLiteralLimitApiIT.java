// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.it;

import com.zextras.carbonio.files.it.support.AbstractFilesIT;
import io.restassured.RestAssured;
import io.restassured.config.HttpClientConfig;
import io.restassured.config.RestAssuredConfig;
import io.restassured.response.Response;
import java.util.List;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class NumericLiteralLimitApiIT extends AbstractFilesIT {

  private static final long MAX_MILLIS = 2000;
  private static final int CLIENT_TIMEOUT_MILLIS = 120_000;
  private static final RestAssuredConfig CLIENT_CONFIG =
      RestAssuredConfig.config()
          .httpClient(
              HttpClientConfig.httpClientConfig()
                  .setParam("http.connection.timeout", CLIENT_TIMEOUT_MILLIS)
                  .setParam("http.socket.timeout", CLIENT_TIMEOUT_MILLIS));

  @BeforeEach
  void warmUpGraphQlEngine() {
    postAnonymous(queryBody("mutation { createLink(node_id: \"x\", expires_at: 1) { id } }"));
  }

  private static Response postAnonymous(String body) {
    return RestAssured.given()
        .config(CLIENT_CONFIG)
        .contentType("application/json")
        .body(body)
        .post("/graphql");
  }

  private static String queryBody(String query) {
    return "{\"query\":" + jsonString(query) + "}";
  }

  private static String jsonString(String value) {
    return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
  }

  private static Response timedAnonymousPost(String body, long[] elapsedMillis) {
    long start = System.nanoTime();
    Response response = postAnonymous(body);
    elapsedMillis[0] = (System.nanoTime() - start) / 1_000_000;
    return response;
  }

  private static String createLinkWithLiteral(String literal) {
    return queryBody("mutation { createLink(node_id: \"x\", expires_at: " + literal + ") { id } }");
  }

  private static List<String> errorCodes(Response response) {
    List<String> codes = response.jsonPath().getList("errors.extensions.errorCode");
    return codes == null ? List.of() : codes;
  }

  @Test
  void givenAnOversizedExponentLiteralThenItFailsValidationBeforeAuthenticationAndFast() {
    long[] elapsed = new long[1];

    Response response = timedAnonymousPost(createLinkWithLiteral("1e6400000"), elapsed);

    Assertions.assertThat(elapsed[0]).isLessThan(MAX_MILLIS);
    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    Assertions.assertThat(response.jsonPath().getList("errors")).isNotEmpty();
    Assertions.assertThat(response.jsonPath().getString("errors[0].extensions.classification"))
        .isEqualTo("ValidationError");
    Assertions.assertThat(errorCodes(response)).doesNotContain("UNAUTHENTICATED");
    Assertions.assertThat(response.jsonPath().getMap("$")).doesNotContainKey("data");
  }

  @Test
  void givenATwoHundredDigitLiteralThenTheDocumentIsUnparseableBeforeAuthenticationAndFast() {
    long[] elapsed = new long[1];

    Response response = timedAnonymousPost(createLinkWithLiteral("9".repeat(200)), elapsed);

    Assertions.assertThat(elapsed[0]).isLessThan(MAX_MILLIS);
    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    Assertions.assertThat(response.jsonPath().getString("errors[0].message"))
        .isEqualTo("Unparseable input document");
    Assertions.assertThat(errorCodes(response)).doesNotContain("UNAUTHENTICATED");
    Assertions.assertThat(response.jsonPath().getMap("$")).doesNotContainKey("data");
  }

  @Test
  void givenANinetyNineDigitLiteralThenItStillParsesAndReachesAuthentication() {
    Response response = postAnonymous(createLinkWithLiteral("9".repeat(99)));

    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    Assertions.assertThat(errorCodes(response)).contains("UNAUTHENTICATED");
  }

  @Test
  void hugeNumericVariableDoesNotReachExactConversion() {
    long[] elapsed = new long[1];

    Response response =
        timedAnonymousPost(
            "{\"query\":"
                + jsonString(
                    "mutation($e: BigInteger) { createLink(node_id: \"x\", expires_at: $e) { id"
                        + " } }")
                + ",\"variables\":{\"e\":1e6400000}}",
            elapsed);

    Assertions.assertThat(elapsed[0]).isLessThan(MAX_MILLIS);
    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    Assertions.assertThat(response.jsonPath().getList("errors")).isNotEmpty();
    Assertions.assertThat(errorCodes(response)).contains("UNAUTHENTICATED");
  }
}
