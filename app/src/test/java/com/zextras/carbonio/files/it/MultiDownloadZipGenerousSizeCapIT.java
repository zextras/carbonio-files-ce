// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.it;

import com.zextras.carbonio.files.FilesStackTestResource;
import com.zextras.carbonio.files.it.support.AbstractFilesIT;
import io.restassured.response.Response;
import java.util.List;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Config-split sibling of {@link MultiDownloadZipApiIT} (Batch E / D3): carries the ONE scenario
 * that needs a cap PRESENT-but-NOT-exceeded (100MB cap, published at runtime via
 * setApplicationConfig on the shared stack) — genuinely distinct, at the {@code
 * BlobService#checkDownloadMultipleInternal} branch level, from the default (uncapped) stack's
 * {@code Optional.empty()} short-circuit that {@link MultiDownloadZipApiIT}'s other methods run
 * under. See {@link MultiDownloadZipApiIT}'s javadoc for the full split mapping (21 base + 2 {@link
 * MultiDownloadZipSizeCapIT} + 1 here = 24).
 */
class MultiDownloadZipGenerousSizeCapIT extends AbstractFilesIT {

  private static final String REQUESTER_ID = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
  private static final String REQUESTER_COOKIE = "ZM_AUTH_TOKEN=fake-token";

  @BeforeAll
  static void registerUsers() {
    FilesStackTestResource.getUserManagementService().registerToken("fake-token", REQUESTER_ID);
  }

  private static final String MAX_DOWNLOADABLE_SIZE_IN_MB = "max-downloadable-size-in-mb";

  @BeforeEach
  void setGenerousDownloadCap() {
    setApplicationConfig(MAX_DOWNLOADABLE_SIZE_IN_MB, "100");
  }

  @AfterEach
  void clearDownloadCap() {
    clearApplicationConfig(MAX_DOWNLOADABLE_SIZE_IN_MB, null);
  }

  @Test
  void givenTotalSizeWithinAGenerousCapCheckDownloadMultipleShouldReturn204() {
    String fileId = seedFile("small.bin", LOCAL_ROOT, new byte[10], REQUESTER_COOKIE);

    Response response =
        checkDownloadMultiple(MultiDownloadZipApiIT.checkBodyOf(List.of(fileId)), REQUESTER_COOKIE);

    Assertions.assertThat(response.getStatusCode()).isEqualTo(204);
  }
}
