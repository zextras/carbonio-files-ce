// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.dal.repositories.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.zextras.carbonio.files.dal.dao.UserInfo;
import com.zextras.carbonio.files.dal.dao.UserMyself;
import com.zextras.carbonio.files.dal.dao.UserStatus;
import com.zextras.carbonio.files.dal.dao.UserType;
import com.zextras.carbonio.user_management.sdk.rest.ApiException;
import com.zextras.carbonio.user_management.sdk.rest.api.UserResourceApi;
import com.zextras.carbonio.user_management.sdk.rest.model.MyselfDto;
import com.zextras.carbonio.user_management.sdk.rest.model.UserInfoDto;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * P6e: validates {@link UserRepositoryImpl} response-to-DTO mapping and the empty-Optional-on-error
 * contract of {@link com.zextras.carbonio.files.dal.repositories.interfaces.UserRepository} against
 * the carbonio-user-management-rest-sdk client.
 *
 * <p>No CDI container / HTTP server is started: the {@link UserResourceApi} is a plain Mockito
 * mock passed directly to the constructor; it does not require any Testcontainers/WireMock stack.
 * Renamed from {@code UserRepositoryImplIT} to {@code *Test} (Phase 7b convention alignment) so
 * Surefire runs it as a unit test instead of Failsafe running it alongside the real integration
 * tests it never needed to be grouped with.
 */
class UserRepositoryImplTest {

  private UserResourceApi userResourceApiMock;
  private UserRepositoryImpl userRepository;

  @BeforeEach
  void setUp() {
    userResourceApiMock = mock(UserResourceApi.class);
    userRepository = new UserRepositoryImpl(userResourceApiMock);
  }

