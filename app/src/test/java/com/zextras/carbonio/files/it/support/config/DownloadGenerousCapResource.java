// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.it.support.config;

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import java.util.Map;

/**
 * Class-restricted config resource: caps {@code max-downloadable-size-in-mb} to a GENEROUS {@code
 * 100}, distinct from {@link DownloadCapResource}'s {@code 0}. {@code
 * BlobService#checkDownloadMultipleInternal}'s size-cap check is {@code maxFileSize.isPresent() &&
 * totalSizeRequestedInMb > maxFileSize.get()} — a cap that is PRESENT but NOT exceeded takes a
 * genuinely different branch than no cap being configured at all (the default, uncapped stack
 * short-circuits on {@code isPresent() == false}). This resource lets a class assert the
 * present-but-not-exceeded branch with real branch fidelity, added for {@code
 * MultiDownloadZipApiIT}'s Batch E migration (not present in the original plan's config-resource
 * set, which only enumerated the {@code 0}-cap variants).
 */
public class DownloadGenerousCapResource implements QuarkusTestResourceLifecycleManager {

  @Override
  public Map<String, String> start() {
    return Map.of("application-config.max-downloadable-size-in-mb", "100");
  }

  @Override
  public void stop() {
    // No external process/container to tear down.
  }
}
