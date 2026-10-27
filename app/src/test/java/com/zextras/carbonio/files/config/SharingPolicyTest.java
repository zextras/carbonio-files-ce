// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.config;

import static com.zextras.carbonio.files.config.FilesHierarchicalConfig.HierarchicalConfig.PUBLIC_LINKS_ENABLED;
import static com.zextras.carbonio.files.config.FilesHierarchicalConfig.HierarchicalConfig.SHARE_RESHARE_PERMISSION_ENABLED;
import static com.zextras.carbonio.files.config.FilesHierarchicalConfig.HierarchicalConfig.SHARE_WRITE_PERMISSION_ENABLED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.zextras.carbonio.files.dal.dao.UserId;
import com.zextras.carbonio.files.dal.dao.UserInfo;
import com.zextras.carbonio.files.dal.dao.UserMyself;
import com.zextras.carbonio.files.dal.dao.ebean.ACL.SharePermission;
import com.zextras.carbonio.quarkus.extensions.confighierarchical.ConfigResolver;
import com.zextras.carbonio.quarkus.extensions.confighierarchical.ConfigScope;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

class SharingPolicyTest {

  private final ConfigResolver resolver = mock(ConfigResolver.class);
  private final SharingPolicy policy = new SharingPolicy(resolver);
  private final ConfigScope scope = ConfigScope.account("acc", "cos", "dom");

  private void resolves(String key, String value) {
    when(resolver.resolve(scope, key))
        .thenReturn(
            new ConfigResolver.Resolution(
                Optional.ofNullable(value), ConfigResolver.Source.GLOBAL));
  }

  @ParameterizedTest
  @ValueSource(strings = {"true", "TRUE", " True "})
  void trueValuesEnableTheType(String value) {
    resolves(PUBLIC_LINKS_ENABLED, value);
    assertThat(policy.isEnabled(SharingType.PUBLIC_LINKS, scope)).isTrue();
  }

  @ParameterizedTest
  @ValueSource(strings = {"false", "FALSE", "yes", "1", "flase"})
  void anyOtherValueDisablesTheType(String value) {
    resolves(PUBLIC_LINKS_ENABLED, value);
    assertThat(policy.isEnabled(SharingType.PUBLIC_LINKS, scope)).isFalse();
  }

  @Test
  void aMissingValueDisablesTheType() {
    resolves(PUBLIC_LINKS_ENABLED, null);
    assertThat(policy.isEnabled(SharingType.PUBLIC_LINKS, scope)).isFalse();
  }

  @ParameterizedTest
  @EnumSource(SharingType.class)
  void eachTypeReadsItsOwnKey(SharingType type) {
    resolves(type.configKey(), "false");
    assertThat(policy.isEnabled(type, scope)).isFalse();
  }

  @Test
  void readOnlyIsAlwaysGrantableWithoutReadingTheConfig() {
    assertThat(policy.allowsGrant(SharePermission.READ_ONLY, scope)).isTrue();
    verifyNoInteractions(resolver);
  }

  @Test
  void writeNeedsTheWriteKey() {
    resolves(SHARE_WRITE_PERMISSION_ENABLED, "false");
    assertThat(policy.allowsGrant(SharePermission.READ_AND_WRITE, scope)).isFalse();
    resolves(SHARE_WRITE_PERMISSION_ENABLED, "true");
    assertThat(policy.allowsGrant(SharePermission.READ_AND_WRITE, scope)).isTrue();
  }

  @Test
  void reshareNeedsTheReshareKey() {
    resolves(SHARE_RESHARE_PERMISSION_ENABLED, "false");
    assertThat(policy.allowsGrant(SharePermission.READ_AND_SHARE, scope)).isFalse();
    resolves(SHARE_RESHARE_PERMISSION_ENABLED, "true");
    assertThat(policy.allowsGrant(SharePermission.READ_AND_SHARE, scope)).isTrue();
  }

  @Test
  void readWriteAndShareNeedsBothKeys() {
    resolves(SHARE_WRITE_PERMISSION_ENABLED, "true");
    resolves(SHARE_RESHARE_PERMISSION_ENABLED, "false");
    assertThat(policy.allowsGrant(SharePermission.READ_WRITE_AND_SHARE, scope)).isFalse();
    resolves(SHARE_RESHARE_PERMISSION_ENABLED, "true");
    assertThat(policy.allowsGrant(SharePermission.READ_WRITE_AND_SHARE, scope)).isTrue();
  }

  @Test
  void scopeOfUserMyselfCarriesAccountCosAndDomain() {
    UserMyself user = mock(UserMyself.class);
    when(user.getId()).thenReturn(new UserId("acc"));
    when(user.getCosId()).thenReturn("cos");
    when(user.getDomainId()).thenReturn(null);
    assertThat(SharingPolicy.scopeOf(user)).isEqualTo(ConfigScope.account("acc", "cos", null));
  }

  @Test
  void scopeOfUserInfoCarriesAccountCosAndDomain() {
    UserInfo user = new UserInfo();
    user.setId(new UserId("acc"));
    user.setCosId("cos");
    user.setDomainId("dom");
    assertThat(SharingPolicy.scopeOf(user)).isEqualTo(ConfigScope.account("acc", "cos", "dom"));
  }
}
