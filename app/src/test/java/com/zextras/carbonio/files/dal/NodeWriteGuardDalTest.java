// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.dal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zextras.carbonio.files.Constants.Db.RootId;
import com.zextras.carbonio.files.FilesStackTestResource;
import com.zextras.carbonio.files.TestUtils;
import com.zextras.carbonio.files.api.utilities.GraphqlCommandBuilder;
import com.zextras.carbonio.files.dal.dao.ebean.NodeType;
import com.zextras.carbonio.files.dal.locks.LockKey;
import com.zextras.carbonio.files.dal.locks.NodeBusyException;
import com.zextras.carbonio.files.dal.locks.NodeLocks;
import com.zextras.carbonio.files.dal.repositories.interfaces.NodeRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

@QuarkusTest
@QuarkusTestResource(FilesStackTestResource.class)
class NodeWriteGuardDalTest {

  private static final String USER_ID = "61111111-1111-1111-1111-111111111111";
  private static final String COOKIE = "ZM_AUTH_TOKEN=guard-owner";
  private static final String FAIL_FAST = "requireWritableNow";

  @Inject NodeLocks nodeLocks;
  @Inject NodeRepository nodeRepository;

  @BeforeAll
  static void registerUser() {
    FilesStackTestResource.getUserManagementService().registerToken("guard-owner", USER_ID);
  }

  @AfterEach
  void cleanup() throws SQLException {
    RefusingNodeWriteGuard.reset();
    try (Connection connection = jdbc();
        Statement statement = connection.createStatement()) {
      statement.execute("DELETE FROM node WHERE owner_id IS NOT NULL");
      statement.execute(
          "TRUNCATE tombstone, version_reservation, overwrite_lease, notification,"
              + " user_notification_interest CASCADE");
    }
    FilesStackTestResource.getStoragesService().reset();
    FilesStackTestResource.getStoragesService().clearAll();
  }

  private static Connection jdbc() throws SQLException {
    return DriverManager.getConnection(FilesStackTestResource.POSTGRES_JDBC_URL, "test", "test");
  }

  private static long count(String sql) throws SQLException {
    try (Connection connection = jdbc();
        Statement statement = connection.createStatement();
        ResultSet rs = statement.executeQuery(sql)) {
      rs.next();
      return rs.getLong(1);
    }
  }

  private static long versionRows(String nodeId) throws SQLException {
    return count("SELECT count(*) FROM revision WHERE node_id = '" + nodeId + "'");
  }

  private static long currentVersion(String nodeId) throws SQLException {
    return count("SELECT current_version FROM node WHERE node_id = '" + nodeId + "'");
  }

  private static long childCount(String folderId) throws SQLException {
    return count("SELECT count(*) FROM node WHERE folder_id = '" + folderId + "'");
  }

  private String seed(String parentId, NodeType type) {
    String nodeId = UUID.randomUUID().toString();
    String ancestors = RootId.LOCAL_ROOT.equals(parentId) ? parentId : "LOCAL_ROOT," + parentId;
    QuarkusTransaction.requiringNew()
        .run(
            () ->
                nodeRepository.createNewNode(
                    nodeId, USER_ID, USER_ID, parentId, "n-" + nodeId, "d", type, ancestors, 0L));
    return nodeId;
  }

  private static String base64(String value) {
    return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
  }

  private static Response upload(String parentId, String name) {
    return io.restassured.RestAssured.given()
        .header("Filename", base64(name))
        .header("ParentId", parentId)
        .header("Cookie", COOKIE)
        .body(new byte[] {1, 2, 3})
        .post("/upload");
  }

  private static Response uploadVersion(String nodeId, boolean overwrite) {
    return io.restassured.RestAssured.given()
        .header("NodeId", nodeId)
        .header("Filename", base64("f.bin"))
        .header("OverwriteVersion", String.valueOf(overwrite))
        .header("Cookie", COOKIE)
        .body(new byte[] {4, 5, 6})
        .post("/upload-version");
  }

