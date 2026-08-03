// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.utilities;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.stubbing.StubMapping;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory REST fake for carbonio-user-management, backed by a dedicated {@link WireMockServer}.
 * Stubs the {@code /internal/users/*} endpoints ({@code getUserMyself} by {@code ZM_AUTH_TOKEN}
 * header, {@code getUserById}/{@code getUserByEmail} lookups) exactly like the real REST SDK
 * (carbonio-user-management-rest-sdk) contract that {@code UserRepositoryImpl} calls.
 *
 * <p>Replaces the P3a in-process gRPC stub ({@code UserManagementServiceImplBase} over
 * grpc-netty-shaded): same public API (registerToken/registerUserById/unregisterUserById/setDown/
 * clearAll) and the same test DATA/expectations, only the transport changed.
 *
 * <p>bump-um-sdk (1.3.0-1): {@code UserResourceApi#internalUsersMyselfGet} switched from a
 * caller-built {@code Cookie} header to sending the token as a plain {@code ZM_AUTH_TOKEN} HTTP
 * header directly (see the generated client's request builder); the {@code myself} stub below is
 * matched on that header, not a cookie.
 */
public class MockUserManagementService {

  private static final String ZM_AUTH_TOKEN_COOKIE = "ZM_AUTH_TOKEN";
  private static final String DEFAULT_EMAIL = "fake-email@example.com";
  private static final String DEFAULT_FULL_NAME = "Fake User";
  private static final String DEFAULT_DOMAIN = "example.com";
  private static final String DEFAULT_LOCALE = "en";

  private final WireMockServer server;

  /** token -> currently-registered {@code /internal/users/myself} stub, so it can be overwritten/removed. */
  private final Map<String, StubMapping> myselfStubsByToken = new ConcurrentHashMap<>();

  /** userId -> currently-registered {@code /internal/users/id/{userId}} stub. */
  private final Map<String, StubMapping> byIdStubs = new ConcurrentHashMap<>();

  /** email -> currently-registered {@code /internal/users/email/{email}} stub. */
  private final Map<String, StubMapping> byEmailStubs = new ConcurrentHashMap<>();

  /** userId -> its currently-registered email, so changing/removing a user also cleans up its email stub. */
  private final Map<String, String> userIdToEmail = new ConcurrentHashMap<>();

  /**
   * Reversible "user-management is unreachable" switch: while set, a high-priority stub answers
   * every {@code /internal/users/*} call with {@code 503}, shadowing any registered fixture.
   * Cleared on {@code close()}/{@code reset()} so a later test class is not poisoned. Backs {@code
   * Mocks#userManagementDown()}.
   */
  private volatile StubMapping downStub;

  public MockUserManagementService() {
    server = new WireMockServer(WireMockConfiguration.options().dynamicPort());
    server.start();
    setupCatchAll();
  }

  /**
   * Low-priority fallback stubs: any unregistered token/userId/email falls through to these,
   * mirroring the gRPC fake's {@code UNAUTHENTICATED}/{@code NOT_FOUND} responses.
   */
  private void setupCatchAll() {
    server.stubFor(
        get(urlPathEqualTo("/q/health/live"))
            .atPriority(10)
            .willReturn(aResponse().withStatus(200)));
    server.stubFor(
        get(urlPathEqualTo("/internal/users/myself"))
            .atPriority(10)
            .willReturn(aResponse().withStatus(401)));
    server.stubFor(
        get(urlPathMatching("/internal/users/id/.*"))
            .atPriority(10)
            .willReturn(aResponse().withStatus(404)));
    server.stubFor(
        get(urlPathMatching("/internal/users/email/.*"))
            .atPriority(10)
            .willReturn(aResponse().withStatus(404)));
  }

  /** The port the fake user-management REST server is listening on. */
  public int getPort() {
    return server.port();
  }

  public synchronized void setDown(boolean down) {
    if (down) {
      if (downStub == null) {
        downStub =
            server.stubFor(
                get(urlPathMatching("/internal/users/.*"))
                    .atPriority(1)
                    .willReturn(aResponse().withStatus(503)));
      }
    } else if (downStub != null) {
      server.removeStub(downStub);
      downStub = null;
    }
  }

  /**
   * Registers a minimal token-to-userId mapping (a no-op if the token is already registered). An
   * ACTIVE, INTERNAL user is built with default values for email/name/domain and the
   * "carbonioFeatureFilesEnabled" feature enabled.
   */
  public synchronized void registerToken(String token, String userId) {
    if (myselfStubsByToken.containsKey(token)) {
      return;
    }
    registerToken(token, userId, "active", false, true);
  }

  /**
   * Registers (or OVERWRITES — unlike {@link #registerToken(String, String)}, which is a no-op if
   * the token already exists) a token-to-userId mapping with explicit account status, type, and
   * feature-flag presence. Used by acceptance tests that need to drive the auth filter's
   * non-happy-path branches: inactive user (status != ACTIVE), guest user (type == GUEST), and
   * feature-disabled user (the "carbonioFeatureFilesEnabled" feature key absent from the features
   * list, which {@code UserMyself} maps to "FALSE").
   *
   * @param status a raw UM status string (e.g. "active", "maintenance", "closed", "locked", ...),
   *     matched case-insensitively against {@link com.zextras.carbonio.files.dal.dao.UserStatus}
   *     by the production mapper ({@code UserRepositoryImpl#mapStatus}).
   * @param isGuest true for a GUEST user, false for INTERNAL.
   * @param filesFeatureEnabled whether "carbonioFeatureFilesEnabled" is present in the features
   *     list; its absence is mapped to "FALSE" by {@code UserMyself}.
   */
  public synchronized void registerToken(
      String token, String userId, String status, boolean isGuest, boolean filesFeatureEnabled) {
    String infoJson =
        userInfoJson(userId, DEFAULT_EMAIL, DEFAULT_FULL_NAME, DEFAULT_DOMAIN, status, isGuest);
    String myselfBody = myselfJson(infoJson, DEFAULT_LOCALE, filesFeatureEnabled);

    StubMapping oldMyself = myselfStubsByToken.remove(token);
    if (oldMyself != null) {
      server.removeStub(oldMyself);
    }
    StubMapping myselfStub =
        server.stubFor(
            get(urlPathEqualTo("/internal/users/myself"))
                .withHeader(ZM_AUTH_TOKEN_COOKIE, equalTo(token))
                .atPriority(5)
                .willReturn(
                    aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(myselfBody)));
    myselfStubsByToken.put(token, myselfStub);

    registerUserInfoStubs(userId, DEFAULT_EMAIL, infoJson);
  }

  /**
   * Removes a userId from the {@code getUserById}/{@code getUserByEmail} lookups so that
   * subsequent calls for this user fall through to the {@code NOT_FOUND} catch-all.
   */
  public synchronized void unregisterUserById(String userId) {
    StubMapping oldById = byIdStubs.remove(userId);
    if (oldById != null) {
      server.removeStub(oldById);
    }
    String email = userIdToEmail.remove(userId);
    if (email != null) {
      StubMapping oldByEmail = byEmailStubs.remove(email);
      if (oldByEmail != null) {
        server.removeStub(oldByEmail);
      }
    }
  }

  /**
   * Registers (or overwrites) a user profile for lookup by userId ({@code getUserById}) and by
   * email ({@code getUserByEmail}). Used by integration tests that need to look up users other
   * than the requester (e.g. transfer ownership target user). Always INTERNAL, matching the
   * legacy gRPC fake's hardcoded type for this method.
   */
  public synchronized void registerUserById(
      String userId, String email, String fullName, String domain, String status) {
    String infoJson = userInfoJson(userId, email, fullName, domain, status, false);
    registerUserInfoStubs(userId, email, infoJson);
  }

  /** Removes every registered fixture and re-installs the baseline catch-all stubs. */
  public synchronized void clearAll() {
    server.resetMappings();
    myselfStubsByToken.clear();
    byIdStubs.clear();
    byEmailStubs.clear();
    userIdToEmail.clear();
    downStub = null;
    setupCatchAll();
  }

  /** Registers/overwrites the by-id and by-email stubs for a user, given its pre-built {@code UserInfoDto} JSON. */
  private void registerUserInfoStubs(String userId, String email, String infoJson) {
    StubMapping oldById = byIdStubs.remove(userId);
    if (oldById != null) {
      server.removeStub(oldById);
    }
    StubMapping idStub =
        server.stubFor(
            get(urlPathEqualTo("/internal/users/id/" + urlEncode(userId)))
                .atPriority(5)
                .willReturn(
                    aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(infoJson)));
    byIdStubs.put(userId, idStub);

    String previousEmail = userIdToEmail.put(userId, email);
    if (previousEmail != null && !previousEmail.equals(email)) {
      StubMapping oldPreviousEmailStub = byEmailStubs.remove(previousEmail);
      if (oldPreviousEmailStub != null) {
        server.removeStub(oldPreviousEmailStub);
      }
    }
    StubMapping oldEmailStub = byEmailStubs.remove(email);
    if (oldEmailStub != null) {
      server.removeStub(oldEmailStub);
    }
    StubMapping emailStub =
        server.stubFor(
            get(urlPathEqualTo("/internal/users/email/" + urlEncode(email)))
                .atPriority(5)
                .willReturn(
                    aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(infoJson)));
    byEmailStubs.put(email, emailStub);
  }

  /** Builds a {@code UserInfoDto}-shaped JSON body. */
  private static String userInfoJson(
      String userId, String email, String fullName, String domain, String status, boolean isGuest) {
    return String.format(
        "{\"userId\":\"%s\",\"email\":\"%s\",\"fullName\":\"%s\",\"domain\":\"%s\",\"status\":\"%s\",\"type\":\"%s\"}",
        jsonEscape(userId),
        jsonEscape(email),
        jsonEscape(fullName),
        jsonEscape(domain),
        jsonEscape(status),
        isGuest ? "GUEST" : "INTERNAL");
  }

  /** Builds a {@code MyselfDto}-shaped JSON body wrapping the given {@code UserInfoDto} JSON. */
  private static String myselfJson(String infoJson, String locale, boolean filesFeatureEnabled) {
    String featuresJson = filesFeatureEnabled ? "[\"carbonioFeatureFilesEnabled\"]" : "[]";
    return String.format(
        "{\"info\":%s,\"locale\":\"%s\",\"features\":%s}", infoJson, jsonEscape(locale), featuresJson);
  }

  private static String jsonEscape(String s) {
    return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
  }

  /** Mirrors {@code ApiClient#urlEncode}, so path stubs match exactly what the REST SDK client requests. */
  private static String urlEncode(String s) {
    return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
  }
}
