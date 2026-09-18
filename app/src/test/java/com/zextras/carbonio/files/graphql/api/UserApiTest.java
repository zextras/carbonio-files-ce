// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.graphql.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.zextras.carbonio.files.dal.dao.UserId;
import com.zextras.carbonio.files.dal.dao.UserInfo;
import com.zextras.carbonio.files.dal.dao.UserMyself;
import com.zextras.carbonio.files.dal.repositories.interfaces.UserRepository;
import com.zextras.carbonio.files.graphql.auth.AuthenticatedUserProducer;
import com.zextras.carbonio.files.graphql.errors.ErrorCodes;
import com.zextras.carbonio.files.graphql.errors.FilesGraphQLException;
import com.zextras.carbonio.files.graphql.model.Account;
import com.zextras.carbonio.files.graphql.model.UserModel;
import com.zextras.carbonio.files.graphql.validation.GraphQLInputValidator;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class UserApiTest {

  private static final String COOKIES = "ZX_AUTH_TOKEN=test-token";
  private static final String REQUESTER_ID = "requester-id";
  private static final String USER_ID = "user-id-1";
  private static final String EMAIL = "test@example.com";

  private UserRepository userRepository;
  private AuthenticatedUserProducer producer;
  private UserApi userApi;

  @BeforeEach
  void setUp() {
    userRepository = mock(UserRepository.class);
    producer = mock(AuthenticatedUserProducer.class);
    when(producer.cookies()).thenReturn(COOKIES);

    UserMyself requester = mock(UserMyself.class);
    when(requester.getId()).thenReturn(new UserId(REQUESTER_ID));

    userApi = new UserApi();
    userApi.userRepository = userRepository;
    userApi.producer = producer;
    userApi.validator = new GraphQLInputValidator();
    userApi.requester = requester;
  }

  private UserInfo makeUserInfo(String id, String email, String fullName) {
    UserInfo info = new UserInfo();
    info.setId(new UserId(id));
    info.setEmail(email);
    info.setFullName(fullName);
    return info;
  }

  // ─── getUserById ──────────────────────────────────────────────────────────

  @Test
  void getUserById_happy() throws FilesGraphQLException {
    UserInfo info = makeUserInfo(USER_ID, EMAIL, "Test User");
    when(userRepository.getUserById(COOKIES, USER_ID)).thenReturn(Optional.of(info));

    UserModel result = userApi.getUserById(USER_ID);

    assertThat(result.getId()).isEqualTo(USER_ID);
    assertThat(result.getEmail()).isEqualTo(EMAIL);
    assertThat(result.getFullName()).isEqualTo("Test User");
  }

  @Test
  void getUserById_notFound_throwsAccountNotFound() {
    when(userRepository.getUserById(COOKIES, USER_ID)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> userApi.getUserById(USER_ID))
        .isInstanceOf(FilesGraphQLException.class)
        .satisfies(
            e ->
                assertThat(((FilesGraphQLException) e).getErrorCode())
                    .isEqualTo(ErrorCodes.ACCOUNT_NOT_FOUND));
  }

  // ─── getAccountByEmail ────────────────────────────────────────────────────

  @Test
  void getAccountByEmail_happy() throws FilesGraphQLException {
    UserInfo info = makeUserInfo(USER_ID, EMAIL, "Test User");
    when(userRepository.getUserByEmail(COOKIES, EMAIL)).thenReturn(Optional.of(info));

    Account result = userApi.getAccountByEmail(EMAIL);

    assertThat(result).isInstanceOf(UserModel.class);
    assertThat(((UserModel) result).getEmail()).isEqualTo(EMAIL);
  }

  @Test
  void getAccountByEmail_notFound_throwsAccountNotFound() {
    when(userRepository.getUserByEmail(COOKIES, EMAIL)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> userApi.getAccountByEmail(EMAIL))
        .isInstanceOf(FilesGraphQLException.class)
        .satisfies(
            e ->
                assertThat(((FilesGraphQLException) e).getErrorCode())
                    .isEqualTo(ErrorCodes.ACCOUNT_NOT_FOUND));
  }

  @Test
  void getAccountByEmail_invalidEmail_throwsValidationError() {
    assertThatThrownBy(() -> userApi.getAccountByEmail("not-an-email"))
        .isInstanceOf(FilesGraphQLException.class);
  }

  // ─── getAccountsByEmail ───────────────────────────────────────────────────

  @Test
  void getAccountsByEmail_happy_returnsAllFound() throws FilesGraphQLException {
    String email2 = "other@example.com";
    UserInfo info1 = makeUserInfo(USER_ID, EMAIL, "User One");
    UserInfo info2 = makeUserInfo("user-id-2", email2, "User Two");
    when(userRepository.getUserByEmail(COOKIES, EMAIL)).thenReturn(Optional.of(info1));
    when(userRepository.getUserByEmail(COOKIES, email2)).thenReturn(Optional.of(info2));

    List<Account> result = userApi.getAccountsByEmail(List.of(EMAIL, email2));

    assertThat(result).hasSize(2);
    assertThat(result.get(0)).isInstanceOf(UserModel.class);
    assertThat(((UserModel) result.get(0)).getEmail()).isEqualTo(EMAIL);
    assertThat(result.get(1)).isInstanceOf(UserModel.class);
    assertThat(((UserModel) result.get(1)).getEmail()).isEqualTo(email2);
  }

  @Test
  void getAccountsByEmail_someNotFound_returnsNullSlots() throws FilesGraphQLException {
    String email2 = "missing@example.com";
    UserInfo info1 = makeUserInfo(USER_ID, EMAIL, "User One");
    when(userRepository.getUserByEmail(COOKIES, EMAIL)).thenReturn(Optional.of(info1));
    when(userRepository.getUserByEmail(COOKIES, email2)).thenReturn(Optional.empty());

    List<Account> result = userApi.getAccountsByEmail(List.of(EMAIL, email2));

    assertThat(result).hasSize(2);
    assertThat(result.get(0)).isInstanceOf(UserModel.class);
    assertThat(result.get(1)).isNull();
  }

  @Test
  void getAccountsByEmail_invalidEmail_throwsValidationError() {
    assertThatThrownBy(() -> userApi.getAccountsByEmail(List.of("bad-email")))
        .isInstanceOf(FilesGraphQLException.class);
  }
}
