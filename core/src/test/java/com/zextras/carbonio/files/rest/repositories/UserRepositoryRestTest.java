// SPDX-FileCopyrightText: 2024 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.rest.repositories;

import com.zextras.carbonio.files.dal.dao.UserInfo;
import com.zextras.carbonio.files.dal.dao.UserMyself;
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
        ApiClient apiClient = new ApiClient(
            httpClientBuilder, ApiClient.createDefaultObjectMapper(), "http://localhost:" + port);
        UserResourceApi userResourceApi = new UserResourceApi(apiClient);

        userRepositoryRest = new UserRepositoryRest(userResourceApi);

        mockServer
            .when(
                HttpRequest.request()
                    .withMethod("GET")
                    .withPath("/internal/users/myself")
                    .withCookie("ZM_AUTH_TOKEN", "valid-token"))
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
            .when(
                HttpRequest.request()
                    .withMethod("GET")
                    .withPath("/internal/users/id/fake-user-id"))
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
}
