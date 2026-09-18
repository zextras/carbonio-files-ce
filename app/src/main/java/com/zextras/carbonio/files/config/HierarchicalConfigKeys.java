// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.config;

import com.zextras.carbonio.quarkus.extensions.bootstrap.ConfigKey;

public final class HierarchicalConfigKeys {

  private HierarchicalConfigKeys() {}

  public interface HierarchicalConfig {

    @ConfigKey(
        description =
            "Max file versions retained (per account/cos/domain override; base default 100)")
    String MAX_VERSIONS = "poc.max-versions";

    @ConfigKey(description = "Example key with empty base default")
    String SAMPLE_EMPTY = "poc.sample-empty";

    @ConfigKey(
        description =
            "Whether sharing is enabled (per account/cos/domain override; base default true)")
    String SHARES_ENABLED = "shares-enabled";
  }
}
