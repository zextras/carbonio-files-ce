// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.it;

import com.zextras.carbonio.files.it.support.AbstractFilesIT;
import io.restassured.RestAssured;
import io.restassured.response.Response;
import java.util.List;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

class InlineLiteralLengthLimitApiIT extends AbstractFilesIT {

  private static Response postAnonymous(String body) {
    return RestAssured.given().contentType("application/json").body(body).post("/graphql");
  }

  private static String queryBody(String query) {
    return "{\"query\":" + jsonString(query) + "}";
  }

  private static String jsonString(String value) {
    return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
  }

  private static String createLink(String expiresAt, String description) {
    String descriptionArgument =
        description == null ? "" : ", description: \"" + description + "\"";
    return queryBody(
        "mutation { createLink(node_id: \"x\", expires_at: \""
            + expiresAt
            + "\""
            + descriptionArgument
            + ") { id } }");
  }

  private static List<String> errorCodes(Response response) {
    List<String> codes = response.jsonPath().getList("errors.extensions.errorCode");
    return codes == null ? List.of() : codes;
  }

  private static void assertRejectedAsUnparseable(Response response) {
    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    Assertions.assertThat(response.jsonPath().getList("errors")).isNotEmpty();
    Assertions.assertThat(response.jsonPath().getString("errors[0].message"))
        .contains("Unparseable input document");
    Assertions.assertThat(errorCodes(response)).doesNotContain("UNAUTHENTICATED");
    Assertions.assertThat(response.jsonPath().getMap("$")).doesNotContainKey("data");
  }

  @Test
  void givenAnOversizedQuotedNumericLiteralThenItIsRejectedBeforeCoercion() {
    assertRejectedAsUnparseable(postAnonymous(createLink("9".repeat(9000), null)));
  }

  @Test
  void givenAnOversizedStringLiteralThenItIsRejectedBeforeCoercion() {
    assertRejectedAsUnparseable(postAnonymous(createLink("1760000000", "a".repeat(9000))));
  }

  @Test
  void givenAShortQuotedNumericLiteralThenItReachesAuthentication() {
    Response response = postAnonymous(createLink("1760000000", null));

    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    Assertions.assertThat(errorCodes(response)).contains("UNAUTHENTICATED");
  }
}
