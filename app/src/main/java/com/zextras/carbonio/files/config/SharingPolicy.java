// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.config;

import com.zextras.carbonio.files.config.FilesHierarchicalConfig.HierarchicalConfig;
import com.zextras.carbonio.files.dal.dao.UserInfo;
import com.zextras.carbonio.files.dal.dao.UserMyself;
import com.zextras.carbonio.files.dal.dao.ebean.ACL;
import com.zextras.carbonio.quarkus.extensions.confighierarchical.ConfigResolver;
import com.zextras.carbonio.quarkus.extensions.confighierarchical.ConfigScope;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@ApplicationScoped
public class SharingPolicy {

  private static final Logger logger = LoggerFactory.getLogger(SharingPolicy.class);

  private final ConfigResolver configResolver;

  @Inject
  public SharingPolicy(ConfigResolver configResolver) {
    this.configResolver = configResolver;
  }

  public static ConfigScope scopeOf(UserMyself user) {
    return ConfigScope.account(user.getId().getUserId(), user.getCosId(), user.getDomainId());
  }

  public static ConfigScope scopeOf(UserInfo user) {
    return ConfigScope.account(user.getId().getUserId(), user.getCosId(), user.getDomainId());
  }

  public boolean isEnabled(SharingType type, ConfigScope scope) {
    return isTrue(scope, type.configKey());
  }

  public boolean allowsGrant(ACL.SharePermission permission, ConfigScope scope) {
    short requested = permission.encode();
    if ((requested & ACL.WRITE) != 0
        && !isTrue(scope, HierarchicalConfig.SHARE_WRITE_PERMISSION_ENABLED)) {
      return false;
    }
    return (requested & ACL.SHARE) == 0
        || isTrue(scope, HierarchicalConfig.SHARE_RESHARE_PERMISSION_ENABLED);
  }

  private boolean isTrue(ConfigScope scope, String key) {
    ConfigResolver.Resolution resolution = configResolver.resolve(scope, key);
    String value = resolution.value().map(String::trim).orElse("");
    if ("true".equalsIgnoreCase(value)) {
      return true;
    }
    if (!"false".equalsIgnoreCase(value)) {
      logger.warn(
          "Config key {} resolved from {} is not a boolean: treated as false",
          key,
          resolution.source());
    }
    return false;
  }
}