  @Test
  void getUserMyselfByCookieShouldExtractTokenAndMapResponse() throws Exception {
    UserInfoDto info =
        new UserInfoDto()
            .userId("user-1")
            .email("user1@example.com")
            .fullName("User One")
            .domain("example.com")
            .status("active")
            .type("INTERNAL");
    MyselfDto myself =
        new MyselfDto()
            .info(info)
            .locale("en_US")
            .features(List.of("carbonioFeatureFilesEnabled"));

    when(userResourceApiMock.internalUsersMyselfGet(isNull(), eq("abc123"))).thenReturn(myself);

    Optional<UserMyself> result =
        userRepository.getUserMyselfByCookie("ZM_AUTH_TOKEN=abc123; other=xyz");

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
  void getUserMyselfByCookieShouldUseRawCookiesAsTokenWhenPrefixMissing() throws Exception {
    UserInfoDto info = new UserInfoDto().userId("user-2").status("active").type("GUEST");
    MyselfDto myself = new MyselfDto().info(info);

    when(userResourceApiMock.internalUsersMyselfGet(isNull(), eq("raw-token-value")))
        .thenReturn(myself);

    Optional<UserMyself> result =
        userRepository.getUserMyselfByCookie("raw-token-value");

    assertThat(result).isPresent();
    assertThat(result.get().getType()).isEqualTo(UserType.GUEST);
    // No locale set on the DTO -> falls back to English.
    assertThat(result.get().getLocale()).isEqualTo(java.util.Locale.ENGLISH);
  }

  @Test
  void getUserMyselfByCookieShouldReturnEmptyOnUnauthenticated() throws Exception {
    when(userResourceApiMock.internalUsersMyselfGet(any(), any()))
        .thenThrow(new ApiException(401, "Unauthorized"));

    Optional<UserMyself> result =
        userRepository.getUserMyselfByCookie("ZM_AUTH_TOKEN=invalid");

    assertThat(result).isEmpty();
  }

  @Test
  void getUserByIdShouldMapResponse() throws Exception {
    UserInfoDto info =
        new UserInfoDto()
            .userId("user-3")
            .email("user3@example.com")
            .fullName("User Three")
            .domain("example.com")
            .status("locked")
            .type("INTERNAL");

    when(userResourceApiMock.internalUsersIdUserIdGet(eq("user-3"))).thenReturn(info);

    Optional<UserInfo> result = userRepository.getUserById("any-cookie", "user-3");

    assertThat(result).isPresent();
    assertThat(result.get().getId().getUserId()).isEqualTo("user-3");
    assertThat(result.get().getStatus()).isEqualTo(UserStatus.LOCKED);
  }

  @Test
  void getUserByIdShouldFallBackToClosedOnUnknownStatus() throws Exception {
    UserInfoDto info = new UserInfoDto().userId("user-4").status("not-a-real-status");

    when(userResourceApiMock.internalUsersIdUserIdGet(any())).thenReturn(info);

    Optional<UserInfo> result = userRepository.getUserById("any-cookie", "user-4");

    assertThat(result).isPresent();
    assertThat(result.get().getStatus()).isEqualTo(UserStatus.CLOSED);
  }

  @Test
  void getUserByIdShouldReturnEmptyOnNotFound() throws Exception {
    when(userResourceApiMock.internalUsersIdUserIdGet(any()))
        .thenThrow(new ApiException(404, "Not Found"));

    Optional<UserInfo> result = userRepository.getUserById("any-cookie", "missing-user");

    assertThat(result).isEmpty();
  }

  @Test
  void getUserByEmailShouldMapResponse() throws Exception {
    UserInfoDto info =
        new UserInfoDto().userId("user-5").email("user5@example.com").type("GUEST").status("active");

    when(userResourceApiMock.internalUsersEmailEmailGet(eq("user5@example.com"))).thenReturn(info);

    Optional<UserInfo> result = userRepository.getUserByEmail("any-cookie", "user5@example.com");

    assertThat(result).isPresent();
    assertThat(result.get().getEmail()).isEqualTo("user5@example.com");
    assertThat(result.get().getType()).isEqualTo(UserType.GUEST);
  }

  @Test
  void getUserByEmailShouldReturnEmptyOnError() throws Exception {
    when(userResourceApiMock.internalUsersEmailEmailGet(any()))
        .thenThrow(new ApiException(503, "Unavailable"));

    Optional<UserInfo> result = userRepository.getUserByEmail("any-cookie", "missing@example.com");

    assertThat(result).isEmpty();
  }

  /**
   * mapType MUST fail closed: a null, unrecognized, or otherwise unresolvable type must map to
   * {@link UserType#GUEST} (the access-denying value), never to {@link UserType#INTERNAL}. See
   * carbonio-files-ce#301 / CO-3482 and the legacy {@code UserRepositoryRest#mapType} it was
   * ported from.
   */
  @Test
  void getUserByIdShouldFailClosedToGuestOnNullType() throws Exception {
    UserInfoDto info = new UserInfoDto().userId("user-6").status("active").type(null);

    when(userResourceApiMock.internalUsersIdUserIdGet(any())).thenReturn(info);

    Optional<UserInfo> result = userRepository.getUserById("any-cookie", "user-6");

    assertThat(result).isPresent();
    assertThat(result.get().getType()).isEqualTo(UserType.GUEST);
  }

  @Test
  void getUserByIdShouldFailClosedToGuestOnUnknownType() throws Exception {
    UserInfoDto info = new UserInfoDto().userId("user-7").status("active").type("not-a-real-type");

    when(userResourceApiMock.internalUsersIdUserIdGet(any())).thenReturn(info);

    Optional<UserInfo> result = userRepository.getUserById("any-cookie", "user-7");

    assertThat(result).isPresent();
    assertThat(result.get().getType()).isEqualTo(UserType.GUEST);
  }

  @Test
  void getUserByIdShouldMapLowercaseGuestType() throws Exception {
    UserInfoDto info = new UserInfoDto().userId("user-8").status("active").type("guest");

    when(userResourceApiMock.internalUsersIdUserIdGet(any())).thenReturn(info);

    Optional<UserInfo> result = userRepository.getUserById("any-cookie", "user-8");

    assertThat(result).isPresent();
    assertThat(result.get().getType()).isEqualTo(UserType.GUEST);
  }

  @Test
  void getUserByIdShouldMapUppercaseInternalType() throws Exception {
    UserInfoDto info = new UserInfoDto().userId("user-9").status("active").type("INTERNAL");

    when(userResourceApiMock.internalUsersIdUserIdGet(any())).thenReturn(info);

    Optional<UserInfo> result = userRepository.getUserById("any-cookie", "user-9");

    assertThat(result).isPresent();
    assertThat(result.get().getType()).isEqualTo(UserType.INTERNAL);
  }

  /**
   * The generated client returns null (rather than throwing) for a 2xx response with a blank
   * body. getUserMyselfByCookie must not NPE in that case, and must instead treat the
   * user as unresolvable (empty Optional), same as a missing nested {@code info}.
   */
  @Test
  void getUserMyselfByCookieShouldReturnEmptyOnBlankBodyResponse() throws Exception {
    when(userResourceApiMock.internalUsersMyselfGet(any(), any())).thenReturn(null);

    Optional<UserMyself> result =
        userRepository.getUserMyselfByCookie("ZM_AUTH_TOKEN=abc123");

    assertThat(result).isEmpty();
  }

  @Test
  void getUserMyselfByCookieShouldReturnEmptyOnMissingNestedInfo() throws Exception {
    MyselfDto myself = new MyselfDto().info(null).locale("en_US");

    when(userResourceApiMock.internalUsersMyselfGet(any(), any())).thenReturn(myself);

    Optional<UserMyself> result =
        userRepository.getUserMyselfByCookie("ZM_AUTH_TOKEN=abc123");

    assertThat(result).isEmpty();
  }
}
