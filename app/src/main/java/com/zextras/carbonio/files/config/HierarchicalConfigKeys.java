// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.config;

import com.zextras.carbonio.quarkus.extensions.bootstrap.ConfigKey;
import java.util.List;

public final class HierarchicalConfigKeys {

  private HierarchicalConfigKeys() {}

  public interface HierarchicalConfig {

    @ConfigKey(
        description =
            "Whether sharing is enabled (per account/cos/domain override; base default true)")
    String SHARES_ENABLED = "shares-enabled";
  }

  /**
   * Every hierarchical-config key files declares, used by the config read endpoint to return the
   * caller's effective values. Add new {@link HierarchicalConfig} keys here too.
   */
  public static final List<String> ALL_KEYS = List.of(HierarchicalConfig.SHARES_ENABLED);
}
