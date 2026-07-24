// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.utilities;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.mockserver.client.MockServerClient;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;

/**
 * Fake carbonio-user-management REST service, backed by MockServer, stubbing the
 * {@code /internal/users/*} endpoints ({@code myself}, {@code id/{userId}},
 * {@code email/{email}}) that {@link
 * com.zextras.carbonio.files.dal.repositories.impl.ebean.UserRepositoryRest} calls via the
 * generated {@code UserResourceApi}.
 *
 * <p>Replaces the old in-process gRPC fake ({@code UserManagementServiceImplBase}) now that
 * files-ce talks REST to user-management. Supports {@code getUserMyself} (token lookup via the
 * {@code ZM_AUTH_TOKEN} header), {@code getUserById} (userId lookup), and {@code getUserByEmail}
 * (email lookup).
 */
public class MockUserManagementService {

  private static final String ZM_AUTH_TOKEN_HEADER = "ZM_AUTH_TOKEN";
  private static final String MYSELF_PATH = "/internal/users/myself";
  private static final String ID_PATH_PREFIX = "/internal/users/id/";
  private static final String EMAIL_PATH_PREFIX = "/internal/users/email/";

  private final MockServerClient mockServerClient;
  // Tracks the email currently stubbed for each userId, so unregisterUserById/re-registration
  // can clear the matching /internal/users/email/{email} stub as well (mirrors the old fake,
  // whose getUserById and getUserByEmail both read from the same underlying map).
  private final Map<String, String> userIdToEmail = new ConcurrentHashMap<>();

  public MockUserManagementService(MockServerClient mockServerClient) {
    this.mockServerClient = mockServerClient;
  }

  /**
   * Registers a minimal token-to-userId mapping. A full "myself" response is built with default
   * values for email, name, domain, status, locale, and the "carbonioFeatureFilesEnabled" feature
   * enabled. Also registers the same user for {@code getUserById}/{@code getUserByEmail} lookups
   * (as the old fake did, since both read from the same registration).
   */
  public void registerToken(String token, String userId) {
    registerUserById(userId, "fake-email@example.com", "Fake User", "example.com", "active");

    HttpRequest myselfRequest = myselfRequest(token);
    mockServerClient.clear(myselfRequest);
    mockServerClient
        .when(myselfRequest)
        .respond(
            HttpResponse.response()
                .withStatusCode(200)
                .withHeader("Content-Type", "application/json")
                .withBody(myselfJson(userId)));
  }

  /**
   * Registers a user profile for lookup by userId via {@code getUserById} (and by email via
   * {@code getUserByEmail}). This is used by integration tests that need to look up users other
   * than the requester (e.g. transfer ownership target user).
   */
  public void registerUserById(
      String userId, String email, String fullName, String domain, String status) {
    String previousEmail = userIdToEmail.put(userId, email);
    if (previousEmail != null && !previousEmail.equals(email)) {
      mockServerClient.clear(emailRequest(previousEmail));
    }

    String infoJson = userInfoJson(userId, email, fullName, domain, status);

    HttpRequest idRequest = idRequest(userId);
    mockServerClient.clear(idRequest);
    mockServerClient
        .when(idRequest)
        .respond(
            HttpResponse.response()
                .withStatusCode(200)
                .withHeader("Content-Type", "application/json")
                .withBody(infoJson));

    HttpRequest emailRequest = emailRequest(email);
    mockServerClient.clear(emailRequest);
    mockServerClient
        .when(emailRequest)
        .respond(
            HttpResponse.response()
                .withStatusCode(200)
                .withHeader("Content-Type", "application/json")
                .withBody(infoJson));
  }

  /**
   * Removes a userId from the {@code getUserById}/{@code getUserByEmail} lookup so that
   * subsequent calls for this user will return NOT_FOUND (404). Mirrors the old fake: the
   * token-based "myself" registration (if any) is left untouched.
   */
  public void unregisterUserById(String userId) {
    mockServerClient.clear(idRequest(userId));
    String email = userIdToEmail.remove(userId);
    if (email != null) {
      mockServerClient.clear(emailRequest(email));
    }
  }

  public void clearAll() {
    mockServerClient.reset();
    userIdToEmail.clear();
  }

  private static HttpRequest myselfRequest(String token) {
    return HttpRequest.request()
        .withMethod("GET")
        .withPath(MYSELF_PATH)
        .withHeader(ZM_AUTH_TOKEN_HEADER, token);
  }

  private static HttpRequest idRequest(String userId) {
    return HttpRequest.request().withMethod("GET").withPath(ID_PATH_PREFIX + urlEncode(userId));
  }

  private static HttpRequest emailRequest(String email) {
    return HttpRequest.request().withMethod("GET").withPath(EMAIL_PATH_PREFIX + urlEncode(email));
  }

  // Matches com.zextras.carbonio.user_management.sdk.rest.ApiClient#urlEncode: the generated
  // client percent-encodes path parameters, so the stub's path must be encoded the same way
  // (MockServer matches the raw/encoded request path, not the decoded one).
  private static String urlEncode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
  }

  private static String userInfoJson(
      String userId, String email, String fullName, String domain, String status) {
    return String.format(
        "{\"userId\":\"%s\",\"email\":\"%s\",\"fullName\":\"%s\",\"domain\":\"%s\","
            + "\"status\":\"%s\",\"type\":\"INTERNAL\"}",
        userId, email, fullName, domain, status);
  }

  private static String myselfJson(String userId) {
    return "{\"info\":"
        + userInfoJson(userId, "fake-email@example.com", "Fake User", "example.com", "active")
        + ",\"locale\":\"en\",\"features\":[\"carbonioFeatureFilesEnabled\"],\"capabilities\":{}}";
  }
}
