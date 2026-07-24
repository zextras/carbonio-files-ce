// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.it.support.config;

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import java.util.Map;

/**
 * Class-restricted config resource: caps {@code max-downloadable-size-in-mb} to {@code 0}, so an
 * {@code @QuarkusIntegrationTest} class can assert the download-side over-limit path. See {@link
 * UploadCapResource}'s javadoc for the mechanism (a second class-scoped {@code @WithTestResource}
 * alongside {@code FilesStackTestResource}).
 */
public class DownloadCapResource implements QuarkusTestResourceLifecycleManager {

  @Override
  public Map<String, String> start() {
    return Map.of("application-config.max-downloadable-size-in-mb", "0");
  }

  @Override
  public void stop() {
    // No external process/container to tear down.
  }
}
