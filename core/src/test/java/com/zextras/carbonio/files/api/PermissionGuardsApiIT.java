// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zextras.carbonio.files.Simulator;
import com.zextras.carbonio.files.Simulator.SimulatorBuilder;
import com.zextras.carbonio.files.TestUtils;
import com.zextras.carbonio.files.api.utilities.DatabasePopulator;
import com.zextras.carbonio.files.api.utilities.GraphqlCommandBuilder;
import com.zextras.carbonio.files.api.utilities.entities.PopulatorNode;
import com.zextras.carbonio.files.api.utilities.entities.SimplePopulatorFolder;
import com.zextras.carbonio.files.api.utilities.entities.SimplePopulatorTextFile;
import com.zextras.carbonio.files.dal.DatabaseManager;
import com.zextras.carbonio.files.dal.dao.ebean.ACL;
import com.zextras.carbonio.files.dal.dao.ebean.ACL.SharePermission;
import com.zextras.carbonio.files.dal.dao.ebean.NodeType;
import com.zextras.carbonio.files.dal.repositories.interfaces.CollaborationLinkRepository;
import com.zextras.carbonio.files.dal.repositories.interfaces.ShareRepository;
import com.zextras.carbonio.files.netty.utilities.BufferInputStream;
import com.zextras.carbonio.files.rest.services.BlobService;
import com.zextras.carbonio.files.utilities.http.HttpRequest;
import com.zextras.carbonio.files.utilities.http.HttpResponse;
import io.ebean.Database;
import io.ebean.SqlRow;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.AbstractMap.SimpleEntry;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockserver.verify.VerificationTimes;

class PermissionGuardsApiIT {

  private static final String LOCAL_ROOT = "LOCAL_ROOT";
  private static final String TRASH_ROOT = "TRASH_ROOT";
  private static final String PADDED_LOCAL_ROOT = String.format("%-36s", "LOCAL_ROOT");
  private static final String A_ID = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
  private static final String B_ID = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";
  private static final String C_ID = "cccccccc-cccc-cccc-cccc-cccccccccccc";
  private static final String A_COOKIE = "ZM_AUTH_TOKEN=fake-token";
  private static final String B_COOKIE = "ZM_AUTH_TOKEN=fake-token-b";
  private static final String C_COOKIE = "ZM_AUTH_TOKEN=fake-token-c";
  private static final String FOLDER_ID = "11111111-1111-1111-1111-111111111111";
  private static final String FILE_ID = "00000000-0000-0000-0000-000000000001";
  private static final String CHILD_ID = "00000000-0000-0000-0000-000000000002";

  private static Simulator simulator;
  private static Database database;
  private static ShareRepository shareRepository;
  private static CollaborationLinkRepository collaborationLinkRepository;

  @BeforeAll
  static void init() {
    simulator =
        SimulatorBuilder.aSimulator()
            .init()
            .withDatabase()
            .withServiceDiscover()
            .withStorages()
            .withUserManagement(
                Map.of("fake-token", A_ID, "fake-token-b", B_ID, "fake-token-c", C_ID))
            .build()
            .start();
    database = simulator.getInjector().getInstance(DatabaseManager.class).getEbeanDatabase();
    shareRepository = simulator.getInjector().getInstance(ShareRepository.class);
    collaborationLinkRepository =
        simulator.getInjector().getInstance(CollaborationLinkRepository.class);
  }

  @BeforeEach
  void ensureRootRows() {
    for (String[] root : new String[][] {{LOCAL_ROOT, "ROOT"}, {TRASH_ROOT, "TRASH"}}) {
      database
          .sqlUpdate(
              "INSERT INTO node (owner_id, node_id, folder_id, name, node_type, node_category,"
                  + " description, index_status, creation_timestamp, updated_timestamp,"
                  + " ancestor_ids, size) VALUES (NULL, ?, NULL, ?, 'ROOT', 0, '', 2,"
                  + " EXTRACT(EPOCH FROM NOW()), EXTRACT(EPOCH FROM NOW()), '', 0)"
                  + " ON CONFLICT DO NOTHING")
          .setParameter(1, root[0])
          .setParameter(2, root[1])
          .execute();
    }
  }

