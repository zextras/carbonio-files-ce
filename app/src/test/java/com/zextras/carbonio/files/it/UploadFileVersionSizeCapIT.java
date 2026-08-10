// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.it;

import com.zextras.carbonio.files.FilesStackTestResource;
import com.zextras.carbonio.files.it.support.AbstractFilesIT;
import io.restassured.response.Response;
import java.nio.charset.StandardCharsets;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Config-split sibling of {@link UploadFileVersionApiIT} (Batch D / D3): carries the ONE scenario
 * that needs {@code application-config.max-uploadable-size-in-mb} capped to {@code 0} (published at
 * runtime via setApplicationConfig on the shared stack). See {@link UploadFileVersionApiIT}'s
 * javadoc for the full
 * split mapping (5 base + 1 here + 3 in {@link UploadFileVersionCountCapIT} = 9).
 */
class UploadFileVersionSizeCapIT extends AbstractFilesIT {

  private static final String REQUESTER_ID = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
  private static final String REQUESTER_COOKIE = "ZM_AUTH_TOKEN=fake-token";

  @BeforeAll
  static void registerUsers() {
    FilesStackTestResource.getUserManagementService().registerToken("fake-token", REQUESTER_ID);
  }

  private static final String MAX_UPLOADABLE_SIZE_IN_MB = "max-uploadable-size-in-mb";

  @BeforeEach
  void capUploadsToZero() {
    setApplicationConfig(MAX_UPLOADABLE_SIZE_IN_MB, "0");
  }

  @AfterEach
  void restoreUncappedUploads() {
    clearApplicationConfig(MAX_UPLOADABLE_SIZE_IN_MB, null);
  }

  @Test
  void givenABodyOverTheConfiguredSizeCapUploadVersionShouldReturn413() {
    // Given — a 0MB cap (max-uploadable-size-in-mb=0, set in @BeforeEach) is active for the WHOLE class, including
    // the fixture-seeding upload below: an EMPTY (0-byte) body is NOT "over" a 0MB cap (0 > 0 is
    // false, see BlobResource#isRequestSizeOverLimit), so the pre-existing v1 must be seeded with
    // zero bytes to succeed under this class's stack; only the actual assertion body is non-empty.
    String nodeId = seedFile("fake.txt", LOCAL_ROOT, new byte[0], REQUESTER_COOKIE);
    byte[] oversizedBody = "over the 0MB cap".getBytes(StandardCharsets.UTF_8);

    // When
    Response response = uploadVersion(nodeId, oversizedBody, "fake.txt", false, REQUESTER_COOKIE);

    // Then
    Assertions.assertThat(response.getStatusCode()).isEqualTo(413);
    Assertions.assertThat(response.getBody().asString()).isEqualTo("413 Request Entity Too Large");
  }
}
