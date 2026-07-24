// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.it;

import com.zextras.carbonio.files.FilesStackTestResource;
import com.zextras.carbonio.files.it.support.AbstractFilesIT;
import com.zextras.carbonio.files.it.support.config.UploadCapResource;
import io.quarkus.test.common.TestResourceScope;
import io.quarkus.test.common.WithTestResource;
import io.restassured.response.Response;
import java.nio.charset.StandardCharsets;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Config-split sibling of {@link UploadFileApiIT} (Batch D / D3 of the acceptance-to-Quarkus-tests
 * plan): carries the ONE scenario that needs {@code application-config.max-uploadable-size-in-mb}
 * capped to {@code 0} ({@link UploadCapResource}, class-restricted so it never leaks into other
 * classes sharing the suite-wide {@link FilesStackTestResource}). {@code FilesConfig}'s size cap
 * is a boot-time snapshot on the launched out-of-process app, so this scenario cannot share
 * {@code UploadFileApiIT}'s default (uncapped) stack.
 *
 * <p><b>Split mapping (Batch D config-split accounting):</b> {@code UploadFileApiIT} = 9 methods +
 * {@code UploadFileSizeCapIT} (this class) = 1 method → 10 total, unchanged from the original
 * {@code acceptance.UploadFileApiIT}.
 */
@WithTestResource(value = UploadCapResource.class, scope = TestResourceScope.RESTRICTED_TO_CLASS)
class UploadFileSizeCapIT extends AbstractFilesIT {

  private static final String REQUESTER_ID = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
  private static final String REQUESTER_COOKIE = "ZM_AUTH_TOKEN=fake-token";

  @BeforeAll
  static void registerUsers() {
    FilesStackTestResource.getUserManagementService().registerToken("fake-token", REQUESTER_ID);
  }

  @Test
  void givenABodyOverTheConfiguredSizeCapUploadShouldReturn413() {
    // Given — a 0MB cap (this class's UploadCapResource) + a tiny (few-byte) body: BlobController
    // rejects based on Content-Length alone, before reading any body bytes.
    byte[] oversizedBody = "over the 0MB cap".getBytes(StandardCharsets.UTF_8);

    // When
    Response response = upload(null, null, oversizedBody, "big.bin", REQUESTER_COOKIE);

    // Then
    Assertions.assertThat(response.getStatusCode()).isEqualTo(413);
    Assertions.assertThat(response.getBody().asString()).isEqualTo("413 Request Entity Too Large");
  }
}
