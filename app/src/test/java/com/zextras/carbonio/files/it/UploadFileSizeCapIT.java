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
 * Config-split sibling of {@link UploadFileApiIT} (Batch D / D3 of the acceptance-to-Quarkus-tests
 * plan): carries the ONE scenario that needs {@code application-config.max-uploadable-size-in-mb}
 * capped to {@code 0}.
 *
 * <p><b>Single-launch model:</b> instead of a class-restricted test resource — which forced a full
 * out-of-process app restart for this one assertion — the cap is now published at RUNTIME on the
 * shared Consul WireMock via {@link AbstractFilesIT#setApplicationConfig} and read live by {@code
 * FilesConfig} (extension 1.13.0-1 keeps the Consul KV view live), so this class shares the
 * suite-wide {@link FilesStackTestResource} launch with every other IT. {@code @AfterEach} restores
 * the uncapped default so the cap never leaks to the next test on the shared app.
 */
class UploadFileSizeCapIT extends AbstractFilesIT {

  private static final String MAX_UPLOADABLE_SIZE_IN_MB = "max-uploadable-size-in-mb";
  private static final String REQUESTER_ID = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
  private static final String REQUESTER_COOKIE = "ZM_AUTH_TOKEN=fake-token";

  @BeforeAll
  static void registerUsers() {
    FilesStackTestResource.getUserManagementService().registerToken("fake-token", REQUESTER_ID);
  }

  @BeforeEach
  void capUploadsToZero() {
    setApplicationConfig(MAX_UPLOADABLE_SIZE_IN_MB, "0");
  }

  @AfterEach
  void restoreUncappedUploads() {
    clearApplicationConfig(MAX_UPLOADABLE_SIZE_IN_MB, null);
  }

  @Test
  void givenABodyOverTheConfiguredSizeCapUploadShouldReturn413() {
    // Given — a 0MB cap (published at runtime above) + a tiny (few-byte) body: BlobController
    // rejects based on Content-Length alone, before reading any body bytes.
    byte[] oversizedBody = "over the 0MB cap".getBytes(StandardCharsets.UTF_8);

    // When
    Response response = upload(null, null, oversizedBody, "big.bin", REQUESTER_COOKIE);

    // Then
    Assertions.assertThat(response.getStatusCode()).isEqualTo(413);
    Assertions.assertThat(response.getBody().asString()).isEqualTo("413 Request Entity Too Large");
  }
}
