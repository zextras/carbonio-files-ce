// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.utilities;

import static com.zextras.carbonio.files.config.HierarchicalConfigKeys.HierarchicalConfig.SHARES_ENABLED;

import com.zextras.carbonio.quarkus.extensions.confighierarchical.ConfigResolver;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Optional;

@ApplicationScoped
public class SharesEnabledGuard {

  private final ConfigResolver configResolver;

  @Inject
  public SharesEnabledGuard(ConfigResolver configResolver) {
    this.configResolver = configResolver;
  }

  public boolean isEnabledFor(String requesterId) {
    return !"false"
        .equalsIgnoreCase(
            configResolver
                .get(Optional.ofNullable(requesterId), Optional.empty(), SHARES_ENABLED)
                .orElse("true"));
  }
}
