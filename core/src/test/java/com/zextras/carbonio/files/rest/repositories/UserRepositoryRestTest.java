// SPDX-FileCopyrightText: 2024 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.rest.repositories;

import com.zextras.carbonio.files.cache.CacheHandler;
import com.zextras.carbonio.files.dal.repositories.impl.ebean.UserRepositoryRest;
import com.zextras.carbonio.files.utilities.MockFilesConfig;
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
    private UserManagementClient userManagementClientMock;
    private CacheHandler cacheHandlerMock;
    private MockFilesConfig mockFilesConfig;

    @BeforeEach
    void setup() {
        mockFilesConfig = new MockFilesConfig();
        cacheHandlerMock = Mockito.mock(CacheHandler.class);
        userManagementClientMock = Mockito.mock(UserManagementClient.class);

        userRepositoryRest = new UserRepositoryRest(mockFilesConfig, cacheHandlerMock, userManagementClientMock);
    }

    @Test
    void givenUserManagementsUsermyselfGetUserMyselfByCookieNotCachedShouldContainUserMyself() {
        // Given
        UserMyself userMyself = new UserMyself();
        UserId userId = new UserId();
        userMyself.setId(userId);

        Try<UserMyself> userMyselfTry = Try.of(() -> userMyself);

        Mockito.when(userManagementClientMock.getUserMyself("cookie", true)).thenReturn(userMyselfTry);

        // When
        Optional<com.zextras.carbonio.files.dal.dao.UserMyself> returnedUserMyselfOpt =
            userRepositoryRest.getUserMyselfByCookieNotCached("cookie");

        // Then
        Assertions.assertThat(returnedUserMyselfOpt.isPresent()).isTrue();

        Mockito.verify(userManagementClientMock).getUserMyself("cookie", true);
    }
}