  private static Response graphql(String mutation) {
    return io.restassured.RestAssured.given()
        .contentType("application/json")
        .body(TestUtils.queryPayload(mutation))
        .header("Cookie", COOKIE)
        .post("/graphql/");
  }

  private static void assertRefusedGraphql(Response response, int refusals) {
    assertThat(response.jsonPath().getList("errors")).isNotEmpty();
    assertThat(RefusingNodeWriteGuard.refusals()).isEqualTo(refusals);
  }

  private static String uploadedFile() {
    Response response = upload(RootId.LOCAL_ROOT, "f-" + UUID.randomUUID() + ".bin");
    String nodeId = response.jsonPath().getString("nodeId");
    assertThat(nodeId).isNotNull();
    return nodeId;
  }

  private static int uploadRequests() {
    return FilesStackTestResource.getStoragesService().uploadRequestCount();
  }

  private void inTx(Runnable action) {
    QuarkusTransaction.requiringNew().run(action);
  }

  @Test
  void lockRefusesAGuardedNodeAndItsParentChildrenKey() {
    String parent = seed(RootId.LOCAL_ROOT, NodeType.FOLDER);
    String child = seed(parent, NodeType.TEXT);
    RefusingNodeWriteGuard.refuse(child);

    assertThatThrownBy(() -> inTx(() -> nodeLocks.lock(List.of(LockKey.node(child)))))
        .isInstanceOfSatisfying(
            NodeBusyException.class, e -> assertThat(e.getNodeId()).isEqualTo(child));

    RefusingNodeWriteGuard.reset();
    RefusingNodeWriteGuard.refuse(parent);

    assertThatThrownBy(() -> inTx(() -> nodeLocks.lock(List.of(LockKey.children(parent, USER_ID)))))
        .isInstanceOfSatisfying(
            NodeBusyException.class, e -> assertThat(e.getNodeId()).isEqualTo(parent));
  }

  @Test
  void lockNeverGuardsTheRootFolders() {
    RefusingNodeWriteGuard.refuse(RootId.LOCAL_ROOT);

    inTx(
        () ->
            nodeLocks.lock(
                List.of(
                    LockKey.node(RootId.LOCAL_ROOT),
                    LockKey.children(RootId.LOCAL_ROOT, USER_ID))));
  }

  @Test
  void lockChildrenRefusesAGuardedChild() {
    String parent = seed(RootId.LOCAL_ROOT, NodeType.FOLDER);
    String child = seed(parent, NodeType.TEXT);
    RefusingNodeWriteGuard.refuse(child);

    assertThatThrownBy(() -> inTx(() -> nodeLocks.lockChildren(List.of(parent))))
        .isInstanceOfSatisfying(
            NodeBusyException.class, e -> assertThat(e.getNodeId()).isEqualTo(child));
  }

  @Test
  void lockRowsRefusesAGuardedRowAfterTheFirstLock() {
    String first = seed(RootId.LOCAL_ROOT, NodeType.FOLDER);
    String second = seed(RootId.LOCAL_ROOT, NodeType.TEXT);
    RefusingNodeWriteGuard.refuse(second);

    assertThatThrownBy(
            () ->
                inTx(
                    () -> {
                      nodeLocks.lock(List.of(LockKey.node(first)));
                      nodeLocks.lockRows(List.of(second));
                    }))
        .isInstanceOfSatisfying(
            NodeBusyException.class, e -> assertThat(e.getNodeId()).isEqualTo(second));
  }

  @Test
  void uploadOfANewFileIsRefusedBeforeStreaming() throws SQLException {
    String folder = seed(RootId.LOCAL_ROOT, NodeType.FOLDER);
    RefusingNodeWriteGuard.refuse(folder, FAIL_FAST);
    int before = uploadRequests();

    Response response = upload(folder, "new.bin");

    assertThat(response.getStatusCode()).isEqualTo(423);
    assertThat(uploadRequests()).isEqualTo(before);
    assertThat(childCount(folder)).isZero();
  }