  @BeforeEach
  void clearStorageUploads() {
    simulator
        .getStoragesMock()
        .clear(org.mockserver.model.HttpRequest.request().withPath("/upload"));
  }

  @AfterEach
  void cleanUp() {
    database
        .sqlUpdate(
            "DELETE FROM node WHERE owner_id IS NULL AND node_id NOT IN ('LOCAL_ROOT',"
                + " 'TRASH_ROOT')")
        .execute();
    database.sqlUpdate("DELETE FROM share WHERE node_id IN ('LOCAL_ROOT', 'TRASH_ROOT')").execute();
    database
        .sqlUpdate("DELETE FROM collaboration_link WHERE node_id IN ('LOCAL_ROOT', 'TRASH_ROOT')")
        .execute();
    database.sqlUpdate("UPDATE node SET name = 'ROOT' WHERE node_id = 'LOCAL_ROOT'").execute();
    database.sqlUpdate("UPDATE node SET name = 'TRASH' WHERE node_id = 'TRASH_ROOT'").execute();
    simulator.resetDatabase();
  }

  @AfterAll
  static void cleanUpAll() {
    simulator.stopAll();
  }

  @Test
  void createShareOnLocalRootIsRejected() {
    addFile(FILE_ID, A_ID);

    HttpResponse response = createShare(LOCAL_ROOT, B_ID, SharePermission.READ_ONLY, B_COOKIE);

    assertErrorCodes(response, "SHARE_CREATION_ERROR");
    Assertions.assertThat(shareRow(LOCAL_ROOT, B_ID)).isEmpty();
    Assertions.assertThat(shareRow(FILE_ID, B_ID)).isEmpty();
  }

  @Test
  void createShareOnTrashRootIsRejected() {
    HttpResponse response = createShare(TRASH_ROOT, B_ID, SharePermission.READ_ONLY, B_COOKIE);

    assertErrorCodes(response, "SHARE_CREATION_ERROR");
    Assertions.assertThat(shareRow(TRASH_ROOT, B_ID)).isEmpty();
  }

  @Test
  void createShareTargetingTheRequesterIsRejected() {
    addFolderWithChild();
    assertNoErrors(createShare(FOLDER_ID, B_ID, SharePermission.READ_AND_SHARE, A_COOKIE));

    HttpResponse response =
        createShare(CHILD_ID, B_ID, SharePermission.READ_WRITE_AND_SHARE, B_COOKIE);

    assertErrorCodes(response, "SHARE_CREATION_ERROR");
    Assertions.assertThat(shareRow(CHILD_ID, B_ID))
        .contains(new ShareRow(SharePermission.READ_AND_SHARE.encode(), false));
  }

  @Test
  void createShareAboveTheRequesterPermissionIsRejected() {
    addFile(FILE_ID, A_ID);
    addShare(FILE_ID, B_ID, SharePermission.READ_AND_SHARE);

    HttpResponse response = createShare(FILE_ID, C_ID, SharePermission.READ_AND_WRITE, B_COOKIE);

    assertErrorCodes(response, "SHARE_CREATION_ERROR");
    Assertions.assertThat(shareRow(FILE_ID, C_ID)).isEmpty();
  }

  @Test
  void createShareWithinTheRequesterPermissionSucceeds() {
    addFile(FILE_ID, A_ID);
    addShare(FILE_ID, B_ID, SharePermission.READ_AND_SHARE);

    HttpResponse response = createShare(FILE_ID, C_ID, SharePermission.READ_ONLY, B_COOKIE);

    assertNoErrors(response);
    Assertions.assertThat(shareRow(FILE_ID, C_ID))
        .contains(new ShareRow(SharePermission.READ_ONLY.encode(), true));
  }

