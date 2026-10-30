// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.zextras.carbonio.files.dal.dao.UserId;
import com.zextras.carbonio.files.dal.dao.UserInfo;
import com.zextras.carbonio.files.dal.dao.UserMyself;
import com.zextras.carbonio.files.dal.repositories.interfaces.UserRepository;
import com.zextras.carbonio.quarkus.extensions.confighierarchical.ConfigScope;
import com.zextras.carbonio.quarkus.extensions.confighierarchical.rest.ConfigAdmin;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class FilesConfigIdentityResolverTest {

  private static final String TOKEN = "token";
  private static final String ACCOUNT_ID = "account-1";

  private final UserRepository userRepository = mock(UserRepository.class);
  private final FilesConfigIdentityResolver resolver =
      new FilesConfigIdentityResolver(userRepository);

  private static UserMyself myself(boolean globalAdmin, String cosId) {
    UserMyself user = new UserMyself();
    user.setId(new UserId(ACCOUNT_ID));
    user.setGlobalAdmin(globalAdmin);
    user.setCosId(cosId);
    return user;
  }

  private static UserInfo info(String cosId) {
    UserInfo user = new UserInfo();
    user.setId(new UserId(ACCOUNT_ID));
    user.setCosId(cosId);
    return user;
  }

  @Test
  void givenAGlobalAdminTokenResolveAdminShouldReturnAnAdmin() {
    when(userRepository.getUserMyselfByToken(TOKEN)).thenReturn(Optional.of(myself(true, "cos-1")));

    assertThat(resolver.resolveAdmin(TOKEN)).contains(new ConfigAdmin(ACCOUNT_ID, true));
  }

  @Test
  void givenANonAdminTokenResolveAdminShouldReturnANonGlobalAdmin() {
    when(userRepository.getUserMyselfByToken(TOKEN))
        .thenReturn(Optional.of(myself(false, "cos-1")));

    assertThat(resolver.resolveAdmin(TOKEN)).contains(new ConfigAdmin(ACCOUNT_ID, false));
  }

  @Test
  void givenAnUnknownTokenResolveAdminShouldReturnEmpty() {
    when(userRepository.getUserMyselfByToken(TOKEN)).thenReturn(Optional.empty());

    assertThat(resolver.resolveAdmin(TOKEN)).isEmpty();
  }

  @Test
  void givenAUserWithACosResolveCallerShouldReturnTheAccountScope() {
    when(userRepository.getUserMyselfByToken(TOKEN))
        .thenReturn(Optional.of(myself(false, "cos-1")));

    assertThat(resolver.resolveCaller(TOKEN)).contains(ConfigScope.account(ACCOUNT_ID, "cos-1"));
  }

  @Test
  void givenAUserWithoutCosResolveCallerShouldReturnAScopeWithNullCos() {
    when(userRepository.getUserMyselfByToken(TOKEN)).thenReturn(Optional.of(myself(false, null)));

    Optional<ConfigScope> scope = resolver.resolveCaller(TOKEN);

    assertThat(scope).contains(ConfigScope.account(ACCOUNT_ID, null));
    assertThat(scope.get().cosId()).isNull();
  }

  @Test
  void givenAnUnknownTokenResolveCallerShouldReturnEmpty() {
    when(userRepository.getUserMyselfByToken(TOKEN)).thenReturn(Optional.empty());

    assertThat(resolver.resolveCaller(TOKEN)).isEmpty();
  }

  @Test
  void givenAnExistingAccountScopeOfAccountShouldUseItsCos() {
    when(userRepository.getUserById(null, ACCOUNT_ID)).thenReturn(Optional.of(info("cos-2")));

    assertThat(resolver.scopeOfAccount(ACCOUNT_ID))
        .contains(ConfigScope.account(ACCOUNT_ID, "cos-2"));
  }

  @Test
  void givenAMissingAccountScopeOfAccountShouldReturnEmpty() {
    when(userRepository.getUserById(null, ACCOUNT_ID)).thenReturn(Optional.empty());

    assertThat(resolver.scopeOfAccount(ACCOUNT_ID)).isEmpty();
  }
}
