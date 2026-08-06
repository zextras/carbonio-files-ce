// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.it;

import com.zextras.carbonio.files.FilesStackTestResource;
import com.zextras.carbonio.files.it.support.AbstractFilesIT;
import com.zextras.carbonio.files.it.support.config.DownloadGenerousCapResource;
import io.quarkus.test.common.TestResourceScope;
import io.quarkus.test.common.WithTestResource;
import io.restassured.response.Response;
import java.util.List;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Config-split sibling of {@link MultiDownloadZipApiIT} (Batch E / D3): carries the ONE scenario
 * that needs a cap PRESENT-but-NOT-exceeded ({@link DownloadGenerousCapResource}, {@code 100}MB,
 * class-restricted) — genuinely distinct, at the {@code BlobService#checkDownloadMultipleInternal}
 * branch level, from the default (uncapped) stack's {@code Optional.empty()} short-circuit that
 * {@link MultiDownloadZipApiIT}'s other methods run under. See {@link MultiDownloadZipApiIT}'s
 * javadoc for the full split mapping (21 base + 2 {@link MultiDownloadZipSizeCapIT} + 1 here = 24).
 */
@WithTestResource(
    value = DownloadGenerousCapResource.class,
    scope = TestResourceScope.RESTRICTED_TO_CLASS)
class MultiDownloadZipGenerousSizeCapIT extends AbstractFilesIT {

  private static final String REQUESTER_ID = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
  private static final String REQUESTER_COOKIE = "ZM_AUTH_TOKEN=fake-token";

  @BeforeAll
  static void registerUsers() {
    FilesStackTestResource.getUserManagementService().registerToken("fake-token", REQUESTER_ID);
  }

  @Test
  void givenTotalSizeWithinAGenerousCapCheckDownloadMultipleShouldReturn204() {
    String fileId = seedFile("small.bin", LOCAL_ROOT, new byte[10], REQUESTER_COOKIE);

    Response response =
        checkDownloadMultiple(MultiDownloadZipApiIT.checkBodyOf(List.of(fileId)), REQUESTER_COOKIE);

    Assertions.assertThat(response.getStatusCode()).isEqualTo(204);
  }
}
