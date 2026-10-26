// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.config;

import com.zextras.carbonio.quarkus.extensions.bootstrap.ConfigKey;

public final class FilesHierarchicalConfig {

  private FilesHierarchicalConfig() {}

  public static final class HierarchicalConfig {
    private HierarchicalConfig() {}

    @ConfigKey(description = "Test key of the hierarchical config skeleton (CO-4496), no behaviour")
    public static final String TEST_KEY = "test-key";
  }
}
