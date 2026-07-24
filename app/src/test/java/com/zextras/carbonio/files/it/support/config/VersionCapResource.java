// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.it.support.config;

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import java.util.Map;

/**
 * Class-restricted config resource: caps {@code max-number-of-versions} to {@code 2}, so an
 * {@code @QuarkusIntegrationTest} class can assert version-trim behaviour (kept current +
 * keepForever, drops least-recent) without waiting for 30 real uploads. See {@link
 * UploadCapResource}'s javadoc for the mechanism.
 */
public class VersionCapResource implements QuarkusTestResourceLifecycleManager {

  @Override
  public Map<String, String> start() {
    return Map.of("application-config.max-number-of-versions", "2");
  }

  @Override
  public void stop() {
    // No external process/container to tear down.
  }
}
