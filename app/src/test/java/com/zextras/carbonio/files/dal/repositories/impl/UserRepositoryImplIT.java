// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.dal.repositories.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.zextras.carbonio.files.dal.dao.UserInfo;
import com.zextras.carbonio.files.dal.dao.UserMyself;
import com.zextras.carbonio.files.dal.dao.UserStatus;
import com.zextras.carbonio.files.dal.dao.UserType;
import com.zextras.carbonio.user_management.sdk.grpc.GetUserByEmailRequest;
import com.zextras.carbonio.user_management.sdk.grpc.GetUserByIdRequest;
import com.zextras.carbonio.user_management.sdk.grpc.GetUserMyselfRequest;
import com.zextras.carbonio.user_management.sdk.grpc.UserInfoProto;
import com.zextras.carbonio.user_management.sdk.grpc.UserInfoResponse;
import com.zextras.carbonio.user_management.sdk.grpc.UserManagementServiceGrpc.UserManagementServiceBlockingStub;
import com.zextras.carbonio.user_management.sdk.grpc.UserMyselfProto;
import com.zextras.carbonio.user_management.sdk.grpc.UserMyselfResponse;
import com.zextras.carbonio.user_management.sdk.grpc.UserTypeProto;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * P3a: validates {@link UserRepositoryImpl} response-to-DTO mapping and the empty-Optional-on-error
 * contract of {@link com.zextras.carbonio.files.dal.repositories.interfaces.UserRepository}.
 *
 * <p>No CDI container / gRPC server is started: the {@code @GrpcClient}-injected blocking stub is
 * a plain Mockito mock assigned directly to the package-private field, mirroring the pattern used
 * by carbonio-tasks-ce's {@code AuthenticationFilterTest} for the same SDK. Named {@code *IT} so it
 * runs under failsafe alongside the other P3a integration tests; it does not require any
 * Testcontainers/WireMock stack.
 */
class UserRepositoryImplIT {

  private UserManagementServiceBlockingStub stubMock;
  private UserRepositoryImpl userRepository;

  @BeforeEach
  void setUp() {
    stubMock = mock(UserManagementServiceBlockingStub.class);
    userRepository = new UserRepositoryImpl();
    userRepository.userManagementStub = stubMock;
  }

  @Test
  void getUserMyselfByCookieNotCachedShouldExtractTokenAndMapResponse() {
    UserInfoProto info =
        UserInfoProto.newBuilder()
            .setUserId("user-1")
            .setEmail("user1@example.com")
            .setFullName("User One")
            .setDomain("example.com")
            .setStatus("active")
            .setType(UserTypeProto.INTERNAL)
            .build();
    UserMyselfProto myself =
        UserMyselfProto.newBuilder()
            .setInfo(info)
            .setLocale("en_US")
            .addFeatures("carbonioFeatureFilesEnabled")
            .build();
    UserMyselfResponse response = UserMyselfResponse.newBuilder().setUser(myself).build();

    GetUserMyselfRequest expectedRequest =
        GetUserMyselfRequest.newBuilder().setToken("abc123").build();
    when(stubMock.getUserMyself(expectedRequest)).thenReturn(response);

    Optional<UserMyself> result =
        userRepository.getUserMyselfByCookieNotCached("ZM_AUTH_TOKEN=abc123; other=xyz");

    assertThat(result).isPresent();
    UserMyself userMyself = result.get();
    assertThat(userMyself.getId().getUserId()).isEqualTo("user-1");
    assertThat(userMyself.getEmail()).isEqualTo("user1@example.com");
    assertThat(userMyself.getFullName()).isEqualTo("User One");
    assertThat(userMyself.getDomain()).isEqualTo("example.com");
    assertThat(userMyself.getStatus()).isEqualTo(UserStatus.ACTIVE);
    assertThat(userMyself.getType()).isEqualTo(UserType.INTERNAL);
    assertThat(userMyself.getLocale().toLanguageTag()).isEqualTo("en-US");
    assertThat(userMyself.getFeatures()).containsExactly("carbonioFeatureFilesEnabled");
  }