  @Test
  void updateSharesRaisingOwnPermissionIsRejected() {
    addFile(FILE_ID, A_ID);
    addShare(FILE_ID, B_ID, SharePermission.READ_AND_SHARE);

    HttpResponse response =
        updateShares(FILE_ID, B_ID, SharePermission.READ_WRITE_AND_SHARE, B_COOKIE);

    assertErrorCodes(response, "SHARE_NOT_FOUND");
    Assertions.assertThat(shareRow(FILE_ID, B_ID))
        .contains(new ShareRow(SharePermission.READ_AND_SHARE.encode(), true));
  }

  @Test
  void updateSharesLoweringOwnPermissionSucceeds() {
    addFile(FILE_ID, A_ID);
    addShare(FILE_ID, B_ID, SharePermission.READ_WRITE_AND_SHARE);

    HttpResponse response = updateShares(FILE_ID, B_ID, SharePermission.READ_ONLY, B_COOKIE);

    assertNoErrors(response);
    Assertions.assertThat(shareRow(FILE_ID, B_ID))
        .contains(new ShareRow(SharePermission.READ_ONLY.encode(), true));
  }

  @Test
  void updateSharesAboveTheRequesterPermissionIsRejected() {
    addFile(FILE_ID, A_ID);
    addShare(FILE_ID, B_ID, SharePermission.READ_AND_SHARE);
    addShare(FILE_ID, C_ID, SharePermission.READ_ONLY);

    HttpResponse response = updateShares(FILE_ID, C_ID, SharePermission.READ_AND_WRITE, B_COOKIE);

    assertErrorCodes(response, "SHARE_NOT_FOUND");
    Assertions.assertThat(shareRow(FILE_ID, C_ID))
        .contains(new ShareRow(SharePermission.READ_ONLY.encode(), true));
  }

  @Test
  void updateSharesOnRootIsRejected() {
    insertRootShare(C_ID);

    HttpResponse response =
        updateShares(LOCAL_ROOT, C_ID, SharePermission.READ_WRITE_AND_SHARE, B_COOKIE);

    assertErrorCodes(response, "SHARE_NOT_FOUND");
    Assertions.assertThat(shareRow(LOCAL_ROOT, C_ID))
        .contains(new ShareRow(SharePermission.READ_ONLY.encode(), true));
  }

  @Test
  void deleteSharesOnRootIsRejected() {
    insertRootShare(C_ID);

    HttpResponse response =
        graphql(
            GraphqlCommandBuilder.aMutationBuilder("deleteShares")
                .withString("node_id", LOCAL_ROOT)
                .withListOfStrings("share_target_ids", new String[] {C_ID})
                .withWantedResultFormat("")
                .build(),
            B_COOKIE);

    assertErrorCodes(response, "SHARE_NOT_FOUND");
    Assertions.assertThat(shareRow(LOCAL_ROOT, C_ID)).isPresent();
  }

  @Test
  void createCollaborationLinkOnRootIsRejected() {
    HttpResponse response =
        graphql(
            GraphqlCommandBuilder.aMutationBuilder("createCollaborationLink")
                .withString("node_id", LOCAL_ROOT)
                .withEnum("permission", SharePermission.READ_ONLY)
                .withWantedResultFormat("{ id }")
                .build(),
            B_COOKIE);

    assertErrorCodes(response, "NODE_WRITE_ERROR");
    Assertions.assertThat(
            count("SELECT count(*) FROM collaboration_link WHERE node_id = ?", LOCAL_ROOT))
        .isZero();
  }

