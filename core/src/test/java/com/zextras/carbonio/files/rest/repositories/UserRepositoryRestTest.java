// SPDX-FileCopyrightText: 2024 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.rest.repositories;

import com.zextras.carbonio.files.cache.CacheHandler;
import com.zextras.carbonio.files.config.FilesConfig;
import com.zextras.carbonio.files.dal.repositories.impl.ebean.UserRepositoryRest;
import com.zextras.carbonio.usermanagement.UserManagementClient;
import com.zextras.carbonio.usermanagement.entities.UserId;
import com.zextras.carbonio.usermanagement.entities.UserMyself;
import io.vavr.control.Try;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mockStatic;

class UserRepositoryRestTest {

  private UserRepositoryRest userRepositoryRest;

  @BeforeEach
  void setup() {
    userRepositoryRest = new UserRepositoryRest(new FilesConfig(), Mockito.mock(CacheHandler.class));
  }

  @Test
  void givenUserManagementsUsermyselfGetUserMyselfByCookieNotCachedShouldContainUserMyself() {
    // Given
    UserMyself userMyself = new UserMyself();
    UserId userId = new UserId();
    userMyself.setId(userId);

    Try<UserMyself> userMyselfTry = Try.of(() -> userMyself);

    try (MockedStatic<UserManagementClient> mockedStatic = mockStatic(UserManagementClient.class)) {
      UserManagementClient userManagementClientMock = Mockito.mock(UserManagementClient.class);

      mockedStatic.when(() -> UserManagementClient.atURL(anyString())).thenReturn(userManagementClientMock);
      Mockito.when(userManagementClientMock.getUserMyself("cookie")).thenReturn(userMyselfTry);

      // When
      Optional<com.zextras.carbonio.files.dal.dao.UserMyself> returnedUserMyselfOpt = userRepositoryRest.getUserMyselfByCookieNotCached("cookie");

      // Then
      Assertions.assertThat(returnedUserMyselfOpt.isPresent()).isTrue();
    }
  }
}
