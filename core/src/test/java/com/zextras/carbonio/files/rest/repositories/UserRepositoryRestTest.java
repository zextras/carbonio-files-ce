// SPDX-FileCopyrightText: 2024 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.rest.repositories;

import com.zextras.carbonio.files.dal.dao.UserInfo;
import com.zextras.carbonio.files.dal.dao.UserMyself;
import com.zextras.carbonio.files.dal.dao.UserType;
import com.zextras.carbonio.files.dal.repositories.impl.ebean.UserRepositoryRest;
import com.zextras.carbonio.user_management.sdk.rest.ApiClient;
import com.zextras.carbonio.user_management.sdk.rest.api.UserResourceApi;
import java.net.http.HttpClient;
import java.util.Optional;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockserver.integration.ClientAndServer;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;

class UserRepositoryRestTest {

  private ClientAndServer mockServer;
  private UserRepositoryRest userRepositoryRest;

  @BeforeEach
  void setup() {
    // Start a MockServer fake of carbonio-user-management on an ephemeral port and stub its
    // /internal/users/* REST endpoints (replaces the old in-process gRPC fake).
    mockServer = ClientAndServer.startClientAndServer();
    int port = mockServer.getLocalPort();

    HttpClient.Builder httpClientBuilder =
        HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1);
    ApiClient apiClient =
        new ApiClient(
            httpClientBuilder, ApiClient.createDefaultObjectMapper(), "http://localhost:" + port);
    UserResourceApi userResourceApi = new UserResourceApi(apiClient);

    userRepositoryRest = new UserRepositoryRest(userResourceApi);

    mockServer
        .when(
            HttpRequest.request()
                .withMethod("GET")
                .withPath("/internal/users/myself")
                .withHeader("ZM_AUTH_TOKEN", "valid-token"))
        .respond(
            HttpResponse.response()
                .withStatusCode(200)
                .withHeader("Content-Type", "application/json")
                .withBody(
                    "{\"info\":{\"userId\":\"fake-user-id\",\"email\":\"fake@example.com\","
                        + "\"fullName\":\"Fake User\",\"domain\":\"example.com\","
                        + "\"status\":\"active\",\"type\":\"INTERNAL\"},\"locale\":\"en\","
                        + "\"features\":[\"carbonioFeatureFilesEnabled\"],\"capabilities\":{}}"));