  @Test
  void invitationOfALinkOnRootSharesNothing() {
    addFile(FILE_ID, A_ID);
    collaborationLinkRepository.createLink(
        UUID.randomUUID(), LOCAL_ROOT, "rootlink", SharePermission.READ_ONLY);

    HttpResponse response = rest("GET", "/invite/rootlink", C_COOKIE, List.of(), "");

    Assertions.assertThat(response.getStatus()).isEqualTo(404);
    Assertions.assertThat(shareRow(LOCAL_ROOT, C_ID)).isEmpty();
    Assertions.assertThat(shareRow(FILE_ID, C_ID)).isEmpty();
  }

  @Test
  void updateNodeOnRootIsRejected() {
    HttpResponse response =
        graphql(
            GraphqlCommandBuilder.aMutationBuilder("updateNode")
                .withString("node_id", LOCAL_ROOT)
                .withString("name", "renamed")
                .withWantedResultFormat("{ id name }")
                .build(),
            B_COOKIE);

    assertErrorCodes(response, "NODE_NOT_FOUND");
    Assertions.assertThat(singleString("SELECT name FROM node WHERE node_id = ?", LOCAL_ROOT))
        .isEqualTo("ROOT");
  }

  @Test
  void createFolderInTrashRootIsRejected() {
    HttpResponse response = createFolder(TRASH_ROOT, "f", A_COOKIE);

    assertErrorCodes(response, "NODE_NOT_FOUND");
    Assertions.assertThat(childrenCount(TRASH_ROOT)).isZero();
  }

  @Test
  void moveNodesToTrashRootIsRejected() {
    addFolder(FOLDER_ID, A_ID);

    HttpResponse response = moveNodes(FOLDER_ID, TRASH_ROOT, A_COOKIE);

    assertErrorCodes(response, "NODE_WRITE_ERROR");
    Assertions.assertThat(parentOf(FOLDER_ID)).isEqualTo(LOCAL_ROOT);
  }

  @Test
  void copyNodesToTrashRootIsRejected() {
    addFile(FILE_ID, A_ID);

    HttpResponse response = copyNodes(FILE_ID, TRASH_ROOT, A_COOKIE);

    assertErrorCodes(response, "NODE_WRITE_ERROR");
    Assertions.assertThat(childrenCount(TRASH_ROOT)).isZero();
  }

  @Test
  void copyNodesSkipsARootSource() {
    addFolder(FOLDER_ID, A_ID);

    HttpResponse response = copyNodes(LOCAL_ROOT, FOLDER_ID, A_COOKIE);

    Assertions.assertThat(response.getStatus()).isEqualTo(200);
    Assertions.assertThat(errorCodes(response)).isNotEmpty();
    Assertions.assertThat(childrenCount(FOLDER_ID)).isZero();
  }

  @Test
  void uploadToTrashRootIsRejected() {
    Optional<String> uploaded = upload(TRASH_ROOT);

    Assertions.assertThat(uploaded).isEmpty();
    Assertions.assertThat(childrenCount(TRASH_ROOT)).isZero();
    Assertions.assertThat(
            count(
                "SELECT count(*) FROM node WHERE owner_id IS NULL AND node_id NOT IN (?,"
                    + " 'TRASH_ROOT')",
                LOCAL_ROOT))
        .isZero();
    assertStorageUploads(0);
  }

  @Test
  void uploadIntoAFileIsRejected() {
    addFile(FILE_ID, A_ID);

    Optional<String> uploaded = upload(FILE_ID);

    Assertions.assertThat(uploaded).isEmpty();
    Assertions.assertThat(childrenCount(FILE_ID)).isZero();
    assertStorageUploads(0);
  }

  @Test
  void folderUploadAndMoveIntoLocalRootStillWork() {
    addFolderWithChild();

    HttpResponse folderResponse = createFolder(LOCAL_ROOT, "f", A_COOKIE);
    upload(LOCAL_ROOT);
    upload(FOLDER_ID);
    HttpResponse moveResponse = moveNodes(CHILD_ID, LOCAL_ROOT, A_COOKIE);

    assertNoErrors(folderResponse);
    assertNoErrors(moveResponse);
    Assertions.assertThat(parentOf(CHILD_ID)).isEqualTo(LOCAL_ROOT);
    assertStorageUploads(2);
  }

