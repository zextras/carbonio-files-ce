// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.it;

import com.zextras.carbonio.files.FilesStackTestResource;
import com.zextras.carbonio.files.TestUtils;
import com.zextras.carbonio.files.api.utilities.GraphqlCommandBuilder;
import com.zextras.carbonio.files.dal.dao.ebean.ACL;
import com.zextras.carbonio.files.it.support.AbstractFilesIT;
import io.restassured.response.Response;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * {@code com.zextras.carbonio.files.acceptance.GetPathApiIT} rewritten as an out-of-process
 * {@code @QuarkusIntegrationTest} on {@link AbstractFilesIT}. All 4 methods and their assertions
 * (including the class-level FINDING about the "inconsistent share" edge silently degrading to a
 * one-element path) are preserved verbatim.
 *
 * <p>The "leaf shared with no ancestor share" pre-state is, contrary to the original's javadoc
 * claim about the backdoor being required, actually reachable via the real API too: the real
 * {@code createShare} mutation only shares the EXACT node it targets (no downward cascade onto
 * the SHARER's side — {@code cascadeUpsertShare} propagates a share onto a node's own descendants
 * when the node itself is later shared again, it does not retroactively create ancestor shares),
 * so directly sharing only the leaf (not any ancestor) reproduces the same state as the original
 * backdoor fixture. Only the seeding mechanism (API calls capturing server-generated ids) and
 * transport changed.
 */
class GetPathApiIT extends AbstractFilesIT {

  private static final String REQUESTER_ID = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
  private static final String OTHER_USER_ID = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";
  private static final String REQUESTER_COOKIE = "ZM_AUTH_TOKEN=fake-token";
  private static final String OTHER_COOKIE = "ZM_AUTH_TOKEN=fake-token-b";

  @BeforeAll
  static void registerUsers() {
    FilesStackTestResource.getUserManagementService().registerToken("fake-token", REQUESTER_ID);
    FilesStackTestResource.getUserManagementService().registerToken("fake-token-b", OTHER_USER_ID);
  }

  private Response getPathRaw(String nodeId, String cookie) {
    String bodyPayload =
        GraphqlCommandBuilder.aQueryBuilder("getPath")
            .withString("node_id", nodeId)
            .withWantedResultFormat("{ id name }")
            .build();
    return graphql(bodyPayload, cookie);
  }

  private List<Map<String, Object>> getPath(String nodeId, String cookie) {
    Response response = getPathRaw(nodeId, cookie);
    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    return TestUtils.jsonResponseToList(response.getBody().asString(), "getPath");
  }

  @Test
  void givenOwnedNodeGetPathShouldReturnTheFullChainFromRoot() {
    // Given — a 3-level chain, fully owned by the requester
    String folderAId = seedFolder("folderA", LOCAL_ROOT, REQUESTER_COOKIE);
    String folderBId = seedFolder("folderB", folderAId, REQUESTER_COOKIE);
    String fileCId = seedFile("fileC.txt", folderBId, "c".getBytes(StandardCharsets.UTF_8), REQUESTER_COOKIE);

    // When
    List<Map<String, Object>> path = getPath(fileCId, REQUESTER_COOKIE);

    // Then — the full chain, starting at LOCAL_ROOT itself
    Assertions.assertThat(path).hasSize(4);
    Assertions.assertThat(path.get(0)).containsEntry("id", "LOCAL_ROOT").containsEntry("name", "ROOT");
    Assertions.assertThat(path.get(1)).containsEntry("id", folderAId).containsEntry("name", "folderA");
    Assertions.assertThat(path.get(2)).containsEntry("id", folderBId).containsEntry("name", "folderB");
    Assertions.assertThat(path.get(3)).containsEntry("id", fileCId).containsEntry("name", "fileC");
  }

  @Test
  void givenASharedSubtreeGetPathShouldStartAtTheHighestSharedAncestorNotAtRoot() {
    // Given — a 3-level chain owned by OTHER_USER_ID; only folderB and the leaf are individually
    // shared with the requester (direct createShare calls on each, no cascade from folderA)
    String folderAId = seedFolder("folderA", LOCAL_ROOT, OTHER_COOKIE);
    String folderBId = seedFolder("folderB", folderAId, OTHER_COOKIE);
    String fileCId = seedFile("fileC.txt", folderBId, "c".getBytes(StandardCharsets.UTF_8), OTHER_COOKIE);
    seedShare(folderBId, REQUESTER_ID, ACL.SharePermission.READ_ONLY, OTHER_COOKIE);
    seedShare(fileCId, REQUESTER_ID, ACL.SharePermission.READ_ONLY, OTHER_COOKIE);

    // When
    List<Map<String, Object>> path = getPath(fileCId, REQUESTER_COOKIE);

    // Then — starts at folderB (the highest node with an explicit share), NOT at LOCAL_ROOT nor
    // at the unshared folderA
    Assertions.assertThat(path).hasSize(2);
    Assertions.assertThat(path.get(0)).containsEntry("id", folderBId).containsEntry("name", "folderB");
    Assertions.assertThat(path.get(1)).containsEntry("id", fileCId).containsEntry("name", "fileC");
  }

  /**
   * See the class-level FINDING: this is the "inconsistent share" edge — a leaf shared directly
   * with no share anywhere on its ancestor chain. It does NOT throw; it silently returns a
   * one-element path containing only the leaf itself.
   */
  @Test
  void givenALeafSharedWithNoAncestorShareGetPathSilentlyReturnsOnlyTheLeafItself() {
    // Given — 3-level chain owned by OTHER_USER_ID; ONLY the leaf is shared, no ancestor is
    String folderAId = seedFolder("folderA", LOCAL_ROOT, OTHER_COOKIE);
    String folderBId = seedFolder("folderB", folderAId, OTHER_COOKIE);
    String fileCId = seedFile("fileC.txt", folderBId, "c".getBytes(StandardCharsets.UTF_8), OTHER_COOKIE);
    seedShare(fileCId, REQUESTER_ID, ACL.SharePermission.READ_ONLY, OTHER_COOKIE);

    // When
    Response response = getPathRaw(fileCId, REQUESTER_COOKIE);

    // Then — silently degrades to a singleton path; no ancestors, no error
    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    List<Map<String, Object>> path = TestUtils.jsonResponseToList(response.getBody().asString(), "getPath");
    Assertions.assertThat(path).hasSize(1);
    Assertions.assertThat(path.get(0)).containsEntry("id", fileCId).containsEntry("name", "fileC");
    Assertions.assertThat(TestUtils.jsonResponseToErrors(response.getBody().asString())).isEmpty();
  }

  @Test
  void givenNoRelationshipToTheRequestedNodeGetPathShouldReturnNodeNotFound() {
    // Given — a node owned by OTHER_USER_ID, never shared with the requester at all
    String fileDId = seedFile("fileD.txt", LOCAL_ROOT, "d".getBytes(StandardCharsets.UTF_8), OTHER_COOKIE);

    // When
    Response response = getPathRaw(fileDId, REQUESTER_COOKIE);

    // Then
    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    List<String> errors = TestUtils.jsonResponseToErrors(response.getBody().asString());
    Assertions.assertThat(errors).hasSize(1).containsExactly("Could not find node with id " + fileDId);
  }
}
