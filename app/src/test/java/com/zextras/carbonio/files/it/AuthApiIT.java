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
 * {@code @QuarkusIntegrationTest} on {@link AbstractFilesIT}. Covers every non-happy branch of the
 * unified {@code POST /graphql} endpoint's auth flow.
 *
 * <p><b>Unified-endpoint paradigm (feat/graphql-code-first):</b> {@code FilesGraphQLAuthMechanism}
 * returns an ANONYMOUS identity when no {@code ZM_AUTH_TOKEN} cookie is present — this is REQUIRED
 * so {@code @PermitAll} public operations ({@code getPublicNode}, {@code findPublicNodes}) succeed
 * unauthenticated. A no-cookie call to an {@code @Authenticated} operation therefore fails DURING
 * GraphQL execution (Quarkus security throws {@code UnauthorizedException}), which SmallRye renders
 * as HTTP 200 with a GraphQL error whose {@code extensions.errorCode} is {@code "UNAUTHENTICATED"}
 * and whose {@code data} is {@code null}. This is security-safe (no data leak) and
 * machine-detectable.
 *
 * <p>Three distinct HTTP outcomes on the {@code /graphql} route:
 *
 * <ol>
 *   <li>No {@code ZM_AUTH_TOKEN} cookie (missing entirely, or cookie present but without the token)
 *       → HTTP <b>200</b> + GraphQL error with {@code errorCode:"UNAUTHENTICATED"} + {@code
 *       "data":null}.
 *   <li>Cookie present but token not resolvable by user-management → HTTP <b>401</b> (mechanism
 *       throws {@code AuthenticationFailedException} → {@code sendChallenge} writes plain-text
 *       body).
 *   <li>Authenticated but not entitled (inactive / GUEST / files-feature-off) → HTTP <b>403</b>.
 * </ol>
 *
 * <p>CO-3482 (devel #301): the 401/403 split is preserved and must never regress.
 *
 * <p>{@code GET /download/{id}} is guarded by {@code BlobAuthenticator} (the REST counterpart); it
 * applies identical checks and status codes, verified by the trailing tests.
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

  static Stream<Arguments> noCookieScenarios() {
    return Stream.of(
        Arguments.of("missing Cookie header entirely", (String) null),
        Arguments.of("Cookie header without ZM_AUTH_TOKEN", "other=1"));
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
  @MethodSource("noCookieScenarios")
  void givenNoCookieOnGraphqlThenReturns200WithUnauthenticatedErrorCode(
      String scenarioName, String cookie) {
    // Given — no cookie → anonymous identity → @Authenticated throws UnauthorizedException in
    // DataFetcher → HTTP 200 with errorCode:"UNAUTHENTICATED", data:null (no leak).
    // When
    Response response = graphql(TRIVIAL_QUERY, cookie);

    // Then
    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    String body = response.getBody().asString();
    Assertions.assertThat(body).contains("UNAUTHENTICATED");
    Assertions.assertThat(body).contains("\"data\":null");
  }

  @Test
  void givenUnresolvableTokenOnGraphqlThenRequestIsRejectedWith401() {
    // Given — ZM_AUTH_TOKEN present but unknown to user-management → mechanism throws
    // AuthenticationFailedException → sendChallenge writes 401 + plain-text body.
    // When
    Response response = graphql(TRIVIAL_QUERY, "ZM_AUTH_TOKEN=unknown-token");

    // Then
    Assertions.assertThat(response.getStatusCode()).isEqualTo(401);
    Assertions.assertThat(response.getBody().asString()).contains("Unable to find requested user");
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
