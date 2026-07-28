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
 * are driven through the same authenticated GraphQL route ({@code POST /graphql/}).
 *
 * <p>CO-3482 (devel #301, ported to Quarkus): a genuine auth failure (missing/invalid credentials,
 * unresolvable user) returns 401, while an authenticated-but-not-entitled user (inactive account,
 * guest, or Files feature disabled) returns 403 instead. The two response families are covered by
 * separate parameterized tests below.
 *
 * <p>{@code GET /download/{id}} is guarded by an equivalent-but-distinct JAX-RS handler ({@code
 * BlobAuthenticator}, the REST counterpart of the GraphQL {@code FilesAuthenticationFilter}); it
 * applies the identical checks and status codes, verified by the trailing tests.
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

  static Stream<Arguments> unauthorizedAuthScenarios() {
    return Stream.of(
        Arguments.of("missing Cookie header entirely", null, "Missing cookies"),
        Arguments.of("Cookie header without ZM_AUTH_TOKEN", "other=1", "Missing cookies"),
        Arguments.of(
            "token not resolvable by user-management",
            "ZM_AUTH_TOKEN=unknown-token",
            "Unable to find requested user"));
  }

  static Stream<Arguments> forbiddenAuthScenarios() {
    return Stream.of(
        Arguments.of(
            "user status is not ACTIVE (MAINTENANCE)",
            "ZM_AUTH_TOKEN=maintenance-token",
            "User is not active"),
        Arguments.of("user type is GUEST", "ZM_AUTH_TOKEN=guest-token", "User is not internal"),
        Arguments.of(
            "carbonioFeatureFilesEnabled is off",
            "ZM_AUTH_TOKEN=flag-off-token",
            "Files feature is not enabled for user"));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("unauthorizedAuthScenarios")
  void givenUnauthorizedAuthBranchOnGraphqlThenRequestIsRejectedWith401(
      String scenarioName, String cookie, String expectedMessageFragment) {
    // Given — genuine auth failure: missing/invalid credentials or unresolvable user.
    HttpRequest httpRequest = HttpRequest.of("POST", "/graphql/", cookie, TRIVIAL_QUERY);

    // When
    HttpResponse httpResponse = app.send(httpRequest);

    // Then
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(401);
    Assertions.assertThat(httpResponse.getBodyPayload()).contains(expectedMessageFragment);
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("forbiddenAuthScenarios")
  void givenForbiddenAuthBranchOnGraphqlThenRequestIsRejectedWith403(
      String scenarioName, String cookie, String expectedMessageFragment) {
    // Given — authenticated but not entitled: inactive, guest, or feature disabled (CO-3482).
    HttpRequest httpRequest = HttpRequest.of("POST", "/graphql/", cookie, TRIVIAL_QUERY);

    // When
    HttpResponse httpResponse = app.send(httpRequest);

    // Then
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(403);
    Assertions.assertThat(httpResponse.getBodyPayload()).contains(expectedMessageFragment);
  }

  @Test
  void givenMissingCookieOnDownloadRouteThenRequestIsRejectedWith401ByBlobAuthenticator() {
    // Given — /download/{id} is routed through BlobAuthenticator, the JAX-RS counterpart of
    // FilesAuthenticationFilter; missing credentials is a genuine auth failure.
    HttpRequest httpRequest =
        HttpRequest.of("GET", "/download/00000000-0000-0000-0000-000000000000", null, null);

    // When
    HttpResponse httpResponse = app.send(httpRequest);

    // Then
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(401);
    Assertions.assertThat(httpResponse.getBodyPayload()).contains("Missing cookies");
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("forbiddenAuthScenarios")
  void givenForbiddenAuthBranchOnDownloadRouteThenRequestIsRejectedWith403ByBlobAuthenticator(
      String scenarioName, String cookie, String expectedMessageFragment) {
    // Given — BlobAuthenticator applies the identical status/type/feature-flag checks as
    // FilesAuthenticationFilter (CO-3482): authenticated but not entitled -> 403.
    HttpRequest httpRequest =
        HttpRequest.of(
            "GET", "/download/00000000-0000-0000-0000-000000000000", cookie, null);

    // When
    HttpResponse httpResponse = app.send(httpRequest);

    // Then
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(403);
    Assertions.assertThat(httpResponse.getBodyPayload()).contains(expectedMessageFragment);
  }
}
