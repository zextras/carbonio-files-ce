// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.config;

import com.zextras.carbonio.files.dal.repositories.interfaces.UserRepository;
import com.zextras.carbonio.quarkus.extensions.confighierarchical.ConfigScope;
import com.zextras.carbonio.quarkus.extensions.confighierarchical.rest.ConfigAdmin;
import com.zextras.carbonio.quarkus.extensions.confighierarchical.rest.HierarchicalConfigIdentityResolver;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Optional;

@ApplicationScoped
public class FilesConfigIdentityResolver implements HierarchicalConfigIdentityResolver {

  private final UserRepository userRepository;

  @Inject
  public FilesConfigIdentityResolver(UserRepository userRepository) {
    this.userRepository = userRepository;
  }

  // Trusts the account's admin flag only: /myself accepts user and admin tokens alike (CO-4496
  // skeleton).
  @Override
  public Optional<ConfigAdmin> resolveAdmin(String adminToken) {
    return userRepository
        .getUserMyselfByToken(adminToken)
        .map(user -> new ConfigAdmin(user.getId().getUserId(), user.isGlobalAdmin()));
  }

  @Override
  public Optional<ConfigScope> resolveCaller(String userToken) {
    return userRepository
        .getUserMyselfByToken(userToken)
        .map(u -> ConfigScope.account(u.getId().getUserId(), u.getCosId(), u.getDomainId()));
  }

  @Override
  public Optional<ConfigScope> scopeOfAccount(String accountId) {
    return userRepository
        .getUserById(null, accountId)
        .map(u -> ConfigScope.account(u.getId().getUserId(), u.getCosId(), u.getDomainId()));
  }
}