  @Test
  void uploadVersionIsRefusedBeforeStreaming() throws SQLException {
    String file = uploadedFile();
    int before = uploadRequests();
    RefusingNodeWriteGuard.refuse(file, FAIL_FAST);

    Response response = uploadVersion(file, false);

    assertThat(response.getStatusCode()).isEqualTo(423);
    assertThat(uploadRequests()).isEqualTo(before);
    assertThat(versionRows(file)).isEqualTo(1);
  }

  @Test
  void uploadVersionConfirmIsRefusedAndWritesNoVersion() throws SQLException {
    String file = uploadedFile();
    RefusingNodeWriteGuard.refuse(file, "lambda$uploadNewVersion");

    Response response = uploadVersion(file, false);

    assertThat(response.getStatusCode()).isEqualTo(423);
    assertThat(versionRows(file)).isEqualTo(1);
    assertThat(currentVersion(file)).isEqualTo(1);
  }

  @Test
  void overwriteIsRefusedBeforeStreaming() throws SQLException {
    String file = uploadedFile();
    int before = uploadRequests();
    RefusingNodeWriteGuard.refuse(file, FAIL_FAST);

    Response response = uploadVersion(file, true);

    assertThat(response.getStatusCode()).isEqualTo(423);
    assertThat(uploadRequests()).isEqualTo(before);
  }

  @Test
  void overwriteLeaseTakeIsRefusedBeforeAnyBlobWrite() throws SQLException {
    String file = uploadedFile();
    int before = uploadRequests();
    RefusingNodeWriteGuard.refuse(file, "OverwriteLeases");

    Response response = uploadVersion(file, true);

    assertThat(response.getStatusCode()).isEqualTo(423);
    assertThat(uploadRequests()).isEqualTo(before);
    assertThat(count("SELECT count(*) FROM overwrite_lease")).isZero();
  }

  @Test
  void overwriteConfirmIsRefusedAndRewritesNothing() throws SQLException {
    String file = uploadedFile();
    long dateBefore =
        count("SELECT timestamp FROM revision WHERE node_id = '" + file + "' AND version = 1");
    RefusingNodeWriteGuard.refuse(file, "lambda$overwriteCurrentVersion");

    Response response = uploadVersion(file, true);

    assertThat(response.getStatusCode()).isEqualTo(423);
    assertThat(versionRows(file)).isEqualTo(1);
    assertThat(
            count("SELECT timestamp FROM revision WHERE node_id = '" + file + "' AND version = 1"))
        .isEqualTo(dateBefore);
  }

  @Test
  void cloneVersionIsRefusedBeforeCopying() throws SQLException {
    String file = uploadedFile();
    RefusingNodeWriteGuard.refuse(file, FAIL_FAST);

    Response response = graphql(cloneMutation(file));

    assertRefusedGraphql(response, 1);
    assertThat(FilesStackTestResource.getStoragesService().copyRequests()).isEmpty();
    assertThat(versionRows(file)).isEqualTo(1);
  }

  @Test
  void cloneVersionConfirmIsRefusedAndWritesNoVersion() throws SQLException {
    String file = uploadedFile();
    RefusingNodeWriteGuard.refuse(file, "lambda$cloneVersion");

    Response response = graphql(cloneMutation(file));

    assertRefusedGraphql(response, 1);
    assertThat(versionRows(file)).isEqualTo(1);
    assertThat(currentVersion(file)).isEqualTo(1);
  }

  @Test
  void copyNodesIsRefusedForAGuardedDestinationButNotForAGuardedSource() throws SQLException {
    String file = uploadedFile();
    String destination = seed(RootId.LOCAL_ROOT, NodeType.FOLDER);
    RefusingNodeWriteGuard.refuse(destination, FAIL_FAST);

    Response refused = graphql(copyMutation(file, destination));

    assertRefusedGraphql(refused, 1);
    assertThat(FilesStackTestResource.getStoragesService().copyRequests()).isEmpty();
    assertThat(childCount(destination)).isZero();

    RefusingNodeWriteGuard.reset();
    RefusingNodeWriteGuard.refuse(file, FAIL_FAST);

    Response allowed = graphql(copyMutation(file, destination));

    assertThat(allowed.jsonPath().getList("errors")).isNull();
    assertThat(childCount(destination)).isEqualTo(1);
  }

