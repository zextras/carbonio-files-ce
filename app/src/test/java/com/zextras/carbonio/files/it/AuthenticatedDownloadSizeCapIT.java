// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.it;

import com.zextras.carbonio.files.FilesStackTestResource;
import com.zextras.carbonio.files.it.support.AbstractFilesIT;
import com.zextras.carbonio.files.it.support.config.DownloadCapResource;
import io.quarkus.test.common.TestResourceScope;
import io.quarkus.test.common.WithTestResource;
import io.restassured.response.Response;
import java.nio.charset.StandardCharsets;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Config-split sibling of {@link AuthenticatedDownloadApiIT} (Batch E / D3 of the
 * acceptance-to-Quarkus-tests plan): carries the ONE scenario that needs an ACTUAL {@code
 * application-config.max-downloadable-size-in-mb} cap configured ({@link DownloadCapResource},
 * cap=0, class-restricted) to meaningfully assert the download-side 413 path. See {@link
 * AuthenticatedDownloadApiIT}'s javadoc for the full split mapping (8 base + 1 here = 9).
 */
@WithTestResource(value = DownloadCapResource.class, scope = TestResourceScope.RESTRICTED_TO_CLASS)
class AuthenticatedDownloadSizeCapIT extends AbstractFilesIT {

  private static final String REQUESTER_ID = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
  private static final String REQUESTER_COOKIE = "ZM_AUTH_TOKEN=fake-token";

  @BeforeAll
  static void registerUsers() {
    FilesStackTestResource.getUserManagementService().registerToken("fake-token", REQUESTER_ID);
  }

  @Test
  void givenTheNodeSizeOverTheConfiguredCapDownloadShouldReturn413() {
    // Given — a 0MB cap is configured (this class's DownloadCapResource): any non-empty node
    // exceeds it.
    String nodeId = seedFile("big.bin", LOCAL_ROOT, "any non-empty content".getBytes(StandardCharsets.UTF_8), REQUESTER_COOKIE);
    // seedFile's real upload path itself performs one storages verify-blob-exists GET /download;
    // reset the fake's download log so the assertion below covers only the capped-download attempt.
    FilesStackTestResource.getStoragesService().reset();

    // When
    Response response = download(nodeId, REQUESTER_COOKIE);

    // Then — FileSizeException groups with the generic-body 413 branch in ExceptionsHandler (the
    // descriptive message is discarded, same quirk as the upload-side size cap)
    Assertions.assertThat(response.getStatusCode()).isEqualTo(413);
    Assertions.assertThat(response.getBody().asString()).isEqualTo("413 Request Entity Too Large");
    FilesStackTestResource.getStoragesService().verifyNeverDownloaded();
  }
}
