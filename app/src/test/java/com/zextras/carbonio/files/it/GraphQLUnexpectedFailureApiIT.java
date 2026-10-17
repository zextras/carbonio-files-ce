// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.it;

import com.zextras.carbonio.files.FilesStackTestResource;
import com.zextras.carbonio.files.it.support.AbstractFilesIT;
import io.restassured.RestAssured;
import io.restassured.response.Response;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

/** A numeric operationName fails the request outside the execution, as an Error in a resolver. */
class GraphQLUnexpectedFailureApiIT extends AbstractFilesIT {

  @Test
  void givenAnUnexpectedFailureTheResponseIsA500WithAnEmptyBody() {
    Response response =
        RestAssured.given()
            .contentType("application/json")
            .accept("*/*")
            .header("Cookie", "ZM_AUTH_TOKEN=" + FilesStackTestResource.AUTH_TOKEN)
            .body("{\"query\":\"query { getConfigs { name } }\",\"operationName\":1}")
            .post("/graphql/");

    Assertions.assertThat(response.getStatusCode()).isEqualTo(500);
    Assertions.assertThat(response.getBody().asString()).isEmpty();
  }
}
