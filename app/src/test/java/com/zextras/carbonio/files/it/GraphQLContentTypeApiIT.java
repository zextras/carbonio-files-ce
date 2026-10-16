// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.it;

import com.zextras.carbonio.files.FilesStackTestResource;
import com.zextras.carbonio.files.TestUtils;
import com.zextras.carbonio.files.it.support.AbstractFilesIT;
import io.restassured.RestAssured;
import io.restassured.response.Response;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** The Carbonio proxy compresses application/json only, so GraphQL answers with it, like main. */
class GraphQLContentTypeApiIT extends AbstractFilesIT {

  @ParameterizedTest
  @CsvSource(
      delimiter = '|',
      value = {
        "*/*                               | application/json; charset=UTF-8",
        "application/graphql-response+json | application/json",
        "application/json                  | application/json"
      })
  void givenAnAcceptHeaderTheGraphQLResponseIsApplicationJson(String accept, String contentType) {
    Response response =
        RestAssured.given()
            .contentType("application/json")
            .accept(accept)
            .header("Cookie", "ZM_AUTH_TOKEN=" + FilesStackTestResource.AUTH_TOKEN)
            .body(TestUtils.queryPayload("query { getConfigs { name value } }"))
            .post("/graphql/");

    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    Assertions.assertThat(TestUtils.jsonResponseToErrors(response.getBody().asString())).isEmpty();
    Assertions.assertThat(response.getHeader("Content-Type")).isEqualTo(contentType);
  }
}
