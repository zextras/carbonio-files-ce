// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.acceptance;

import com.zextras.carbonio.files.FilesStackTestResource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;

import com.zextras.carbonio.files.TestUtils;
import com.zextras.carbonio.files.acceptance.seam.FilesTestApp;
import com.zextras.carbonio.files.acceptance.seam.impl.QuarkusFilesTestAppBuilder;
import com.zextras.carbonio.files.api.utilities.GraphqlCommandBuilder;
import com.zextras.carbonio.files.api.utilities.entities.SimplePopulatorFolder;
import com.zextras.carbonio.files.api.utilities.entities.SimplePopulatorTextFile;
import com.zextras.carbonio.files.dal.dao.ebean.ACL;
import com.zextras.carbonio.files.utilities.http.HttpRequest;
import com.zextras.carbonio.files.utilities.http.HttpResponse;
import java.util.List;
import java.util.Map;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Task 1.5 (part 1) of the acceptance coverage-expansion plan: direct HTTP coverage of {@code
 * createFolder}, which so far has only been exercised indirectly as a notification trigger (see
 * {@code AddedNodeNotificationCreateApiIT}). Covers happy path (including owner inheritance in a
 * shared parent), name-collision dedup, permission-denied, and parent-not-found/wrong-type.
 *
 * <p><b>FINDING (overrides the plan's combined "not-found/wrong-type" prediction):</b> {@code
 * createFolderFetcher} checks {@code permissionsChecker.getPermissions(parentId,
 * requesterId).has(READ_AND_WRITE)} BEFORE it ever calls {@code nodeRepository.getNode(parentId)}.
 * {@code PermissionsChecker#getPermissions} returns {@code ACL.NONE} for ANY non-existent node id
 * (its {@code Optional<Node>} lookup is empty, so it falls through to {@code
 * ACL.decode(ACL.NONE)}), which always fails the {@code READ_AND_WRITE} check. So a genuinely
 * non-existent {@code destination_id} is rejected by the PERMISSION branch and returns {@code
 * nodeWriteError}, NOT {@code nodeNotFound} — the plan's predicted {@code nodeNotFound} message is
 * only reachable for the DIFFERENT "wrong type" scenario (parent exists, requester already has
 * write permission on it, but it is a {@code FILE} rather than a {@code FOLDER}/{@code ROOT}).
 * These two scenarios are asserted separately below with their real, different error shapes.
 */
@QuarkusTest
@QuarkusTestResource(FilesStackTestResource.class)
class CreateFolderApiIT {

  static FilesTestApp app;

  private static final String REQUESTER_ID = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
  private static final String OTHER_USER_ID = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";

  @BeforeAll
  static void init() {
    app =
        QuarkusFilesTestAppBuilder.aFilesTestApp()
            .withDatabase()
            .withServiceDiscover()
            .withUserManagement(
                Map.of(
                    "fake-token", REQUESTER_ID,
                    "fake-token-b", OTHER_USER_ID))
            .build();
  }

  @AfterEach
  void cleanUp() {
    app.backdoor().resetDatabase();
  }

  @AfterAll
  static void cleanUpAll() {
    app.close();
  }

  private HttpResponse createFolder(String destinationId, String name, String cookie) {
    String bodyPayload =
        GraphqlCommandBuilder.aMutationBuilder("createFolder")
            .withString("destination_id", destinationId)
            .withString("name", name)
            .withWantedResultFormat("{ id name owner { id } }")
            .build();
    HttpRequest httpRequest = HttpRequest.of("POST", "/graphql/", cookie, bodyPayload);
    return app.send(httpRequest);
  }

  @Test
  void givenValidDestinationCreateFolderShouldReturnTheNewFolder() {
    // When
    HttpResponse httpResponse = createFolder("LOCAL_ROOT", "newFolder", "ZM_AUTH_TOKEN=fake-token");

    // Then
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(200);
    Map<String, Object> folder = TestUtils.jsonResponseToMap(httpResponse.getBodyPayload(), "createFolder");
    Assertions.assertThat(folder).containsEntry("name", "newFolder");
    Assertions.assertThat(((Map<String, Object>) folder.get("owner"))).containsEntry("id", REQUESTER_ID);
    Assertions.assertThat(app.backdoor().nodeExists((String) folder.get("id"))).isTrue();
  }

  @Test
  void givenParentFolderOwnedBySomeoneElseCreateFolderShouldInheritTheParentOwner() {
    // Given — parent folder owned by OTHER_USER_ID, shared with the requester with write access
    app.backdoor()
        .populator()
        .addNode(new SimplePopulatorFolder("10000000-0000-0000-0000-000000000001", OTHER_USER_ID, "sharedParent"))
        .addShare(
            "10000000-0000-0000-0000-000000000001", REQUESTER_ID, ACL.SharePermission.READ_AND_WRITE);

    // When
    HttpResponse httpResponse =
        createFolder("10000000-0000-0000-0000-000000000001", "childFolder", "ZM_AUTH_TOKEN=fake-token");

    // Then — the new folder's owner is the PARENT's owner, not the requester who created it
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(200);
    Map<String, Object> folder = TestUtils.jsonResponseToMap(httpResponse.getBodyPayload(), "createFolder");
    Assertions.assertThat(((Map<String, Object>) folder.get("owner"))).containsEntry("id", OTHER_USER_ID);
  }

  @Test
  void givenADuplicateNameCreateFolderShouldDedupTheName() {
    // Given
    app.backdoor()
        .populator()
        .addNode(new SimplePopulatorFolder("10000000-0000-0000-0000-000000000001", REQUESTER_ID, "sameName"));

    // When
    HttpResponse httpResponse = createFolder("LOCAL_ROOT", "sameName", "ZM_AUTH_TOKEN=fake-token");

    // Then
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(200);
    Map<String, Object> folder = TestUtils.jsonResponseToMap(httpResponse.getBodyPayload(), "createFolder");
    Assertions.assertThat(folder).containsEntry("name", "sameName (1)");
  }

  @Test
  void givenNoWritePermissionOnParentCreateFolderShouldReturnNodeWriteError() {
    // Given — parent folder owned by OTHER_USER_ID, no share at all with the requester
    app.backdoor()
        .populator()
        .addNode(new SimplePopulatorFolder("10000000-0000-0000-0000-000000000001", OTHER_USER_ID, "privateParent"));

    // When
    HttpResponse httpResponse =
        createFolder("10000000-0000-0000-0000-000000000001", "childFolder", "ZM_AUTH_TOKEN=fake-token");

    // Then
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(200);
    List<String> errors = TestUtils.jsonResponseToErrors(httpResponse.getBodyPayload());
    Assertions.assertThat(errors)
        .hasSize(1)
        .containsExactly(
            "There was a problem while executing requested operation on node: "
                + "10000000-0000-0000-0000-000000000001");
  }

  /**
   * See the class-level FINDING: a non-existent parent fails the READ_AND_WRITE permission gate
   * (ACL.NONE for a missing node) BEFORE the not-found filter is ever reached, so the real error
   * is {@code nodeWriteError}, not {@code nodeNotFound}.
   */
  @Test
  void givenNonExistentParentCreateFolderShouldReturnNodeWriteErrorNotNodeNotFound() {
    // Given — a syntactically-valid (36-char) but non-existent parent id
    String nonExistentParentId = "10000000-0000-0000-0000-00000000ffff";

    // When
    HttpResponse httpResponse = createFolder(nonExistentParentId, "childFolder", "ZM_AUTH_TOKEN=fake-token");

    // Then
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(200);
    List<String> errors = TestUtils.jsonResponseToErrors(httpResponse.getBodyPayload());
    Assertions.assertThat(errors)
        .hasSize(1)
        .containsExactly(
            "There was a problem while executing requested operation on node: " + nonExistentParentId);
  }

  @Test
  void givenParentIsAFileNotAFolderCreateFolderShouldReturnNodeNotFound() {
    // Given
    app.backdoor()
        .populator()
        .addNode(new SimplePopulatorTextFile("00000000-0000-0000-0000-000000000001", REQUESTER_ID, "aFile.txt"));

    // When
    HttpResponse httpResponse =
        createFolder("00000000-0000-0000-0000-000000000001", "childFolder", "ZM_AUTH_TOKEN=fake-token");

    // Then — the parent exists, but createFolder filters it out for having the wrong node type
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(200);
    List<String> errors = TestUtils.jsonResponseToErrors(httpResponse.getBodyPayload());
    Assertions.assertThat(errors)
        .hasSize(1)
        .containsExactly("Could not find node with id 00000000-0000-0000-0000-000000000001");
  }
}