  @Test
  void sharesOfARootAreNotInherited() {
    insertRootShare(C_ID);
    addFolderWithChild();

    HttpResponse folderResponse = createFolder(LOCAL_ROOT, "f", A_COOKIE);
    HttpResponse moveResponse = moveNodes(CHILD_ID, LOCAL_ROOT, A_COOKIE);

    assertNoErrors(folderResponse);
    assertNoErrors(moveResponse);
    String createdFolderId =
        (String)
            TestUtils.jsonResponseToMap(folderResponse.getBodyPayload(), "createFolder").get("id");
    Assertions.assertThat(shareRow(createdFolderId, C_ID)).isEmpty();
    Assertions.assertThat(shareRow(CHILD_ID, C_ID)).isEmpty();
  }

  @Test
  void downloadMultipleOfTrashRootIsNotAServerError() {
    addFile(FILE_ID, A_ID);

    HttpResponse response =
        rest(
            "POST",
            "/download-multiple",
            A_COOKIE,
            List.of(new SimpleEntry<>("Content-Type", "application/x-www-form-urlencoded")),
            "nodeIds=" + URLEncoder.encode("[\"" + TRASH_ROOT + "\"]", StandardCharsets.UTF_8));

    Assertions.assertThat(response.getStatus()).isLessThan(500);
  }

  @Test
  void createShareOnPaddedLocalRootIsRejected() {
    addFile(FILE_ID, A_ID);

    HttpResponse response =
        createShare(PADDED_LOCAL_ROOT, B_ID, SharePermission.READ_ONLY, B_COOKIE);

    Assertions.assertThat(TestUtils.jsonResponseToErrors(response.getBodyPayload())).isNotEmpty();
    Assertions.assertThat(shareRow(LOCAL_ROOT, B_ID)).isEmpty();
    Assertions.assertThat(shareRow(FILE_ID, B_ID)).isEmpty();
  }

  @Test
  void updateNodeOnPaddedRootIsRejected() {
    HttpResponse response =
        graphql(
            GraphqlCommandBuilder.aMutationBuilder("updateNode")
                .withString("node_id", PADDED_LOCAL_ROOT)
                .withString("name", "renamed")
                .withWantedResultFormat("{ id name }")
                .build(),
            B_COOKIE);

    Assertions.assertThat(TestUtils.jsonResponseToErrors(response.getBodyPayload())).isNotEmpty();
    Assertions.assertThat(singleString("SELECT name FROM node WHERE node_id = ?", LOCAL_ROOT))
        .isEqualTo("ROOT");
  }

  @Test
  void updateSharesLoweringAnotherCollaboratorWithinItsPermissionSucceeds() {
    addFile(FILE_ID, A_ID);
    addShare(FILE_ID, B_ID, SharePermission.READ_AND_SHARE);
    addShare(FILE_ID, C_ID, SharePermission.READ_WRITE_AND_SHARE);

    HttpResponse response = updateShares(FILE_ID, C_ID, SharePermission.READ_AND_WRITE, B_COOKIE);

    assertNoErrors(response);
    Assertions.assertThat(shareRow(FILE_ID, C_ID))
        .contains(new ShareRow(SharePermission.READ_AND_WRITE.encode(), true));
  }

  @Test
  void createCollaborationLinkWithoutShareRightIsRejected() {
    addFile(FILE_ID, A_ID);
    addShare(FILE_ID, B_ID, SharePermission.READ_AND_WRITE);

    HttpResponse response =
        graphql(
            GraphqlCommandBuilder.aMutationBuilder("createCollaborationLink")
                .withString("node_id", FILE_ID)
                .withEnum("permission", SharePermission.READ_AND_WRITE)
                .withWantedResultFormat("{ id }")
                .build(),
            B_COOKIE);

    assertErrorCodes(response, "NODE_WRITE_ERROR");
    Assertions.assertThat(count("SELECT count(*) FROM collaboration_link WHERE node_id = ?", FILE_ID))
        .isZero();
  }

