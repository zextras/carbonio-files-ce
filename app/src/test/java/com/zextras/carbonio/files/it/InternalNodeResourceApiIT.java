// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.it;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zextras.carbonio.files.FilesStackTestResource;
import com.zextras.carbonio.files.it.support.AbstractFilesIT;
import io.restassured.RestAssured;
import io.restassured.response.Response;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Out-of-process {@code @QuarkusIntegrationTest} (on {@link AbstractFilesIT}) for the version-aware
 * trusted-caller node-metadata endpoint {@code GET /internal/accounts/{userId}/nodes/{nodeId}}. The
 * optional {@code version} query parameter restores parity with the retired GraphQL {@code
 * getNode(node_id, version)} query: omitting it returns the CURRENT version's metadata (unchanged
 * legacy behaviour), while {@code ?version=N} returns that historical version's version-scoped
 * fields (size/updatedAt/mimeType) so a consumer opening an older version (e.g. docs-connector
 * WOPI) sees metadata that matches the bytes {@code /download/{nodeId}/{version}} serves. Like the
 * sibling {@link InternalBlobResourceApiIT}, none of these routes has an auth-handler (mesh mTLS is
 * the trust boundary): the {@code userId} PATH segment alone drives whose ACLs are checked.
 */
class InternalNodeResourceApiIT extends AbstractFilesIT {

  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  private static final String REQUESTER_ID = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
  private static final String REQUESTER_COOKIE = "ZM_AUTH_TOKEN=fake-token";

  @BeforeAll
  static void registerUsers() {
    FilesStackTestResource.getUserManagementService().registerToken("fake-token", REQUESTER_ID);
  }

  /** Deliberately takes NO cookie: {@code /internal/**} has no auth-handler in its pipeline. */
  private static Response internalGetNode(String userId, String nodeId, Integer version) {
    var request = RestAssured.given();
    if (version != null) {
      request = request.queryParam("version", version);
    }
    return request.get("/internal/accounts/" + userId + "/nodes/" + nodeId);
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> asMap(Response response) throws Exception {
    return OBJECT_MAPPER.readValue(response.getBody().asString(), Map.class);
  }

  @Test
  void internalGetNodeShouldBeVersionAwareAcrossTwoVersionsOfDifferentSize() throws Exception {
    // Given — a file with v1, then a differently-sized v2 (now the current version). tickClock()
    // guarantees v2's updated_at is strictly greater than v1's, so the version-scoped updatedAt is
    // observably distinct, not just the size.
    byte[] v1 = "v1-small".getBytes(StandardCharsets.UTF_8); // 8 bytes
    byte[] v2 = "v2-considerably-longer-body".getBytes(StandardCharsets.UTF_8); // 27 bytes
    String nodeId = seedFile("versioned.txt", LOCAL_ROOT, v1, REQUESTER_COOKIE);
    tickClock();
    int currentVersion = seedVersion(nodeId, v2, "versioned.txt", REQUESTER_COOKIE);
    Assertions.assertThat(currentVersion).isEqualTo(2);

    // When — omitting version returns the CURRENT (v2) metadata, exactly as before.
    Response currentResponse = internalGetNode(REQUESTER_ID, nodeId, null);
    Assertions.assertThat(currentResponse.getStatusCode()).isEqualTo(200);
    Map<String, Object> current = asMap(currentResponse);
    Assertions.assertThat(((Number) current.get("version")).intValue()).isEqualTo(2);
    Assertions.assertThat(((Number) current.get("size")).longValue()).isEqualTo(v2.length);
    long currentUpdatedAt = ((Number) current.get("updatedAt")).longValue();

    // And — an explicit ?version=1 returns v1's version-scoped metadata, NOT the current one.
    Response v1Response = internalGetNode(REQUESTER_ID, nodeId, 1);
    Assertions.assertThat(v1Response.getStatusCode()).isEqualTo(200);
    Map<String, Object> versionOne = asMap(v1Response);
    Assertions.assertThat(((Number) versionOne.get("version")).intValue()).isEqualTo(1);
    Assertions.assertThat(((Number) versionOne.get("size")).longValue()).isEqualTo(v1.length);
    long v1UpdatedAt = ((Number) versionOne.get("updatedAt")).longValue();
    Assertions.assertThat(v1UpdatedAt).isLessThan(currentUpdatedAt);

    // Node-level fields are identical regardless of the requested version.
    Assertions.assertThat(versionOne.get("id")).isEqualTo(current.get("id")).isEqualTo(nodeId);
    Assertions.assertThat(versionOne.get("name")).isEqualTo(current.get("name"));
  }

  @Test
  void internalGetNodeWithANonExistentVersionShouldReturn404() throws Exception {
    String nodeId =
        seedFile(
            "onlyOneVersion.txt",
            LOCAL_ROOT,
            "content".getBytes(StandardCharsets.UTF_8),
            REQUESTER_COOKIE);

    Response response = internalGetNode(REQUESTER_ID, nodeId, 99);

    Assertions.assertThat(response.getStatusCode()).isEqualTo(404);
  }

  @Test
  void internalGetNodeOfANonExistentNodeShouldReturn404() {
    String nonExistentId = "10000000-0000-0000-0000-00000000ffff";
    Response response = internalGetNode(REQUESTER_ID, nonExistentId, null);
    Assertions.assertThat(response.getStatusCode()).isEqualTo(404);
  }
}
