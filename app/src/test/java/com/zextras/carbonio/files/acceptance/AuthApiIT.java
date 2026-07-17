// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.acceptance;

import com.zextras.carbonio.files.FilesStackTestResource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;

import com.zextras.carbonio.files.acceptance.seam.FilesTestApp;
import com.zextras.carbonio.files.acceptance.seam.impl.QuarkusFilesTestAppBuilder;
import com.zextras.carbonio.files.utilities.http.HttpRequest;
import com.zextras.carbonio.files.utilities.http.HttpResponse;
import java.util.Map;
import java.util.stream.Stream;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Covers every non-happy branch of {@code AuthenticationHandler} (Task 1.1 of the acceptance
 * coverage-expansion plan): missing cookie, cookie without {@code ZM_AUTH_TOKEN}, an unresolvable
 * token, an inactive user, a guest user, and a user with the Files feature flag disabled. All six
 * are driven through the same authenticated GraphQL route ({@code POST /graphql/}); a seventh test
 * confirms the same handler also guards {@code GET /download/{id}}.
 */
@QuarkusTest
@QuarkusTestResource(FilesStackTestResource.class)
class AuthApiIT {

  static FilesTestApp app;

  private static final String ACTIVE_USER_ID = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
  private static final String TRIVIAL_QUERY = "query { getRootsList { id } }";

  @BeforeAll
  static void init() {
    app =
        QuarkusFilesTestAppBuilder.aFilesTestApp()
            .withDatabase()
            .withServiceDiscover()
            .withUserManagement(Map.of("active-token", ACTIVE_USER_ID))
            .build();

    // Fixtures for the non-happy-path branches, registered once — each test picks its own cookie.
    app.mocks()
        .registerUser("maintenance-token", "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbb1", "maintenance", false, true);
    app.mocks()
        .registerUser("guest-token", "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbb2", "active", true, true);
    app.mocks()
        .registerUser("flag-off-token", "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbb3", "active", false, false);
    // "unknown-token" is deliberately never registered on either UM fixture map.
  }

  @AfterEach
  void cleanUp() {
    app.backdoor().resetDatabase();
  }

  @AfterAll
  static void cleanUpAll() {
    app.close();
  }

  static Stream<Arguments> nonHappyAuthScenarios() {
    return Stream.of(
        Arguments.of("missing Cookie header entirely", null, "Missing cookies"),
        Arguments.of("Cookie header without ZM_AUTH_TOKEN", "other=1", "Missing cookies"),
        Arguments.of(
            "token not resolvable by user-management",
            "ZM_AUTH_TOKEN=unknown-token",
            "Unable to find requested user"),
        Arguments.of(
            "user status is not ACTIVE (MAINTENANCE)",
            "ZM_AUTH_TOKEN=maintenance-token",
            "User is not active"),
        Arguments.of("user type is GUEST", "ZM_AUTH_TOKEN=guest-token", "User is not internal"),
        Arguments.of(
            "carbonioFeatureFilesEnabled is off",
            "ZM_AUTH_TOKEN=flag-off-token",
            "User is not internal"));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("nonHappyAuthScenarios")
  void givenNonHappyAuthBranchOnGraphqlThenRequestIsRejectedWith401(
      String scenarioName, String cookie, String expectedMessageFragment) {
    // Given
    HttpRequest httpRequest = HttpRequest.of("POST", "/graphql/", cookie, TRIVIAL_QUERY);

    // When
    HttpResponse httpResponse = app.send(httpRequest);

    // Then
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(401);
    Assertions.assertThat(httpResponse.getBodyPayload()).contains(expectedMessageFragment);
  }

  @Test
  void givenMissingCookieOnDownloadRouteThenRequestIsRejectedWith401BySameHandler() {
    // Given — /download/{id} is routed through the same auth-handler as /graphql/.
    HttpRequest httpRequest =
        HttpRequest.of("GET", "/download/00000000-0000-0000-0000-000000000000", null, null);

    // When
    HttpResponse httpResponse = app.send(httpRequest);

    // Then
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(401);
    Assertions.assertThat(httpResponse.getBodyPayload()).contains("Missing cookies");
  }
}
