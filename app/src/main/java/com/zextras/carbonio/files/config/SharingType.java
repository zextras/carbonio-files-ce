// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.config;

import com.zextras.carbonio.files.config.FilesHierarchicalConfig.HierarchicalConfig;

public enum SharingType {
  ACCOUNT_SHARES(HierarchicalConfig.ACCOUNT_SHARES_ENABLED),
  COLLABORATION_LINKS(HierarchicalConfig.COLLABORATION_LINKS_ENABLED),
  PUBLIC_LINKS(HierarchicalConfig.PUBLIC_LINKS_ENABLED);

  private final String configKey;

  SharingType(String configKey) {
    this.configKey = configKey;
  }

  public String configKey() {
    return configKey;
  }
}