  @Test
  void deleteSharesOfOwnShareOnRootIsRejected() {
    insertRootShare(C_ID);

    HttpResponse response =
        graphql(
            GraphqlCommandBuilder.aMutationBuilder("deleteShares")
                .withString("node_id", LOCAL_ROOT)
                .withListOfStrings("share_target_ids", new String[] {C_ID})
                .withWantedResultFormat("")
                .build(),
            C_COOKIE);

    assertErrorCodes(response, "SHARE_NOT_FOUND");
    Assertions.assertThat(shareRow(LOCAL_ROOT, C_ID)).isPresent();
  }

  private static void addFile(String nodeId, String ownerId) {
    DatabasePopulator.aNodePopulator(simulator.getInjector())
        .addNode(new SimplePopulatorTextFile(nodeId, ownerId));
  }

  private static void addFolder(String nodeId, String ownerId) {
    DatabasePopulator.aNodePopulator(simulator.getInjector())
        .addNode(new SimplePopulatorFolder(nodeId, ownerId));
  }

  private static void addFolderWithChild() {
    DatabasePopulator.aNodePopulator(simulator.getInjector())
        .addNode(new SimplePopulatorFolder(FOLDER_ID, A_ID))
        .addNode(
            new PopulatorNode(
                CHILD_ID,
                A_ID,
                A_ID,
                FOLDER_ID,
                "x.txt",
                "",
                NodeType.TEXT,
                LOCAL_ROOT + "," + FOLDER_ID,
                7L,
                "text/plain"));
  }

  private static void addShare(String nodeId, String targetUserId, SharePermission permission) {
    DatabasePopulator.aNodePopulator(simulator.getInjector())
        .addShare(nodeId, targetUserId, permission);
  }

  private static void insertRootShare(String targetUserId) {
    shareRepository.upsertShare(
        LOCAL_ROOT,
        targetUserId,
        ACL.decode(SharePermission.READ_ONLY),
        true,
        false,
        Optional.empty());
  }

  private static HttpResponse graphql(String body, String cookie) {
    return TestUtils.sendRequest(
        HttpRequest.of("POST", "/graphql/", cookie, body), simulator.getNettyChannel());
  }

  private static HttpResponse rest(
      String method,
      String path,
      String cookie,
      List<Map.Entry<String, String>> headers,
      String body) {
    return TestUtils.sendFormRequest(
        HttpRequest.of(method, path, cookie, headers, body), simulator.getNettyChannel());
  }

  private static Optional<String> upload(String parentId) {
    byte[] content = "content".getBytes(StandardCharsets.UTF_8);
    BufferInputStream stream = new BufferInputStream(new EmbeddedChannel().config());
    stream.addContent(Unpooled.wrappedBuffer(content));
    stream.finishWrite();
    try {
      return simulator
          .getInjector()
          .getInstance(BlobService.class)
          .uploadFile(A_ID, Optional.empty(), stream, content.length, parentId, "x.txt", "");
    } catch (RuntimeException e) {
      return Optional.empty();
    }
  }

  private static HttpResponse createShare(
      String nodeId, String targetUserId, SharePermission permission, String cookie) {
    return graphql(
        GraphqlCommandBuilder.aMutationBuilder("createShare")
            .withString("node_id", nodeId)
            .withString("share_target_id", targetUserId)
            .withEnum("permission", permission)
            .withWantedResultFormat("{ created_at }")
            .build(),
        cookie);
  }

