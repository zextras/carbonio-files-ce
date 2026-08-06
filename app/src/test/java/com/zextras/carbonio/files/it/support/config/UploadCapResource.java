// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.it.support.config;

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import java.util.Map;

/**
 * Class-restricted config resource: caps {@code max-uploadable-size-in-mb} to {@code 0} (any upload
 * is over-limit), so an {@code @QuarkusIntegrationTest} class can assert the 413 path without an
 * in-JVM {@code @Inject}able config double (unavailable out-of-process).
 *
 * <p>Add via a SECOND {@code @WithTestResource(UploadCapResource.class, scope =
 * TestResourceScope.RESTRICTED_TO_CLASS)} on the specific config-variant class, alongside the
 * stack-wide {@code @WithTestResource(FilesStackTestResource.class)} inherited from {@code
 * AbstractFilesIT} — this resource ADDS one config property to the same launched-process config
 * channel {@code FilesStackTestResource} already proves works out-of-process (see {@code
 * ConfigCapSpikeIT}, Phase 1).
 */
public class UploadCapResource implements QuarkusTestResourceLifecycleManager {

  @Override
  public Map<String, String> start() {
    return Map.of("application-config.max-uploadable-size-in-mb", "0");
  }

  @Override
  public void stop() {
    // No external process/container to tear down: this resource only contributes a config
    // property to the launched app's config map.
  }
}
