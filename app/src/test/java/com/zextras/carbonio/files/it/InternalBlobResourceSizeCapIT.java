// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.it;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zextras.carbonio.files.it.support.AbstractFilesIT;
import com.zextras.carbonio.files.it.support.config.UploadCapResource;
import io.quarkus.test.common.TestResourceScope;
import io.quarkus.test.common.WithTestResource;
import io.restassured.RestAssured;
import io.restassured.response.Response;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Config-split sibling of {@link InternalBlobResourceApiIT} (Batch D / D3): carries the ONE
 * scenario that needs an ACTUAL {@code application-config.max-uploadable-size-in-mb} cap
 * configured ({@link UploadCapResource}, cap=0, class-restricted) to meaningfully assert that the
 * trusted {@code /internal/accounts/{userId}/upload} route bypasses it entirely. See {@link
 * InternalBlobResourceApiIT}'s javadoc for the full split mapping (8 base + 1 here = 9).
 */
@WithTestResource(value = UploadCapResource.class, scope = TestResourceScope.RESTRICTED_TO_CLASS)
class InternalBlobResourceSizeCapIT extends AbstractFilesIT {

  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
  private static final String REQUESTER_ID = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";

  private static String toBase64(String value) {
    return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
  }

  /** Deliberately takes NO cookie parameter: {@code /internal/**} has no auth-handler. */
  private static Response internalUpload(String userId, String filenameB64, byte[] body) {
    return RestAssured.given().header("Filename", filenameB64).body(body).post("/internal/accounts/" + userId + "/upload");
  }

  @Test
  void givenABodyOverTheConfiguredSizeCapInternalUploadShouldStillSucceed() throws Exception {
    // Given — a 0MB cap is configured (this class's UploadCapResource): ANY non-empty body is
    // "over" it, so a plain few-KB body already proves the bypass; kept sizeable (2MB, as in the
    // original) to also document that the bypass is not merely a small-body coincidence.
    byte[] oversizedBody = new byte[2 * 1024 * 1024]; // 2MB, definitely over the 0MB cap

    // When
    Response response = internalUpload(REQUESTER_ID, toBase64("big-internal.bin"), oversizedBody);

    // Then — no 413: the size cap is bypassed entirely for the internal route
    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    Map<String, Object> json = OBJECT_MAPPER.readValue(response.getBody().asString(), Map.class);
    Assertions.assertThat(json).containsKey("nodeId");
  }
}
