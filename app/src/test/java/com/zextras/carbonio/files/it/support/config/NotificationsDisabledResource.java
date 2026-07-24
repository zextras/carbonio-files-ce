// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.it.support.config;

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import java.util.Map;

/**
 * Class-restricted config resource: disables new-share/added-node/removed-node notifications
 * ({@code networking-config.carbonio.files.enable-notifications=false}), so a sibling of a
 * notification-scenario class can assert the suppression path. See {@link UploadCapResource}'s
 * javadoc for the mechanism.
 */
public class NotificationsDisabledResource implements QuarkusTestResourceLifecycleManager {

  @Override
  public Map<String, String> start() {
    return Map.of("networking-config.carbonio.files.enable-notifications", "false");
  }

  @Override
  public void stop() {
    // No external process/container to tear down.
  }
}
