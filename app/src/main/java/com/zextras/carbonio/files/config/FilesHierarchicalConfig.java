// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.config;

import com.zextras.carbonio.quarkus.extensions.bootstrap.ConfigKey;

public final class FilesHierarchicalConfig {

  private FilesHierarchicalConfig() {}

  public static final class HierarchicalConfig {
    private HierarchicalConfig() {}

    @ConfigKey(description = "Whether the user may create and update shares with specific accounts")
    public static final String ACCOUNT_SHARES_ENABLED = "account-shares-enabled";

    @ConfigKey(description = "Whether the user may create internal (collaboration) links")
    public static final String COLLABORATION_LINKS_ENABLED = "collaboration-links-enabled";

    @ConfigKey(
        description = "Whether the user may create and update public links, Smart Links included")
    public static final String PUBLIC_LINKS_ENABLED = "public-links-enabled";

    @ConfigKey(
        description =
            "Whether the user may grant the write permission in shares and collaboration links")
    public static final String SHARE_WRITE_PERMISSION_ENABLED = "share-write-permission-enabled";

    @ConfigKey(
        description =
            "Whether the user may grant the reshare permission in shares and collaboration links")
    public static final String SHARE_RESHARE_PERMISSION_ENABLED =
        "share-reshare-permission-enabled";
  }
}
