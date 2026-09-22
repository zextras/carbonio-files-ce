// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.utilities;

import static com.zextras.carbonio.files.config.HierarchicalConfigKeys.HierarchicalConfig.SHARES_ENABLED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.zextras.carbonio.quarkus.extensions.confighierarchical.ConfigResolver;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SharesEnabledGuardTest {

  private static final String USER_ID = "test-user-id";

  private ConfigResolver configResolver;
  private SharesEnabledGuard guard;

  @BeforeEach
  void setUp() {
    configResolver = mock(ConfigResolver.class);
    guard = new SharesEnabledGuard(configResolver);
  }

  @Test
  void falseValueDisablesSharing() {
    when(configResolver.get(Optional.of(USER_ID), Optional.empty(), SHARES_ENABLED))
        .thenReturn(Optional.of("false"));
    assertThat(guard.isEnabledFor(USER_ID)).isFalse();
  }

  @Test
  void trueValueKeepsSharingEnabled() {
    when(configResolver.get(Optional.of(USER_ID), Optional.empty(), SHARES_ENABLED))
        .thenReturn(Optional.of("true"));
    assertThat(guard.isEnabledFor(USER_ID)).isTrue();
  }

  @Test
  void absentValueDefaultsToEnabled() {
    when(configResolver.get(Optional.of(USER_ID), Optional.empty(), SHARES_ENABLED))
        .thenReturn(Optional.empty());
    assertThat(guard.isEnabledFor(USER_ID)).isTrue();
  }

  @Test
  void falseIsCaseInsensitiveUpperCase() {
    when(configResolver.get(Optional.of(USER_ID), Optional.empty(), SHARES_ENABLED))
        .thenReturn(Optional.of("FALSE"));
    assertThat(guard.isEnabledFor(USER_ID)).isFalse();
  }

  @Test
  void falseIsCaseInsensitiveMixedCase() {
    when(configResolver.get(Optional.of(USER_ID), Optional.empty(), SHARES_ENABLED))
        .thenReturn(Optional.of("False"));
    assertThat(guard.isEnabledFor(USER_ID)).isFalse();
  }
}