    mockServer
        .when(HttpRequest.request().withMethod("GET").withPath("/internal/users/id/fake-user-id"))
        .respond(
            HttpResponse.response()
                .withStatusCode(200)
                .withHeader("Content-Type", "application/json")
                .withBody(
                    "{\"userId\":\"fake-user-id\",\"email\":\"fake@example.com\","
                        + "\"fullName\":\"Fake User\",\"domain\":\"example.com\","
                        + "\"status\":\"active\",\"type\":\"INTERNAL\"}"));
  }

  @AfterEach
  void tearDown() {
    if (mockServer != null) {
      mockServer.stop();
    }
  }

  @Test
  void givenValidCookieGetUserMyselfByCookieNotCachedShouldContainUserMyself() {
    // When
    Optional<UserMyself> returnedUserMyselfOpt =
        userRepositoryRest.getUserMyselfByCookieNotCached("ZM_AUTH_TOKEN=valid-token");

    // Then
    Assertions.assertThat(returnedUserMyselfOpt).isPresent();
    UserMyself user = returnedUserMyselfOpt.get();
    Assertions.assertThat(user.getId().getUserId()).isEqualTo("fake-user-id");
    Assertions.assertThat(user.getEmail()).isEqualTo("fake@example.com");
    Assertions.assertThat(user.getFullName()).isEqualTo("Fake User");
    Assertions.assertThat(user.getDomain()).isEqualTo("example.com");
  }

  @Test
  void givenInvalidCookieGetUserMyselfByCookieNotCachedShouldReturnEmpty() {
    // When
    Optional<UserMyself> returnedUserMyselfOpt =
        userRepositoryRest.getUserMyselfByCookieNotCached("ZM_AUTH_TOKEN=invalid-token");

    // Then
    Assertions.assertThat(returnedUserMyselfOpt).isEmpty();
  }

  @Test
  void givenValidUserIdGetUserByIdShouldReturnUserInfo() {
    // When
    Optional<UserInfo> returnedUserInfoOpt =
        userRepositoryRest.getUserById("ZM_AUTH_TOKEN=valid-token", "fake-user-id");

    // Then
    Assertions.assertThat(returnedUserInfoOpt).isPresent();
    UserInfo userInfo = returnedUserInfoOpt.get();
    Assertions.assertThat(userInfo.getId().getUserId()).isEqualTo("fake-user-id");
    Assertions.assertThat(userInfo.getEmail()).isEqualTo("fake@example.com");
  }

  @Test
  void givenUnknownUserIdGetUserByIdShouldReturnEmpty() {
    // When
    Optional<UserInfo> returnedUserInfoOpt =
        userRepositoryRest.getUserById("ZM_AUTH_TOKEN=valid-token", "unknown-user-id");

    // Then
    Assertions.assertThat(returnedUserInfoOpt).isEmpty();
  }

  // ---------------------------------------------------------------------------------------
  // mapType() coverage: "GUEST" is the access-denying value (AuthenticationHandler blocks
  // guests), so an unresolvable/unknown type must fail CLOSED onto GUEST rather than
  // defaulting to INTERNAL. These stub a range of "type" values that a plain REST string can
  // now carry but the old protobuf UserTypeProto enum could never have produced.
  // ---------------------------------------------------------------------------------------

  @Test
  void givenUserTypeGuestGetUserByIdShouldMapToGuestType() {
    // Given
    mockServer
        .when(HttpRequest.request().withMethod("GET").withPath("/internal/users/id/guest-user-id"))
        .respond(
            HttpResponse.response()
                .withStatusCode(200)
                .withHeader("Content-Type", "application/json")
                .withBody(
                    "{\"userId\":\"guest-user-id\",\"email\":\"guest@example.com\","
                        + "\"fullName\":\"Guest User\",\"domain\":\"example.com\","
                        + "\"status\":\"active\",\"type\":\"GUEST\"}"));

    // When
    Optional<UserInfo> returnedUserInfoOpt =
        userRepositoryRest.getUserById("ZM_AUTH_TOKEN=valid-token", "guest-user-id");

    // Then
    Assertions.assertThat(returnedUserInfoOpt).isPresent();
    Assertions.assertThat(returnedUserInfoOpt.get().getType()).isEqualTo(UserType.GUEST);
  }

  @Test
  void givenUserTypeInternalGetUserByIdShouldMapToInternalType() {
    // When: the "fake-user-id" stub registered in setup() returns "type":"INTERNAL"
    Optional<UserInfo> returnedUserInfoOpt =
        userRepositoryRest.getUserById("ZM_AUTH_TOKEN=valid-token", "fake-user-id");

    // Then
    Assertions.assertThat(returnedUserInfoOpt).isPresent();
    Assertions.assertThat(returnedUserInfoOpt.get().getType()).isEqualTo(UserType.INTERNAL);
  }

  @Test
  void givenMixedCaseUserTypeGuestGetUserByIdShouldMapToGuestType() {
    // Given
    mockServer
        .when(
            HttpRequest.request()
                .withMethod("GET")
                .withPath("/internal/users/id/mixed-case-guest-id"))
        .respond(
            HttpResponse.response()
                .withStatusCode(200)
                .withHeader("Content-Type", "application/json")
                .withBody(
                    "{\"userId\":\"mixed-case-guest-id\",\"email\":\"guest2@example.com\","
                        + "\"fullName\":\"Guest User Two\",\"domain\":\"example.com\","
                        + "\"status\":\"active\",\"type\":\"GuEsT\"}"));

    // When
    Optional<UserInfo> returnedUserInfoOpt =
        userRepositoryRest.getUserById("ZM_AUTH_TOKEN=valid-token", "mixed-case-guest-id");

    // Then
    Assertions.assertThat(returnedUserInfoOpt).isPresent();
    Assertions.assertThat(returnedUserInfoOpt.get().getType()).isEqualTo(UserType.GUEST);
  }

  @Test
  void givenMixedCaseUserTypeInternalGetUserByIdShouldMapToInternalType() {
    // Given
    mockServer
        .when(
            HttpRequest.request()
                .withMethod("GET")
                .withPath("/internal/users/id/mixed-case-internal-id"))
        .respond(
            HttpResponse.response()
                .withStatusCode(200)
                .withHeader("Content-Type", "application/json")
                .withBody(
                    "{\"userId\":\"mixed-case-internal-id\",\"email\":\"internal2@example.com\","
                        + "\"fullName\":\"Internal User Two\",\"domain\":\"example.com\","
                        + "\"status\":\"active\",\"type\":\"InTeRnAl\"}"));

    // When
    Optional<UserInfo> returnedUserInfoOpt =
        userRepositoryRest.getUserById("ZM_AUTH_TOKEN=valid-token", "mixed-case-internal-id");

    // Then
    Assertions.assertThat(returnedUserInfoOpt).isPresent();
    Assertions.assertThat(returnedUserInfoOpt.get().getType()).isEqualTo(UserType.INTERNAL);
  }

  @Test
  void givenMissingUserTypeGetUserByIdShouldFailClosedToGuestType() {
    // Given: the "type" field is entirely absent from the response body -- e.g. a UM-side
    // regression -- unlike the old protobuf message where the field was always populated
    mockServer
        .when(
            HttpRequest.request().withMethod("GET").withPath("/internal/users/id/no-type-user-id"))
        .respond(
            HttpResponse.response()
                .withStatusCode(200)
                .withHeader("Content-Type", "application/json")
                .withBody(
                    "{\"userId\":\"no-type-user-id\",\"email\":\"no-type@example.com\","
                        + "\"fullName\":\"No Type User\",\"domain\":\"example.com\","
                        + "\"status\":\"active\"}"));

    // When
    Optional<UserInfo> returnedUserInfoOpt =
        userRepositoryRest.getUserById("ZM_AUTH_TOKEN=valid-token", "no-type-user-id");

    // Then: a missing type must NOT silently grant internal access
    Assertions.assertThat(returnedUserInfoOpt).isPresent();
    Assertions.assertThat(returnedUserInfoOpt.get().getType()).isEqualTo(UserType.GUEST);
  }

  @Test
  void givenUnknownUserTypeGetUserByIdShouldFailClosedToGuestType() {
    // Given: an unrecognized type value (e.g. a UM-side typo, or a future type this client
    // doesn't know about yet)
    mockServer
        .when(
            HttpRequest.request()
                .withMethod("GET")
                .withPath("/internal/users/id/unknown-type-user-id"))
        .respond(
            HttpResponse.response()
                .withStatusCode(200)
                .withHeader("Content-Type", "application/json")
                .withBody(
                    "{\"userId\":\"unknown-type-user-id\",\"email\":\"unknown@example.com\","
                        + "\"fullName\":\"Unknown Type User\",\"domain\":\"example.com\","
                        + "\"status\":\"active\",\"type\":\"SOMETHING_ELSE\"}"));

    // When
    Optional<UserInfo> returnedUserInfoOpt =
        userRepositoryRest.getUserById("ZM_AUTH_TOKEN=valid-token", "unknown-type-user-id");

    // Then: an unrecognized type must NOT silently grant internal access
    Assertions.assertThat(returnedUserInfoOpt).isPresent();
    Assertions.assertThat(returnedUserInfoOpt.get().getType()).isEqualTo(UserType.GUEST);
  }

  // ---------------------------------------------------------------------------------------
  // NPE-guard coverage: the generated client returns null (not an exception) for a 2xx
  // response with a blank body, and MyselfDto#getInfo()/#getFeatures() are @Nullable. All of
  // these must degrade to Optional.empty() (or an empty features list), never throw.
  // ---------------------------------------------------------------------------------------

  @Test
  void givenBlankResponseBodyGetUserMyselfByCookieNotCachedShouldReturnEmpty() {
    // Given
    mockServer
        .when(
            HttpRequest.request()
                .withMethod("GET")
                .withPath("/internal/users/myself")
                .withHeader("ZM_AUTH_TOKEN", "blank-body-token"))
        .respond(HttpResponse.response().withStatusCode(200).withBody(""));

    // When
    Optional<UserMyself> returnedUserMyselfOpt =
        userRepositoryRest.getUserMyselfByCookieNotCached("ZM_AUTH_TOKEN=blank-body-token");

    // Then
    Assertions.assertThat(returnedUserMyselfOpt).isEmpty();
  }

  @Test
  void givenMissingInfoInMyselfResponseGetUserMyselfByCookieNotCachedShouldReturnEmpty() {
    // Given: a 200 response whose body parses fine but carries no "info" object
    mockServer
        .when(
            HttpRequest.request()
                .withMethod("GET")
                .withPath("/internal/users/myself")
                .withHeader("ZM_AUTH_TOKEN", "no-info-token"))
        .respond(
            HttpResponse.response()
                .withStatusCode(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"locale\":\"en\",\"features\":[],\"capabilities\":{}}"));

    // When
    Optional<UserMyself> returnedUserMyselfOpt =
        userRepositoryRest.getUserMyselfByCookieNotCached("ZM_AUTH_TOKEN=no-info-token");

    // Then
    Assertions.assertThat(returnedUserMyselfOpt).isEmpty();
  }

  @Test
  void
      givenNullFeaturesInMyselfResponseGetUserMyselfByCookieNotCachedShouldDefaultToEmptyFeatures() {
    // Given: UM emits an explicit JSON null for "features" (Jackson leaves the field at its
    // default `new ArrayList<>()` only when the key is ABSENT, but sets it to null when the
    // key is present with a JSON null value)
    mockServer
        .when(
            HttpRequest.request()
                .withMethod("GET")
                .withPath("/internal/users/myself")
                .withHeader("ZM_AUTH_TOKEN", "null-features-token"))
        .respond(
            HttpResponse.response()
                .withStatusCode(200)
                .withHeader("Content-Type", "application/json")
                .withBody(
                    "{\"info\":{\"userId\":\"null-features-user-id\",\"email\":\"null-features@example.com\",\"fullName\":\"Null"
                        + " Features User\","
                        + "\"domain\":\"example.com\",\"status\":\"active\",\"type\":\"INTERNAL\"},"
                        + "\"locale\":\"en\",\"features\":null,\"capabilities\":{}}"));

    // When
    Optional<UserMyself> returnedUserMyselfOpt =
        userRepositoryRest.getUserMyselfByCookieNotCached("ZM_AUTH_TOKEN=null-features-token");

    // Then: no NPE, and the null is normalized to an empty list rather than propagated
    Assertions.assertThat(returnedUserMyselfOpt).isPresent();
    UserMyself user = returnedUserMyselfOpt.get();
    Assertions.assertThat(user.getFeatures()).isEmpty();
    Assertions.assertThat(user.getCarbonioAttributes()).isEmpty();
  }

  @Test
  void givenBlankResponseBodyGetUserByIdShouldReturnEmpty() {
    // Given
    mockServer
        .when(HttpRequest.request().withMethod("GET").withPath("/internal/users/id/blank-body-id"))
        .respond(HttpResponse.response().withStatusCode(200).withBody(""));

    // When
    Optional<UserInfo> returnedUserInfoOpt =
        userRepositoryRest.getUserById("ZM_AUTH_TOKEN=valid-token", "blank-body-id");

    // Then
    Assertions.assertThat(returnedUserInfoOpt).isEmpty();
  }

  @Test
  void givenBlankResponseBodyGetUserByEmailShouldReturnEmpty() {
    // Given
    mockServer
        .when(
            HttpRequest.request()
                .withMethod("GET")
                .withPath("/internal/users/email/blank-body-email"))
        .respond(HttpResponse.response().withStatusCode(200).withBody(""));

    // When
    Optional<UserInfo> returnedUserInfoOpt =
        userRepositoryRest.getUserByEmail("ZM_AUTH_TOKEN=valid-token", "blank-body-email");

    // Then
    Assertions.assertThat(returnedUserInfoOpt).isEmpty();
  }
}
