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
 * Config-split sibling of {@link MultiDownloadZipApiIT} (Batch E / D3 of the
 * acceptance-to-Quarkus-tests plan): carries the TWO scenarios (the {@code /check} variant and the
 * actual streamed-download variant) that need an ACTUAL {@code
 * application-config.max-downloadable-size-in-mb} cap of {@code 0} configured (cap=0, published at
 * runtime via setApplicationConfig on the shared stack) to meaningfully assert the
 * total-size-over-cap 413 path. See {@link MultiDownloadZipApiIT}'s javadoc for the full split
 * mapping (21 base + 2 here + 1 {@link MultiDownloadZipGenerousSizeCapIT} = 24).
 */
class MultiDownloadZipSizeCapIT extends AbstractFilesIT {

  private static final String REQUESTER_ID = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
  private static final String REQUESTER_COOKIE = "ZM_AUTH_TOKEN=fake-token";

  @BeforeAll
  static void registerUsers() {
    FilesStackTestResource.getUserManagementService().registerToken("fake-token", REQUESTER_ID);
  }

  private static final String MAX_DOWNLOADABLE_SIZE_IN_MB = "max-downloadable-size-in-mb";

  @BeforeEach
  void capDownloadsToZero() {
    setApplicationConfig(MAX_DOWNLOADABLE_SIZE_IN_MB, "0");
  }

  @AfterEach
  void restoreUncappedDownloads() {
    clearApplicationConfig(MAX_DOWNLOADABLE_SIZE_IN_MB, null);
  }

  @Test
  void givenTotalSizeExceedingCapCheckDownloadMultipleShouldReturn413() {
    String fileId = seedFile("big.bin", LOCAL_ROOT, new byte[1024], REQUESTER_COOKIE);

    Response response =
        checkDownloadMultiple(MultiDownloadZipApiIT.checkBodyOf(List.of(fileId)), REQUESTER_COOKIE);

    Assertions.assertThat(response.getStatusCode()).isEqualTo(413);
  }

  @Test
  void givenTotalSizeExceedingCapTheDownloadMultipleShouldReturn413() {
    String fileId = seedFile("big.bin", LOCAL_ROOT, new byte[1024], REQUESTER_COOKIE);
    // seedFile's upload itself triggers one storages verify-blob-exists GET /download; reset so
    // "never downloaded" below asserts only the (rejected) download-multiple attempt.
    FilesStackTestResource.getStoragesService().reset();

    Response response = downloadMultiple(List.of(fileId), REQUESTER_COOKIE);

    Assertions.assertThat(response.getStatusCode()).isEqualTo(413);
    FilesStackTestResource.getStoragesService().verifyNeverDownloaded();
  }
}
