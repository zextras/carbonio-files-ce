// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.utilities;

import static com.zextras.carbonio.files.config.HierarchicalConfigKeys.HierarchicalConfig.SHARES_ENABLED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.zextras.carbonio.files.dal.dao.UserId;
import com.zextras.carbonio.files.dal.dao.UserMyself;
import com.zextras.carbonio.quarkus.extensions.confighierarchical.ConfigResolver;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SharesEnabledGuardTest {

  private static final String USER_ID = "test-user-id";
  private static final String COS_ID = "test-cos-id";

  private ConfigResolver configResolver;
  private SharesEnabledGuard guard;
  private UserMyself requester;

  @BeforeEach
  void setUp() {
    configResolver = mock(ConfigResolver.class);
    guard = new SharesEnabledGuard(configResolver);
    UserId id = mock(UserId.class);
    when(id.getUserId()).thenReturn(USER_ID);
    requester = mock(UserMyself.class);
    when(requester.getId()).thenReturn(id);
    when(requester.getCosId()).thenReturn(COS_ID);
  }

  @Test
  void falseValueDisablesSharing() {
    when(configResolver.get(Optional.of(USER_ID), Optional.of(COS_ID), SHARES_ENABLED))
        .thenReturn(Optional.of("false"));
    assertThat(guard.isEnabledFor(requester)).isFalse();
  }

  @Test
  void trueValueKeepsSharingEnabled() {
    when(configResolver.get(Optional.of(USER_ID), Optional.of(COS_ID), SHARES_ENABLED))
        .thenReturn(Optional.of("true"));
    assertThat(guard.isEnabledFor(requester)).isTrue();
  }

  @Test
  void absentValueDefaultsToEnabled() {
    when(configResolver.get(Optional.of(USER_ID), Optional.of(COS_ID), SHARES_ENABLED))
        .thenReturn(Optional.empty());
    assertThat(guard.isEnabledFor(requester)).isTrue();
  }

  @Test
  void falseIsCaseInsensitiveUpperCase() {
    when(configResolver.get(Optional.of(USER_ID), Optional.of(COS_ID), SHARES_ENABLED))
        .thenReturn(Optional.of("FALSE"));
    assertThat(guard.isEnabledFor(requester)).isFalse();
  }

  @Test
  void falseIsCaseInsensitiveMixedCase() {
    when(configResolver.get(Optional.of(USER_ID), Optional.of(COS_ID), SHARES_ENABLED))
        .thenReturn(Optional.of("False"));
    assertThat(guard.isEnabledFor(requester)).isFalse();
  }

  @Test
  void passesTheRequesterCosSoCosLevelOverridesAreHonored() {
    // Regression: the guard must forward the requester's cos to the resolver; otherwise the COS
    // tier is skipped and a cos-level disable is not enforced (account > cos > default).
    when(requester.getCosId()).thenReturn(null);
    when(configResolver.get(Optional.of(USER_ID), Optional.empty(), SHARES_ENABLED))
        .thenReturn(Optional.of("false"));
    assertThat(guard.isEnabledFor(requester)).isFalse();
  }
}