  @Test
  void aBusyNodeFailsTheCascadingLockOfAMutation() {
    String file = uploadedFile();
    RefusingNodeWriteGuard.refuse(file);

    Response response =
        graphql(
            GraphqlCommandBuilder.aMutationBuilder("flagNodes")
                .withListOfStrings("node_ids", new String[] {file})
                .withBoolean("flag", true)
                .withWantedResultFormat("")
                .build());

    assertRefusedGraphql(response, 1);
  }

  private static String text(String sql) throws SQLException {
    try (Connection connection = jdbc();
        Statement statement = connection.createStatement();
        ResultSet rs = statement.executeQuery(sql)) {
      rs.next();
      return rs.getString(1);
    }
  }

  private static String ancestorsOf(String nodeId) throws SQLException {
    return text("SELECT trim(ancestor_ids) FROM node WHERE node_id = '" + nodeId + "'");
  }

  private static String folder(String parentId) {
    Response response =
        graphql(
            GraphqlCommandBuilder.aMutationBuilder("createFolder")
                .withString("destination_id", parentId)
                .withString("name", "f-" + UUID.randomUUID())
                .withWantedResultFormat("{ id }")
                .build());
    String id = response.jsonPath().getString("data.createFolder.id");
    assertThat(id).isNotNull();
    return id;
  }

  private static Response treeMutation(String name, String key, String value) {
    GraphqlCommandBuilder builder =
        GraphqlCommandBuilder.aMutationBuilder(name)
            .withListOfStrings("node_ids", new String[] {value});
    if (key != null) {
      builder = builder.withString("destination_id", key);
    }
    return graphql(
        builder.withWantedResultFormat("trashNodes".equals(name) ? "" : "{ id }").build());
  }

  private static void assertBusy(Response response) {
    assertThat(response.jsonPath().getList("errors")).isNotEmpty();
  }

  @Test
  void trashOfAnAncestorOfARefusedDescendantFailsBusyAndChangesNothing() throws SQLException {
    String top = folder(RootId.LOCAL_ROOT);
    String middle = folder(top);
    String file = upload(middle, "deep.bin").jsonPath().getString("nodeId");
    String middleAncestors = ancestorsOf(middle);
    String fileAncestors = ancestorsOf(file);
    RefusingNodeWriteGuard.refuse(file);

    Response response = treeMutation("trashNodes", null, top);

    assertBusy(response);
    assertThat(RefusingNodeWriteGuard.refusals()).isEqualTo(1);
    assertThat(count("SELECT count(*) FROM trashed WHERE node_id = '" + top + "'")).isZero();
    assertThat(text("SELECT trim(folder_id) FROM node WHERE node_id = '" + top + "'"))
        .isEqualTo(RootId.LOCAL_ROOT);
    assertThat(ancestorsOf(middle)).isEqualTo(middleAncestors);
    assertThat(ancestorsOf(file)).isEqualTo(fileAncestors);
  }

  @Test
  void moveOfAnAncestorOfARefusedDescendantFailsBusyAndChangesNothing() throws SQLException {
    String destination = folder(RootId.LOCAL_ROOT);
    String top = folder(RootId.LOCAL_ROOT);
    String middle = folder(top);
    String file = upload(middle, "deep.bin").jsonPath().getString("nodeId");
    String middleAncestors = ancestorsOf(middle);
    String fileAncestors = ancestorsOf(file);
    RefusingNodeWriteGuard.refuse(file);

    Response response = treeMutation("moveNodes", destination, top);

    assertBusy(response);
    assertThat(RefusingNodeWriteGuard.refusals()).isEqualTo(1);
    assertThat(text("SELECT trim(folder_id) FROM node WHERE node_id = '" + top + "'"))
        .isEqualTo(RootId.LOCAL_ROOT);
    assertThat(ancestorsOf(middle)).isEqualTo(middleAncestors);
    assertThat(ancestorsOf(file)).isEqualTo(fileAncestors);
  }

