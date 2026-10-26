// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.config;

import com.zextras.carbonio.quarkus.extensions.bootstrap.ConfigKey;

public final class FilesHierarchicalConfig {

  private FilesHierarchicalConfig() {}

  public interface HierarchicalConfig {
    @ConfigKey(description = "Test key of the hierarchical config skeleton (CO-4496), no behaviour")
    String TEST_KEY = "test-key";
  }
}
