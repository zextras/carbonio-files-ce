// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.it;

import com.zextras.carbonio.files.FilesStackTestResource;
import com.zextras.carbonio.files.it.support.AbstractFilesIT;
import io.restassured.response.Response;
import java.util.stream.Stream;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * {@code com.zextras.carbonio.files.acceptance.AuthApiIT} rewritten as an out-of-process
 * {@code @QuarkusIntegrationTest} on {@link AbstractFilesIT}. Covers every non-happy branch of
 * {@code AuthenticationHandler}: missing cookie, cookie without {@code ZM_AUTH_TOKEN}, an
 * unresolvable token, an inactive user, a guest user, and a user with the Files feature flag
 * disabled. All six are driven through the same authenticated GraphQL route ({@code POST
 * /graphql/}).
 *
 * <p>CO-3482 (devel #301): a genuine auth failure (missing/invalid credentials, unresolvable user)
 * returns 401, while an authenticated-but-not-entitled user (inactive account, guest, or Files
 * feature disabled) returns 403 instead. The two response families are covered by separate
 * parameterized tests below — carried over VERBATIM from the seam version (commit 70dbf4c5, which
 * fixed these from a uniform 401 to the 401/403 split): do NOT regress these back to 401.
 *
 * <p>{@code GET /download/{id}} is guarded by an equivalent-but-distinct JAX-RS handler ({@code
 * BlobAuthenticator}, the REST counterpart of the GraphQL {@code FilesAuthenticationFilter}); it
 * applies the identical checks and status codes, verified by the trailing tests (including the
 * parameterized 403 coverage on this route).
 *
 * <p>All 8 methods/scenarios (2 parameterized ×3 + 1 plain + 1 parameterized ×3) and their
 * assertions are preserved verbatim; only the transport (RestAssured instead of the seam) and the
 * user-fixture registration ({@link FilesStackTestResource#getUserManagementService()}'s 5-arg
 * {@code registerToken}, replacing {@code Mocks#registerUser}) changed.
 */
class AuthApiIT extends AbstractFilesIT {

  private static final String ACTIVE_USER_ID = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
  private static final String TRIVIAL_QUERY = "query { getRootsList { id } }";

  @BeforeAll
  static void registerUsers() {
    FilesStackTestResource.getUserManagementService().registerToken("active-token", ACTIVE_USER_ID);

    // Fixtures for the non-happy-path branches, registered once — each test picks its own cookie.
    FilesStackTestResource.getUserManagementService()
        .registerToken(
            "maintenance-token",
            "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbb1",
            "maintenance",
            false,
            true);
    FilesStackTestResource.getUserManagementService()
        .registerToken("guest-token", "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbb2", "active", true, true);
    FilesStackTestResource.getUserManagementService()
        .registerToken(
            "flag-off-token", "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbb3", "active", false, false);
    // "unknown-token" is deliberately never registered on either UM fixture map.
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
    // When
    Response response = graphql(TRIVIAL_QUERY, cookie);

    // Then
    Assertions.assertThat(response.getStatusCode()).isEqualTo(401);
    Assertions.assertThat(response.getBody().asString()).contains(expectedMessageFragment);
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("forbiddenAuthScenarios")
  void givenForbiddenAuthBranchOnGraphqlThenRequestIsRejectedWith403(
      String scenarioName, String cookie, String expectedMessageFragment) {
    // Given — authenticated but not entitled: inactive, guest, or feature disabled (CO-3482).
    // When
    Response response = graphql(TRIVIAL_QUERY, cookie);

    // Then
    Assertions.assertThat(response.getStatusCode()).isEqualTo(403);
    Assertions.assertThat(response.getBody().asString()).contains(expectedMessageFragment);
  }

  @Test
  void givenMissingCookieOnDownloadRouteThenRequestIsRejectedWith401ByBlobAuthenticator() {
    // Given — /download/{id} is routed through BlobAuthenticator, the JAX-RS counterpart of
    // FilesAuthenticationFilter; missing credentials is a genuine auth failure.
    // When
    Response response = download("00000000-0000-0000-0000-000000000000", null);

    // Then
    Assertions.assertThat(response.getStatusCode()).isEqualTo(401);
    Assertions.assertThat(response.getBody().asString()).contains("Missing cookies");
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("forbiddenAuthScenarios")
  void givenForbiddenAuthBranchOnDownloadRouteThenRequestIsRejectedWith403ByBlobAuthenticator(
      String scenarioName, String cookie, String expectedMessageFragment) {
    // Given — BlobAuthenticator applies the identical status/type/feature-flag checks as
    // FilesAuthenticationFilter (CO-3482): authenticated but not entitled -> 403.
    // When
    Response response = download("00000000-0000-0000-0000-000000000000", cookie);

    // Then
    Assertions.assertThat(response.getStatusCode()).isEqualTo(403);
    Assertions.assertThat(response.getBody().asString()).contains(expectedMessageFragment);
  }
}
