// SPDX-FileCopyrightText: 2024 Zextras <https://www.zextras.com>
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
 * {@code com.zextras.carbonio.files.acceptance.FindNodesApiIT} rewritten as an out-of-process
 * {@code @QuarkusIntegrationTest} on {@link AbstractFilesIT}. All 12 methods and their assertions
 * are preserved verbatim from the seam-based original; only the seeding mechanism changed (API
 * calls capturing server-generated ids instead of {@code DatabasePopulator} repository writes with
 * fixed-id literals) and the transport (RestAssured against the launched out-of-process app).
 *
 * <p>The original's {@code @Nested}/{@code @TestInstance(PER_CLASS)} sharing of one seeded fixture
 * across several {@code @Test} methods within a nested class cannot be preserved: {@link
 * AbstractFilesIT#resetDb()} runs {@code @AfterEach}, after EVERY test method, so each test below
 * re-seeds its own fixture instead (flattened, no nested classes) — the scenarios/assertions are
 * unchanged, only the seeding is now per-test rather than shared-per-nested-class.
 */
class FindNodesApiIT extends AbstractFilesIT {

  private static final String REQUESTER_ID = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
  private static final String OTHER_USER_ID = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";
  private static final String REQUESTER_COOKIE = "ZM_AUTH_TOKEN=fake-token";
  private static final String OTHER_COOKIE = "ZM_AUTH_TOKEN=fake-token-b";

  @BeforeAll
  static void registerUsers() {
    FilesStackTestResource.getUserManagementService().registerToken("fake-token", REQUESTER_ID);
    FilesStackTestResource.getUserManagementService().registerToken("fake-token-b", OTHER_USER_ID);
  }

  @SuppressWarnings("unchecked")
  private List<Map<String, Object>> findNodes(
      String folderId,
      boolean cascade,
      String sort,
      int limit,
      boolean flagged,
      boolean sharedByMe,
      boolean sharedWithMe,
      boolean directShare,
      String[] keywords,
      String cookie) {
    GraphqlCommandBuilder builder =
        GraphqlCommandBuilder.aQueryBuilder("findNodes")
            .withString("folder_id", folderId)
            .withBoolean("cascade", cascade)
            .withEnumLiteral("sort", sort)
            .withInteger("limit", limit);
    if (flagged) {
      builder.withBoolean("flagged", true);
    }
    if (sharedByMe) {
      builder.withBoolean("shared_by_me", true);
    }
    if (sharedWithMe) {
      builder.withBoolean("shared_with_me", true);
    }
    if (directShare) {
      builder.withBoolean("direct_share", true);
    }
    if (keywords != null) {
      builder.withListOfStrings("keywords", keywords);
    }
    String bodyPayload =
        builder.withWantedResultFormat("{ nodes { id name }, page_token }").build();

    Response response = graphql(bodyPayload, cookie);
    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    Map<String, Object> page =
        TestUtils.jsonResponseToMap(response.getBody().asString(), "findNodes");
    return (List<Map<String, Object>>) page.get("nodes");
  }

  private List<Map<String, Object>> findNodesOnRoot(String sort, int limit) {
    return findNodes(
        "LOCAL_ROOT", true, sort, limit, false, false, false, false, null, REQUESTER_COOKIE);
  }

  /** Seeds folderA, folderB, aaa.txt, bbb.txt, ccc.txt (in that order) and returns their ids. */
  private String[] seedFiveMixedNodes() {
    String folderAId = seedFolder("folderA", LOCAL_ROOT, REQUESTER_COOKIE);
    tickClock();
    String folderBId = seedFolder("folderB", LOCAL_ROOT, REQUESTER_COOKIE);
    tickClock();
    String aaaId =
        seedFile("aaa.txt", LOCAL_ROOT, "a".getBytes(StandardCharsets.UTF_8), REQUESTER_COOKIE);
    tickClock();
    String bbbId =
        seedFile("bbb.txt", LOCAL_ROOT, "b".getBytes(StandardCharsets.UTF_8), REQUESTER_COOKIE);
    tickClock();
    String cccId =
        seedFile("ccc.txt", LOCAL_ROOT, "c".getBytes(StandardCharsets.UTF_8), REQUESTER_COOKIE);
    return new String[] {folderAId, folderBId, aaaId, bbbId, cccId};
  }

  @Test
  void givenFilesOnRootSearchWithSortNameAscShouldReturnCorrectlySortedNodes() {
    String[] ids = seedFiveMixedNodes();

    List<Map<String, Object>> nodes = findNodesOnRoot("NAME_ASC", 5);

    Assertions.assertThat(nodes).hasSize(5);
    Assertions.assertThat(nodes.get(0))
        .containsEntry("id", ids[0])
        .containsEntry("name", "folderA");
    Assertions.assertThat(nodes.get(1))
        .containsEntry("id", ids[1])
        .containsEntry("name", "folderB");
    Assertions.assertThat(nodes.get(2)).containsEntry("id", ids[2]).containsEntry("name", "aaa");
    Assertions.assertThat(nodes.get(3)).containsEntry("id", ids[3]).containsEntry("name", "bbb");
    Assertions.assertThat(nodes.get(4)).containsEntry("id", ids[4]).containsEntry("name", "ccc");
  }

  @Test
  void givenFilesOnRootSearchWithSortNameDescShouldReturnCorrectlySortedNodes() {
    String[] ids = seedFiveMixedNodes();

    List<Map<String, Object>> nodes = findNodesOnRoot("NAME_DESC", 5);

    Assertions.assertThat(nodes).hasSize(5);
    Assertions.assertThat(nodes.get(1))
        .containsEntry("id", ids[0])
        .containsEntry("name", "folderA");
    Assertions.assertThat(nodes.get(0))
        .containsEntry("id", ids[1])
        .containsEntry("name", "folderB");
    Assertions.assertThat(nodes.get(4)).containsEntry("id", ids[2]).containsEntry("name", "aaa");
    Assertions.assertThat(nodes.get(3)).containsEntry("id", ids[3]).containsEntry("name", "bbb");
    Assertions.assertThat(nodes.get(2)).containsEntry("id", ids[4]).containsEntry("name", "ccc");
  }

  @Test
  void givenFilesOnRootSearchWithSortLastUpdateAscShouldReturnCorrectlySortedNodes() {
    String[] ids = seedFiveMixedNodes();

    List<Map<String, Object>> nodes = findNodesOnRoot("UPDATED_AT_ASC", 5);

    Assertions.assertThat(nodes).hasSize(5);
    Assertions.assertThat(nodes.get(0))
        .containsEntry("id", ids[0])
        .containsEntry("name", "folderA");
    Assertions.assertThat(nodes.get(1))
        .containsEntry("id", ids[1])
        .containsEntry("name", "folderB");
    Assertions.assertThat(nodes.get(2)).containsEntry("id", ids[2]).containsEntry("name", "aaa");
    Assertions.assertThat(nodes.get(3)).containsEntry("id", ids[3]).containsEntry("name", "bbb");
    Assertions.assertThat(nodes.get(4)).containsEntry("id", ids[4]).containsEntry("name", "ccc");
  }

  @Test
  void givenFilesOnRootSearchWithSortLastUpdateDescShouldReturnCorrectlySortedNodes() {
    String[] ids = seedFiveMixedNodes();

    List<Map<String, Object>> nodes = findNodesOnRoot("UPDATED_AT_DESC", 5);

    Assertions.assertThat(nodes).hasSize(5);
    Assertions.assertThat(nodes.get(1))
        .containsEntry("id", ids[0])
        .containsEntry("name", "folderA");
    Assertions.assertThat(nodes.get(0))
        .containsEntry("id", ids[1])
        .containsEntry("name", "folderB");
    Assertions.assertThat(nodes.get(4)).containsEntry("id", ids[2]).containsEntry("name", "aaa");
    Assertions.assertThat(nodes.get(3)).containsEntry("id", ids[3]).containsEntry("name", "bbb");
    Assertions.assertThat(nodes.get(2)).containsEntry("id", ids[4]).containsEntry("name", "ccc");
  }

  @Test
  void givenFilesOnRootSearchByKeywordsShouldReturnCorrectNodes() {
    String[] ids = seedFiveMixedNodes();

    List<Map<String, Object>> nodes =
        findNodes(
            "LOCAL_ROOT",
            true,
            "NAME_ASC",
            5,
            false,
            false,
            false,
            false,
            new String[] {"a"},
            REQUESTER_COOKIE);

    Assertions.assertThat(nodes).hasSize(2);
    Assertions.assertThat(nodes.get(0))
        .containsEntry("id", ids[0])
        .containsEntry("name", "folderA");
    Assertions.assertThat(nodes.get(1)).containsEntry("id", ids[2]).containsEntry("name", "aaa");
  }

  /** Seeds 2 folders (size 0) and 3 files of size 0/1/2 bytes and returns their ids. */
  private String[] seedNodesWithDistinctSizes() {
    String folder1Id = seedFolder("folder", LOCAL_ROOT, REQUESTER_COOKIE);
    tickClock();
    String folder2Id = seedFolder("folder", LOCAL_ROOT, REQUESTER_COOKIE);
    tickClock();
    String file0Id = seedFile("fake.txt", LOCAL_ROOT, new byte[0], REQUESTER_COOKIE);
    tickClock();
    String file1Id = seedFile("fake.txt", LOCAL_ROOT, new byte[1], REQUESTER_COOKIE);
    tickClock();
    String file2Id = seedFile("fake.txt", LOCAL_ROOT, new byte[2], REQUESTER_COOKIE);
    return new String[] {folder1Id, folder2Id, file0Id, file1Id, file2Id};
  }

  @Test
  void givenFilesOnRootSearchWithSortSizeAscShouldReturnCorrectlySortedNodes() {
    String[] ids = seedNodesWithDistinctSizes();

    List<Map<String, Object>> nodes = findNodesOnRoot("SIZE_ASC", 5);

    // Note: unlike the old raw-DB backdoor (which wrote the literal "folder"/"fake" name to every
    // row with no dedup pass), the real createFolder/upload API enforces name-uniqueness among
    // siblings, so same-named siblings created here pick up the real "(1)"/"(2)" dedup suffix.
    Assertions.assertThat(nodes).hasSize(5);
    Assertions.assertThat(nodes.get(0)).containsEntry("id", ids[0]).containsEntry("name", "folder");
    Assertions.assertThat(nodes.get(1))
        .containsEntry("id", ids[1])
        .containsEntry("name", "folder (1)");
    Assertions.assertThat(nodes.get(2)).containsEntry("id", ids[2]).containsEntry("name", "fake");
    Assertions.assertThat(nodes.get(3))
        .containsEntry("id", ids[3])
        .containsEntry("name", "fake (1)");
    Assertions.assertThat(nodes.get(4))
        .containsEntry("id", ids[4])
        .containsEntry("name", "fake (2)");
  }

  @Test
  void givenFilesOnRootSearchWithSortSizeDescShouldReturnCorrectlySortedNodes() {
    String[] ids = seedNodesWithDistinctSizes();

    List<Map<String, Object>> nodes = findNodesOnRoot("SIZE_DESC", 5);

    Assertions.assertThat(nodes).hasSize(5);
    Assertions.assertThat(nodes.get(3)).containsEntry("id", ids[0]).containsEntry("name", "folder");
    Assertions.assertThat(nodes.get(4))
        .containsEntry("id", ids[1])
        .containsEntry("name", "folder (1)");
    Assertions.assertThat(nodes.get(2)).containsEntry("id", ids[2]).containsEntry("name", "fake");
    Assertions.assertThat(nodes.get(1))
        .containsEntry("id", ids[3])
        .containsEntry("name", "fake (1)");
    Assertions.assertThat(nodes.get(0))
        .containsEntry("id", ids[4])
        .containsEntry("name", "fake (2)");
  }

  @Test
  void givenFilesOnRootSearchWithFlaggedShouldReturnFlaggedNodes() {
    // Given
    String flaggedId =
        seedFile(
            "flagged.txt",
            LOCAL_ROOT,
            "flagged".getBytes(StandardCharsets.UTF_8),
            REQUESTER_COOKIE);
    seedFlag(flaggedId, REQUESTER_COOKIE);
    seedFile(
        "not_flagged.txt",
        LOCAL_ROOT,
        "notflagged".getBytes(StandardCharsets.UTF_8),
        REQUESTER_COOKIE);

    // When
    List<Map<String, Object>> nodes =
        findNodes(
            "LOCAL_ROOT", true, "NAME_ASC", 5, true, false, false, false, null, REQUESTER_COOKIE);

    // Then
    Assertions.assertThat(nodes).hasSize(1);
    Assertions.assertThat(nodes.get(0))
        .containsEntry("id", flaggedId)
        .containsEntry("name", "flagged");
  }

  @Test
  void givenFilesOnRootSearchSharedByMeShouldReturnSharedByMeNodes() {
    // Given
    String sharedId =
        seedFile(
            "shared_by_me.txt",
            LOCAL_ROOT,
            "shared".getBytes(StandardCharsets.UTF_8),
            REQUESTER_COOKIE);
    seedShare(sharedId, OTHER_USER_ID, ACL.SharePermission.READ_AND_SHARE, REQUESTER_COOKIE);
    seedFile(
        "not_shared_by_me.txt",
        LOCAL_ROOT,
        "notshared".getBytes(StandardCharsets.UTF_8),
        REQUESTER_COOKIE);

    // When
    List<Map<String, Object>> nodes =
        findNodes(
            "LOCAL_ROOT", true, "NAME_ASC", 5, false, true, false, true, null, REQUESTER_COOKIE);

    // Then
    Assertions.assertThat(nodes).hasSize(1);
    Assertions.assertThat(nodes.get(0))
        .containsEntry("id", sharedId)
        .containsEntry("name", "shared_by_me");
  }

  @Test
  void givenFilesOnRootSearchSharedWithMeShouldReturnSharedWithMeNodes()
      throws java.sql.SQLException {
    // Given — file owned by the requester, shared TO the requester itself (mirrors the original
    // seam fixture verbatim: addShare(nodeId, REQUESTER_ID, ...) on a node the requester itself
    // owns), plus a second, un-shared file. NOTE: the real createShare mutation explicitly REJECTS
    // target==owner (ShareDataFetcher#createShareFetcher's shareCreationError guard), so this
    // self-share pre-state is not API-creatable and is seeded via the raw-JDBC escape hatch.
    String sharedId =
        seedFile(
            "shared_with_me.txt",
            LOCAL_ROOT,
            "shared".getBytes(StandardCharsets.UTF_8),
            REQUESTER_COOKIE);
    seedShareRawJdbc(sharedId, REQUESTER_ID, ACL.SharePermission.READ_AND_SHARE);
    seedFile(
        "not_shared_with_me.txt",
        LOCAL_ROOT,
        "notshared".getBytes(StandardCharsets.UTF_8),
        REQUESTER_COOKIE);

    // When
    List<Map<String, Object>> nodes =
        findNodes(
            "LOCAL_ROOT", true, "NAME_ASC", 5, false, false, true, true, null, REQUESTER_COOKIE);

    // Then
    Assertions.assertThat(nodes).hasSize(1);
    Assertions.assertThat(nodes.get(0))
        .containsEntry("id", sharedId)
        .containsEntry("name", "shared_with_me");
  }

  @Test
  void givenFilesOnTrashSearchTrashBinShouldReturnTrashedNodes() {
    // Given
    String trashedId =
        seedFile(
            "trashed.txt",
            LOCAL_ROOT,
            "trashed".getBytes(StandardCharsets.UTF_8),
            REQUESTER_COOKIE);
    seedTrashed(trashedId, REQUESTER_COOKIE);
    seedFile(
        "not_trashed.txt",
        LOCAL_ROOT,
        "nottrashed".getBytes(StandardCharsets.UTF_8),
        REQUESTER_COOKIE);

    // When
    List<Map<String, Object>> nodes =
        findNodes(
            "TRASH_ROOT", false, "NAME_ASC", 5, false, false, false, false, null, REQUESTER_COOKIE);

    // Then
    Assertions.assertThat(nodes).hasSize(1);
    Assertions.assertThat(nodes.get(0))
        .containsEntry("id", trashedId)
        .containsEntry("name", "trashed");
  }

  @Test
  void givenExistingFilesTrashSearchSharedWithMeInTrashBinShouldReturnSharedTrashedNodes()
      throws java.sql.SQLException {
    // Given — same self-share pre-state as the sibling test above (target==owner, raw-JDBC only).
    String trashedId =
        seedFile(
            "trashed.txt",
            LOCAL_ROOT,
            "trashed".getBytes(StandardCharsets.UTF_8),
            REQUESTER_COOKIE);
    seedShareRawJdbc(trashedId, REQUESTER_ID, ACL.SharePermission.READ_AND_SHARE);
    seedTrashed(trashedId, REQUESTER_COOKIE);
    seedFile(
        "not_trashed.txt",
        LOCAL_ROOT,
        "nottrashed".getBytes(StandardCharsets.UTF_8),
        REQUESTER_COOKIE);

    // When
    List<Map<String, Object>> nodes =
        findNodes(
            "TRASH_ROOT", false, "NAME_ASC", 5, false, false, true, false, null, REQUESTER_COOKIE);

    // Then
    Assertions.assertThat(nodes).hasSize(1);
    Assertions.assertThat(nodes.get(0))
        .containsEntry("id", trashedId)
        .containsEntry("name", "trashed");
  }
}