  @Test
  void restoreOfAnAncestorOfARefusedDescendantFailsBusyAndChangesNothing() throws SQLException {
    String top = folder(RootId.LOCAL_ROOT);
    String middle = folder(top);
    String file = upload(middle, "deep.bin").jsonPath().getString("nodeId");
    assertThat(treeMutation("trashNodes", null, top).jsonPath().getList("errors")).isNull();
    String middleAncestors = ancestorsOf(middle);
    String fileAncestors = ancestorsOf(file);
    RefusingNodeWriteGuard.refuse(file);

    Response response = treeMutation("restoreNodes", null, top);

    assertBusy(response);
    assertThat(RefusingNodeWriteGuard.refusals()).isEqualTo(1);
    assertThat(count("SELECT count(*) FROM trashed WHERE node_id = '" + top + "'")).isEqualTo(1);
    assertThat(ancestorsOf(middle)).isEqualTo(middleAncestors);
    assertThat(ancestorsOf(file)).isEqualTo(fileAncestors);
  }

  private static Response deleteAllNodesOf(String userId) {
    return io.restassured.RestAssured.given()
        .contentType("application/json")
        .body("{\"userId\":\"" + userId + "\"}")
        .delete("/internal/nodes");
  }

  @Test
  void deleteAllOfAUserWithARefusedNodeAnswers423AndDeletesNothing() throws SQLException {
    String file = uploadedFile();
    String other = uploadedFile();
    RefusingNodeWriteGuard.refuse(other);

    Response response = deleteAllNodesOf(USER_ID);

    assertThat(response.getStatusCode()).isEqualTo(423);
    assertThat(RefusingNodeWriteGuard.refusals()).isEqualTo(1);
    assertThat(count("SELECT count(*) FROM node WHERE node_id IN ('" + file + "','" + other + "')"))
        .isEqualTo(2);
    assertThat(count("SELECT count(*) FROM tombstone")).isZero();
    assertThat(FilesStackTestResource.getStoragesService().has(file, 1)).isTrue();
  }

  @Test
  void deleteAllNeitherTombstonesNorDeletesANodeWhoseOwnerChangedBeforeTheLock() throws Exception {
    String kept = uploadedFile();
    String transferred = uploadedFile();
    CompletableFuture<Response> deletion;
    try (Connection holder = jdbc()) {
      holder.setAutoCommit(false);
      try (PreparedStatement lock =
          holder.prepareStatement("SELECT 1 FROM node WHERE node_id = ? FOR UPDATE")) {
        lock.setString(1, transferred);
        lock.execute();
      }
      deletion = CompletableFuture.supplyAsync(() -> deleteAllNodesOf(USER_ID));
      LockWaits.awaitBlockedBy(1, LockWaits.backendPid(holder));
      try (PreparedStatement transfer =
          holder.prepareStatement("UPDATE node SET owner_id = ? WHERE node_id = ?")) {
        transfer.setString(1, "someone-else");
        transfer.setString(2, transferred);
        transfer.execute();
      }
      holder.commit();
    }

    Response response = deletion.get(30, TimeUnit.SECONDS);

    assertThat(response.getStatusCode()).isEqualTo(200);
    assertThat(count("SELECT count(*) FROM node WHERE node_id = '" + kept + "'")).isZero();
    assertThat(count("SELECT count(*) FROM node WHERE node_id = '" + transferred + "'"))
        .isEqualTo(1);
    assertThat(count("SELECT count(*) FROM tombstone WHERE node_id = '" + transferred + "'"))
        .isZero();
    assertThat(FilesStackTestResource.getStoragesService().has(transferred, 1)).isTrue();
  }

  private static String cloneMutation(String nodeId) {
    return GraphqlCommandBuilder.aMutationBuilder("cloneVersion")
        .withString("node_id", nodeId)
        .withInteger("version", 1)
        .withWantedResultFormat("{ id }")
        .build();
  }

  private static String copyMutation(String nodeId, String destinationId) {
    return GraphqlCommandBuilder.aMutationBuilder("copyNodes")
        .withListOfStrings("node_ids", new String[] {nodeId})
        .withString("destination_id", destinationId)
        .withWantedResultFormat("{ id }")
        .build();
  }
}
