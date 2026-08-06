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
 * Phase 1 pilot: {@code com.zextras.carbonio.files.acceptance.CreateFolderApiIT} rewritten as an
 * out-of-process {@code @QuarkusIntegrationTest} on the new {@link AbstractFilesIT} base. Direct
 * HTTP coverage of {@code createFolder}: happy path (including owner inheritance in a shared
 * parent), name-collision dedup, permission-denied, and parent-not-found/wrong-type.
 *
 * <p>All 6 methods and their assertions are preserved verbatim from the seam-based original; only
 * the SEEDING mechanism changed (API calls capturing server-generated ids instead of {@code
 * DatabasePopulator} repository writes) and the transport (RestAssured against the launched
 * out-of-process app instead of the {@code FilesTestApp} seam over the in-process
 * {@code @QuarkusTest} app).
 *
 * <p><b>FINDING (preserved from the original, still true):</b> {@code createFolderFetcher} checks
 * {@code permissionsChecker.getPermissions(parentId, requesterId).has(READ_AND_WRITE)} BEFORE it
 * ever calls {@code nodeRepository.getNode(parentId)}. {@code PermissionsChecker#getPermissions}
 * returns {@code ACL.NONE} for ANY non-existent node id, which always fails the {@code
 * READ_AND_WRITE} check. So a genuinely non-existent {@code destination_id} is rejected by the
 * PERMISSION branch and returns {@code nodeWriteError}, NOT {@code nodeNotFound} — the {@code
 * nodeNotFound} message is only reachable for the DIFFERENT "wrong type" scenario (parent exists,
 * requester already has write permission on it, but it is a {@code FILE} rather than a {@code
 * FOLDER}/{@code ROOT}). These two scenarios are asserted separately below with their real,
 * different error shapes.
 */
class CreateFolderApiIT extends AbstractFilesIT {

  private static final String REQUESTER_ID = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
  private static final String OTHER_USER_ID = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";
  private static final String REQUESTER_COOKIE = "ZM_AUTH_TOKEN=fake-token";
  private static final String OTHER_COOKIE = "ZM_AUTH_TOKEN=fake-token-b";

  @BeforeAll
  static void registerUsers() {
    FilesStackTestResource.getUserManagementService().registerToken("fake-token", REQUESTER_ID);
    FilesStackTestResource.getUserManagementService().registerToken("fake-token-b", OTHER_USER_ID);
  }

  private Response createFolder(String destinationId, String name, String cookie) {
    String bodyPayload =
        GraphqlCommandBuilder.aMutationBuilder("createFolder")
            .withString("destination_id", destinationId)
            .withString("name", name)
            .withWantedResultFormat("{ id name owner { id } }")
            .build();
    return graphql(bodyPayload, cookie);
  }

  @Test
  void givenValidDestinationCreateFolderShouldReturnTheNewFolder() {
    // When
    Response response = createFolder(LOCAL_ROOT, "newFolder", REQUESTER_COOKIE);

    // Then
    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    Map<String, Object> folder =
        TestUtils.jsonResponseToMap(response.getBody().asString(), "createFolder");
    Assertions.assertThat(folder).containsEntry("name", "newFolder");
    Assertions.assertThat(((Map<String, Object>) folder.get("owner")))
        .containsEntry("id", REQUESTER_ID);
    Assertions.assertThat(nodeExists((String) folder.get("id"), REQUESTER_COOKIE)).isTrue();
  }

  @Test
  void givenParentFolderOwnedBySomeoneElseCreateFolderShouldInheritTheParentOwner() {
    // Given — parent folder owned by OTHER_USER_ID, shared with the requester with write access
    String sharedParentId = seedFolder("sharedParent", LOCAL_ROOT, OTHER_COOKIE);
    seedShare(sharedParentId, REQUESTER_ID, ACL.SharePermission.READ_AND_WRITE, OTHER_COOKIE);

    // When
    Response response = createFolder(sharedParentId, "childFolder", REQUESTER_COOKIE);

    // Then — the new folder's owner is the PARENT's owner, not the requester who created it
    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    Map<String, Object> folder =
        TestUtils.jsonResponseToMap(response.getBody().asString(), "createFolder");
    Assertions.assertThat(((Map<String, Object>) folder.get("owner")))
        .containsEntry("id", OTHER_USER_ID);
  }

  @Test
  void givenADuplicateNameCreateFolderShouldDedupTheName() {
    // Given
    seedFolder("sameName", LOCAL_ROOT, REQUESTER_COOKIE);

    // When
    Response response = createFolder(LOCAL_ROOT, "sameName", REQUESTER_COOKIE);

    // Then
    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    Map<String, Object> folder =
        TestUtils.jsonResponseToMap(response.getBody().asString(), "createFolder");
    Assertions.assertThat(folder).containsEntry("name", "sameName (1)");
  }

  @Test
  void givenNoWritePermissionOnParentCreateFolderShouldReturnNodeWriteError() {
    // Given — parent folder owned by OTHER_USER_ID, no share at all with the requester
    String privateParentId = seedFolder("privateParent", LOCAL_ROOT, OTHER_COOKIE);

    // When
    Response response = createFolder(privateParentId, "childFolder", REQUESTER_COOKIE);

    // Then
    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    List<String> errors = TestUtils.jsonResponseToErrors(response.getBody().asString());
    Assertions.assertThat(errors)
        .hasSize(1)
        .containsExactly(
            "There was a problem while executing requested operation on node: " + privateParentId);
  }

  /**
   * See the class-level FINDING: a non-existent parent fails the READ_AND_WRITE permission gate
   * (ACL.NONE for a missing node) BEFORE the not-found filter is ever reached, so the real error is
   * {@code nodeWriteError}, not {@code nodeNotFound}. No seeding: this id is deliberately
   * syntactically-valid (36-char) but non-existent, not a captured id.
   */
  @Test
  void givenNonExistentParentCreateFolderShouldReturnNodeWriteErrorNotNodeNotFound() {
    // Given
    String nonExistentParentId = "10000000-0000-0000-0000-00000000ffff";

    // When
    Response response = createFolder(nonExistentParentId, "childFolder", REQUESTER_COOKIE);

    // Then
    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    List<String> errors = TestUtils.jsonResponseToErrors(response.getBody().asString());
    Assertions.assertThat(errors)
        .hasSize(1)
        .containsExactly(
            "There was a problem while executing requested operation on node: "
                + nonExistentParentId);
  }

  @Test
  void givenParentIsAFileNotAFolderCreateFolderShouldReturnNodeNotFound() {
    // Given
    String fileId =
        seedFile(
            "aFile.txt",
            LOCAL_ROOT,
            "aFile content".getBytes(StandardCharsets.UTF_8),
            REQUESTER_COOKIE);

    // When
    Response response = createFolder(fileId, "childFolder", REQUESTER_COOKIE);

    // Then — the parent exists, but createFolder filters it out for having the wrong node type
    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    List<String> errors = TestUtils.jsonResponseToErrors(response.getBody().asString());
    Assertions.assertThat(errors)
        .hasSize(1)
        .containsExactly("Could not find node with id " + fileId);
  }
}