  @Test
  void getUserMyselfByCookieNotCachedShouldUseRawCookiesAsTokenWhenPrefixMissing() {
    UserInfoProto info =
        UserInfoProto.newBuilder()
            .setUserId("user-2")
            .setStatus("active")
            .setType(UserTypeProto.GUEST)
            .build();
    UserMyselfProto myself = UserMyselfProto.newBuilder().setInfo(info).build();
    UserMyselfResponse response = UserMyselfResponse.newBuilder().setUser(myself).build();

    GetUserMyselfRequest expectedRequest =
        GetUserMyselfRequest.newBuilder().setToken("raw-token-value").build();
    when(stubMock.getUserMyself(expectedRequest)).thenReturn(response);

    Optional<UserMyself> result =
        userRepository.getUserMyselfByCookieNotCached("raw-token-value");

    assertThat(result).isPresent();
    assertThat(result.get().getType()).isEqualTo(UserType.GUEST);
    // No locale set on the proto -> falls back to English.
    assertThat(result.get().getLocale()).isEqualTo(java.util.Locale.ENGLISH);
  }

  @Test
  void getUserMyselfByCookieNotCachedShouldReturnEmptyOnUnauthenticated() {
    when(stubMock.getUserMyself(org.mockito.ArgumentMatchers.any()))
        .thenThrow(new StatusRuntimeException(Status.UNAUTHENTICATED));

    Optional<UserMyself> result =
        userRepository.getUserMyselfByCookieNotCached("ZM_AUTH_TOKEN=invalid");

    assertThat(result).isEmpty();
  }

  @Test
  void getUserByIdShouldMapResponse() {
    UserInfoProto info =
        UserInfoProto.newBuilder()
            .setUserId("user-3")
            .setEmail("user3@example.com")
            .setFullName("User Three")
            .setDomain("example.com")
            .setStatus("locked")
            .setType(UserTypeProto.INTERNAL)
            .build();
    UserInfoResponse response = UserInfoResponse.newBuilder().setUser(info).build();

    GetUserByIdRequest expectedRequest =
        GetUserByIdRequest.newBuilder().setUserId("user-3").build();
    when(stubMock.getUserById(expectedRequest)).thenReturn(response);

    Optional<UserInfo> result = userRepository.getUserById("any-cookie", "user-3");

    assertThat(result).isPresent();
    assertThat(result.get().getId().getUserId()).isEqualTo("user-3");
    assertThat(result.get().getStatus()).isEqualTo(UserStatus.LOCKED);
  }

  @Test
  void getUserByIdShouldFallBackToClosedOnUnknownStatus() {
    UserInfoProto info =
        UserInfoProto.newBuilder().setUserId("user-4").setStatus("not-a-real-status").build();
    UserInfoResponse response = UserInfoResponse.newBuilder().setUser(info).build();

    when(stubMock.getUserById(org.mockito.ArgumentMatchers.any())).thenReturn(response);

    Optional<UserInfo> result = userRepository.getUserById("any-cookie", "user-4");

    assertThat(result).isPresent();
    assertThat(result.get().getStatus()).isEqualTo(UserStatus.CLOSED);
  }

  @Test
  void getUserByIdShouldReturnEmptyOnNotFound() {
    when(stubMock.getUserById(org.mockito.ArgumentMatchers.any()))
        .thenThrow(new StatusRuntimeException(Status.NOT_FOUND));

    Optional<UserInfo> result = userRepository.getUserById("any-cookie", "missing-user");

    assertThat(result).isEmpty();
  }

  @Test
  void getUserByEmailShouldMapResponse() {
    UserInfoProto info =
        UserInfoProto.newBuilder()
            .setUserId("user-5")
            .setEmail("user5@example.com")
            .setType(UserTypeProto.GUEST)
            .setStatus("active")
            .build();
    UserInfoResponse response = UserInfoResponse.newBuilder().setUser(info).build();

    GetUserByEmailRequest expectedRequest =
        GetUserByEmailRequest.newBuilder().setUserEmail("user5@example.com").build();
    when(stubMock.getUserByEmail(expectedRequest)).thenReturn(response);

    Optional<UserInfo> result = userRepository.getUserByEmail("any-cookie", "user5@example.com");

    assertThat(result).isPresent();
    assertThat(result.get().getEmail()).isEqualTo("user5@example.com");
    assertThat(result.get().getType()).isEqualTo(UserType.GUEST);
  }

  @Test
  void getUserByEmailShouldReturnEmptyOnError() {
    when(stubMock.getUserByEmail(org.mockito.ArgumentMatchers.any()))
        .thenThrow(new StatusRuntimeException(Status.UNAVAILABLE));

    Optional<UserInfo> result = userRepository.getUserByEmail("any-cookie", "missing@example.com");

    assertThat(result).isEmpty();
  }
}
