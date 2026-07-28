// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.it;

import com.zextras.carbonio.files.FilesStackTestResource;
import com.zextras.carbonio.files.TestUtils;
import com.zextras.carbonio.files.it.support.AbstractFilesIT;
import io.restassured.response.Response;
import java.util.List;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * {@code com.zextras.carbonio.files.acceptance.IntrospectionApiIT} rewritten as an out-of-process
 * {@code @QuarkusIntegrationTest} on {@link AbstractFilesIT}: the authenticated {@code /graphql/}
 * endpoint blocks {@code __schema}/{@code __type} introspection ({@code
 * GraphQLProvider#buildSchema}'s {@code BlockedFields.newBlock().addPattern("__.*")}
 * field-visibility transform). Contrast with {@link PublicGraphQLIntrospectionApiIT}, where the
 * public endpoint applies no such transform. Doubles as a native-smoke surface under {@code
 * -Dnative} (broad schema-reflection coverage). The 1 method and its assertion are preserved
 * verbatim; only the transport changed.
 */
class IntrospectionApiIT extends AbstractFilesIT {

  private static final String REQUESTER_ID = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
  private static final String REQUESTER_COOKIE = "ZM_AUTH_TOKEN=fake-token";

  @BeforeAll
  static void registerUsers() {
    FilesStackTestResource.getUserManagementService().registerToken("fake-token", REQUESTER_ID);
  }

  @Test
  void givenIntrospectionIsDisabledWhenIntrospectionQueryIsSentThenItShouldFail() {
    // Given
    String introspectionQuery = "query introspectionQuery { __schema { types { name } } }";

    // When
    Response response = graphql(introspectionQuery, REQUESTER_COOKIE);

    // Then
    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);

    List<String> errors = TestUtils.jsonResponseToErrors(response.getBody().asString());

    Assertions.assertThat(errors).isNotEmpty();
    Assertions.assertThat(errors.get(0)).contains("Validation error");
  }
}
