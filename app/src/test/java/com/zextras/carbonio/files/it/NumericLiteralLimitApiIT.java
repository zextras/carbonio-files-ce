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

class NumericLiteralLimitApiIT extends AbstractFilesIT {

  private static Response postAnonymous(String body) {
    return RestAssured.given().contentType("application/json").body(body).post("/graphql");
  }

  private static String queryBody(String query) {
    return "{\"query\":" + jsonString(query) + "}";
  }

  private static String jsonString(String value) {
    return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
  }

  private static String createLinkWithLiteral(String literal) {
    return queryBody("mutation { createLink(node_id: \"x\", expires_at: " + literal + ") { id } }");
  }

  private static List<String> errorCodes(Response response) {
    List<String> codes = response.jsonPath().getList("errors.extensions.errorCode");
    return codes == null ? List.of() : codes;
  }

  @Test
  void givenAnOversizedExponentLiteralThenItFailsValidationBeforeAuthentication() {
    Response response = postAnonymous(createLinkWithLiteral("1e6400000"));

    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    Assertions.assertThat(response.jsonPath().getList("errors")).isNotEmpty();
    Assertions.assertThat(response.jsonPath().getString("errors[0].extensions.classification"))
        .isEqualTo("ValidationError");
    Assertions.assertThat(errorCodes(response)).doesNotContain("UNAUTHENTICATED");
    Assertions.assertThat(response.jsonPath().getMap("$")).doesNotContainKey("data");
  }
}