  private static HttpResponse updateShares(
      String nodeId, String targetUserId, SharePermission permission, String cookie) {
    return graphql(
        GraphqlCommandBuilder.aMutationBuilder("updateShares")
            .withString("node_id", nodeId)
            .withListOfStrings("share_target_ids", new String[] {targetUserId})
            .withEnum("permission", permission)
            .withWantedResultFormat("{ permission }")
            .build(),
        cookie);
  }

  private static HttpResponse createFolder(String parentId, String name, String cookie) {
    return graphql(
        GraphqlCommandBuilder.aMutationBuilder("createFolder")
            .withString("destination_id", parentId)
            .withString("name", name)
            .withWantedResultFormat("{ id }")
            .build(),
        cookie);
  }

  private static HttpResponse moveNodes(String nodeId, String destinationId, String cookie) {
    return graphql(
        GraphqlCommandBuilder.aMutationBuilder("moveNodes")
            .withListOfStrings("node_ids", new String[] {nodeId})
            .withString("destination_id", destinationId)
            .withWantedResultFormat("{ id }")
            .build(),
        cookie);
  }

  private static HttpResponse copyNodes(String nodeId, String destinationId, String cookie) {
    return graphql(
        GraphqlCommandBuilder.aMutationBuilder("copyNodes")
            .withListOfStrings("node_ids", new String[] {nodeId})
            .withString("destination_id", destinationId)
            .withWantedResultFormat("{ id }")
            .build(),
        cookie);
  }

  @SuppressWarnings("unchecked")
  private static List<String> errorCodes(HttpResponse response) {
    try {
      Map<String, Object> body = new ObjectMapper().readValue(response.getBodyPayload(), Map.class);
      List<Map<String, Object>> errors = (List<Map<String, Object>>) body.get("errors");
      return errors == null
          ? List.of()
          : errors.stream()
              .map(error -> (Map<String, Object>) error.get("extensions"))
              .filter(extensions -> extensions != null && extensions.get("errorCode") != null)
              .map(extensions -> String.valueOf(extensions.get("errorCode")))
              .toList();
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private static void assertErrorCodes(HttpResponse response, String expectedCode) {
    Assertions.assertThat(response.getStatus()).isEqualTo(200);
    Assertions.assertThat(errorCodes(response)).containsOnly(expectedCode);
  }

  private static void assertNoErrors(HttpResponse response) {
    Assertions.assertThat(response.getStatus()).isEqualTo(200);
    Assertions.assertThat(TestUtils.jsonResponseToErrors(response.getBodyPayload())).isEmpty();
  }

  private static void assertStorageUploads(int expected) {
    simulator
        .getStoragesMock()
        .verify(
            org.mockserver.model.HttpRequest.request().withPath("/upload"),
            VerificationTimes.exactly(expected));
  }

  private record ShareRow(short rights, boolean direct) {}

  private static Optional<ShareRow> shareRow(String nodeId, String targetUserId) {
    SqlRow row =
        database
            .sqlQuery("SELECT rights, direct FROM share WHERE node_id = ? AND target_uuid = ?")
            .setParameter(1, nodeId)
            .setParameter(2, targetUserId)
            .findOne();
    return row == null
        ? Optional.empty()
        : Optional.of(
            new ShareRow(row.getInteger("rights").shortValue(), row.getBoolean("direct")));
  }

  private static int childrenCount(String parentId) {
    return count("SELECT count(*) FROM node WHERE folder_id = ?", parentId);
  }

  private static String parentOf(String nodeId) {
    return singleString("SELECT trim(folder_id) FROM node WHERE node_id = ?", nodeId);
  }

  private static int count(String sql, String parameter) {
    SqlRow row = database.sqlQuery(sql).setParameter(1, parameter).findOne();
    return row.getInteger("count");
  }

  private static String singleString(String sql, String parameter) {
    SqlRow row = database.sqlQuery(sql).setParameter(1, parameter).findOne();
    return String.valueOf(row.values().iterator().next());
  }
}
