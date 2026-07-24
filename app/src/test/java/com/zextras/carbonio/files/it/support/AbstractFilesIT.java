// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.it.support;

import com.zextras.carbonio.files.FilesStackTestResource;
import com.zextras.carbonio.files.TestUtils;
import com.zextras.carbonio.files.api.utilities.GraphqlCommandBuilder;
import com.zextras.carbonio.files.dal.dao.ebean.ACL;
import com.zextras.carbonio.files.dal.dao.ebean.NodeType;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusIntegrationTest;
import io.restassured.RestAssured;
import io.restassured.response.Response;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;

/**
 * The ONE base class for the out-of-process (native-binary-capable) black-box IT suite (D1/D2 of
 * the acceptance-to-Quarkus-tests plan): {@code @QuarkusIntegrationTest} — the app under test runs
 * as a SEPARATE launched process (packaged jar today, {@code -Dnative} periodically) — plus {@code
 * @WithTestResource(FilesStackTestResource.class)}, the same stack the seam-based {@code
 * @QuarkusTest} acceptance classes still use during the migration window.
 *
 * <p><b>NO {@code @Inject}/Arc anywhere in this class or its subclasses.</b> Out-of-process, the
 * test JVM and the app JVM are different processes; CDI beans are simply not resolvable from here.
 * Every capability below is either (a) a RestAssured HTTP call against the launched app's real
 * port, or (b) a raw JDBC connection to the shared Postgres Testcontainer via {@link
 * FilesStackTestResource#POSTGRES_JDBC_URL}.
 *
 * <p><b>Seeding is API-first.</b> The {@code seedXxx} helpers below replace {@code
 * DatabasePopulator}'s direct repository writes: they drive the same public GraphQL/REST surface a
 * real client would, and return the SERVER-GENERATED id from the response — subclasses must never
 * hard-code a node/share/link id, since API-seeding cannot produce a caller-chosen id. A second
 * user-management token (registered via {@link FilesStackTestResource#getUserManagementService()})
 * is how a subclass seeds a foreign-owner node (create as that user, then act as the requester).
 *
 * <p><b>Cleanup is raw JDBC.</b> {@link #resetDb()} runs after every test method and mirrors the
 * seam's {@code QuarkusTestDataAccess#resetDatabase} exactly (same DELETE/TRUNCATE statements), plus
 * resetting the shared {@code MockStoragesService} fake so blob-presence assertions do not leak
 * across tests.
 *
 * <p><b>GOTCHA — the cookie/token convention is GLOBAL, not per-class.</b> Unlike the old seam
 * (one fresh in-process {@code FilesTestApp}/user-management fake PER TEST CLASS), {@link
 * FilesStackTestResource#getUserManagementService()} is a single static singleton shared by the
 * ENTIRE out-of-process test run. Its {@code registerToken(token, userId)} 2-arg overload is a
 * NO-OP if the token is already registered — so whichever class runs FIRST in the suite "wins" a
 * given cookie label for every class that reuses it afterward. ALL subclasses MUST reuse the SAME
 * fixed convention for the standard fixture users: {@code "fake-token"} →
 * {@code aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa}, {@code "fake-token-b"} →
 * {@code bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb}, {@code "fake-token-c"} →
 * {@code cccccccc-cccc-cccc-cccc-cccccccccccc}. Deviating (e.g. mapping {@code "fake-token-b"} to
 * a different id in just one class) silently binds that cookie to WHICHEVER id some other class
 * registered first, corrupting ownership/permission assertions in a way that only reproduces when
 * the whole suite (or an unlucky subset) runs together — see the fix in {@code FlagNodesApiIT}.
 */
@QuarkusIntegrationTest
@WithTestResource(FilesStackTestResource.class)
public abstract class AbstractFilesIT {

  /** The fixed root-folder pseudo-id accepted by {@code createFolder}/{@code upload}'s ParentId header. */
  protected static final String LOCAL_ROOT = "LOCAL_ROOT";

  // --------------------------------------------------------------------------------- lifecycle

  /**
   * Raw-JDBC cleanup, run after every test method. Mirrors the seam's {@code
   * QuarkusTestDataAccess#resetDatabase} verbatim (same statements) since {@code @Inject}/Arc
   * repositories are not available out-of-process. Also resets the shared storages fake so
   * upload/download/failure-injection state never leaks into the next test.
   */
  @AfterEach
  void resetDb() throws SQLException {
    try (Connection connection = jdbcConnection();
        Statement statement = connection.createStatement()) {
      // Delete test nodes but preserve ROOT nodes (LOCAL_ROOT/TRASH_ROOT, null owner_id). FK
      // cascades wipe activity/custom/link/revision/share/trashed.
      statement.execute("DELETE FROM node WHERE owner_id IS NOT NULL");
      // Notification + snapshot tables are not FK-linked to node, so the cascade above misses them.
      statement.execute(
          "TRUNCATE user_notification_interest, notification, snapshot_node, snapshot_user,"
              + " user_notifications_info CASCADE");
    }
    FilesStackTestResource.getStoragesService().reset();
    FilesStackTestResource.getStoragesService().clearAll();
  }

  // ------------------------------------------------------------------------------------- JDBC

  /** Opens a raw JDBC connection to the shared Postgres Testcontainer (test/test credentials). */
  protected static Connection jdbcConnection() throws SQLException {
    return DriverManager.getConnection(FilesStackTestResource.POSTGRES_JDBC_URL, "test", "test");
  }

  /**
   * The rare non-API read-back: number of tombstone rows for a given node (any version). Prefer
   * {@link #download(String, int, String)} → 200/404 for API-observable version state; use this
   * only when the state genuinely has no API-observable equivalent (e.g. asserting a tombstone was
   * recorded after a forced storages bulk-delete failure).
   */
  protected static int tombstoneRowsForNode(String nodeId) throws SQLException {
    try (Connection connection = jdbcConnection();
        PreparedStatement statement =
            connection.prepareStatement("SELECT COUNT(*) FROM tombstone WHERE node_id = ?")) {
      statement.setString(1, nodeId);
      try (ResultSet resultSet = statement.executeQuery()) {
        resultSet.next();
        return resultSet.getInt(1);
      }
    }
  }

  /** The rare non-API read-back: the set of surviving version numbers for a node, ascending. */
  protected static List<Integer> versionRows(String nodeId) throws SQLException {
    try (Connection connection = jdbcConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "SELECT version FROM revision WHERE node_id = ? ORDER BY version ASC")) {
      statement.setString(1, nodeId);
      try (ResultSet resultSet = statement.executeQuery()) {
        List<Integer> versions = new ArrayList<>();
        while (resultSet.next()) {
          versions.add(resultSet.getInt(1));
        }
        return versions;
      }
    }
  }

  // ------------------------------------------------------------------------------ RestAssured

  /** POSTs an authenticated GraphQL {@code query}/{@code mutation} string to {@code /graphql/}. */
  protected static Response graphql(String query, String cookie) {
    var request = RestAssured.given().contentType("application/json").body(TestUtils.queryPayload(query));
    if (cookie != null) {
      request = request.header("Cookie", cookie);
    }
    return request.post("/graphql/");
  }

  /** POSTs an UNAUTHENTICATED GraphQL {@code query}/{@code mutation} string to {@code /public/graphql/}. */
  protected static Response publicGraphql(String query) {
    return RestAssured.given()
        .contentType("application/json")
        .body(TestUtils.queryPayload(query))
        .post("/public/graphql/");
  }

  /**
   * {@code POST /upload}: creates a new node under {@code parentId} (or the account root, when
   * {@code null}) with the given content, matching {@code BlobResource#upload}'s header contract
   * ({@code Filename} base64, optional {@code ParentId}/{@code Description}, raw byte body — NOT
   * multipart).
   */
  protected static Response upload(String parentId, String description, byte[] content, String filename, String cookie) {
    var request = RestAssured.given().header("Filename", base64(filename));
    if (parentId != null) {
      request = request.header("ParentId", parentId);
    }
    if (description != null) {
      request = request.header("Description", description);
    }
    if (cookie != null) {
      request = request.header("Cookie", cookie);
    }
    return request.body(content).post("/upload");
  }

  /**
   * {@code POST /upload-version}: uploads a new version of {@code nodeId}, matching {@code
   * BlobResource#uploadVersion}'s header contract ({@code NodeId}, {@code Filename} base64, {@code
   * OverwriteVersion}, raw byte body).
   */
  protected static Response uploadVersion(
      String nodeId, byte[] content, String filename, boolean overwrite, String cookie) {
    var request =
        RestAssured.given()
            .header("NodeId", nodeId)
            .header("Filename", base64(filename))
            .header("OverwriteVersion", String.valueOf(overwrite));
    if (cookie != null) {
      request = request.header("Cookie", cookie);
    }
    return request.body(content).post("/upload-version");
  }

  /** {@code GET /download/{nodeId}}: downloads the current version. */
  protected static Response download(String nodeId, String cookie) {
    var request = RestAssured.given();
    if (cookie != null) {
      request = request.header("Cookie", cookie);
    }
    return request.get("/download/" + nodeId);
  }

  /** {@code GET /download/{nodeId}/{version}}: downloads a specific version. */
  protected static Response download(String nodeId, int version, String cookie) {
    var request = RestAssured.given();
    if (cookie != null) {
      request = request.header("Cookie", cookie);
    }
    return request.get("/download/" + nodeId + "/" + version);
  }

  private static String base64(String value) {
    return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
  }

  // ----------------------------------------------------------------------------- API seeding

  /**
   * Creates a folder via the public {@code createFolder} GraphQL mutation and returns the
   * SERVER-GENERATED id. Replaces {@code DatabasePopulator#addNode(SimplePopulatorFolder)}.
   */
  protected static String seedFolder(String name, String parentId, String ownerCookie) {
    String mutation =
        GraphqlCommandBuilder.aMutationBuilder("createFolder")
            .withString("destination_id", parentId)
            .withString("name", name)
            .withWantedResultFormat("{ id }")
            .build();
    Response response = graphql(mutation, ownerCookie);
    Map<String, Object> folder = TestUtils.jsonResponseToMap(response.getBody().asString(), "createFolder");
    String id = folder == null ? null : (String) folder.get("id");
    if (id == null) {
      throw new IllegalStateException("seedFolder failed: " + response.getBody().asString());
    }
    return id;
  }

  /**
   * Creates a file via {@code POST /upload} (version 1) and returns the SERVER-GENERATED node id.
   * Replaces {@code DatabasePopulator#addNode(SimplePopulatorTextFile)}.
   */
  protected static String seedFile(String name, String parentId, byte[] content, String ownerCookie) {
    Response response = upload(parentId, null, content, name, ownerCookie);
    Map<String, Object> json = response.jsonPath().getMap("$");
    String nodeId = json == null ? null : (String) json.get("nodeId");
    if (nodeId == null) {
      throw new IllegalStateException("seedFile failed: " + response.getBody().asString());
    }
    return nodeId;
  }

  /**
   * Uploads a new version of an existing node via {@code POST /upload-version} and returns the
   * new version number. Replaces {@code DatabasePopulator#addVersion}. {@code keepForever} is NOT
   * settable via the public API (it is not exposed as an upload parameter); a scenario that needs
   * a kept-forever version seeded directly (rather than exercised via the {@code updateNode}
   * mutation) is a candidate for the JDBC-seed escape hatch, not this helper.
   */
  protected static int seedVersion(String nodeId, byte[] content, String filename, String ownerCookie) {
    Response response = uploadVersion(nodeId, content, filename, false, ownerCookie);
    Map<String, Object> json = response.jsonPath().getMap("$");
    Object version = json == null ? null : json.get("version");
    if (version == null) {
      // UploadVersionResponse#setVersion only serialises values > 1 (Wave-0 quirk); version 2 is
      // the first version-bump reachable via this helper's normal (non-overwrite) path, so an
      // absent field here means the upload itself failed.
      throw new IllegalStateException("seedVersion failed: " + response.getBody().asString());
    }
    return ((Number) version).intValue();
  }

  /** Creates a share via the {@code createShare} GraphQL mutation. Replaces {@code DatabasePopulator#addShare}. */
  protected static void seedShare(
      String nodeId, String targetUserId, ACL.SharePermission permission, String ownerCookie) {
    String mutation =
        GraphqlCommandBuilder.aMutationBuilder("createShare")
            .withString("node_id", nodeId)
            .withString("share_target_id", targetUserId)
            .withEnum("permission", permission)
            .withWantedResultFormat("{ created_at }")
            .build();
    Response response = graphql(mutation, ownerCookie);
    List<String> errors = TestUtils.jsonResponseToErrors(response.getBody().asString());
    if (!errors.isEmpty()) {
      throw new IllegalStateException("seedShare failed: " + errors);
    }
  }

  /** Creates a public link via the {@code createLink} GraphQL mutation and returns its id. Replaces {@code DatabasePopulator#addLink}. */
  protected static String seedLink(String nodeId, String ownerCookie) {
    String mutation =
        GraphqlCommandBuilder.aMutationBuilder("createLink")
            .withString("node_id", nodeId)
            .withWantedResultFormat("{ id }")
            .build();
    Response response = graphql(mutation, ownerCookie);
    Map<String, Object> link = TestUtils.jsonResponseToMap(response.getBody().asString(), "createLink");
    String id = link == null ? null : (String) link.get("id");
    if (id == null) {
      throw new IllegalStateException("seedLink failed: " + response.getBody().asString());
    }
    return id;
  }

  /** Flags a node for the given requester via the {@code flagNodes} GraphQL mutation. Replaces {@code DatabasePopulator#addFlag}. */
  protected static void seedFlag(String nodeId, String cookie) {
    String mutation =
        GraphqlCommandBuilder.aMutationBuilder("flagNodes")
            .withListOfStrings("node_ids", new String[] {nodeId})
            .withBoolean("flag", true)
            .withWantedResultFormat("")
            .build();
    Response response = graphql(mutation, cookie);
    List<String> errors = TestUtils.jsonResponseToErrors(response.getBody().asString());
    if (!errors.isEmpty()) {
      throw new IllegalStateException("seedFlag failed: " + errors);
    }
  }

  /** Trashes a node via the {@code trashNodes} GraphQL mutation. Replaces {@code DatabasePopulator#addNodeToTrash}. */
  protected static void seedTrashed(String nodeId, String cookie) {
    String mutation =
        GraphqlCommandBuilder.aMutationBuilder("trashNodes")
            .withListOfStrings("node_ids", new String[] {nodeId})
            .withWantedResultFormat("")
            .build();
    Response response = graphql(mutation, cookie);
    List<String> errors = TestUtils.jsonResponseToErrors(response.getBody().asString());
    if (!errors.isEmpty()) {
      throw new IllegalStateException("seedTrashed failed: " + errors);
    }
  }

  /**
   * API-observable existence check replacing the seam's backdoor {@code
   * TestDataAccess#nodeExists(String)} (which resolved {@code NodeRepository} from Arc — unavailable
   * out-of-process): queries {@code getNode} and returns {@code true} iff it resolves without a
   * GraphQL error.
   */
  protected static boolean nodeExists(String nodeId, String cookie) {
    String query =
        GraphqlCommandBuilder.aQueryBuilder("getNode")
            .withString("node_id", nodeId)
            .withWantedResultFormat("{ id }")
            .build();
    Response response = graphql(query, cookie);
    Map<String, Object> node = TestUtils.jsonResponseToMap(response.getBody().asString(), "getNode");
    return node != null && node.get("id") != null;
  }

  /**
   * Waits until the system clock advances by at least 1ms. Ports {@code
   * DatabasePopulator#delay()}'s rationale verbatim: consecutive API-seeding calls must land on
   * distinct {@code creation_timestamp}/{@code updated_timestamp} millis for time-ordering
   * assertions (sort-by-updated-at, pagination-by-time) to be deterministic.
   */
  protected static void tickClock() {
    long start = System.currentTimeMillis();
    while (System.currentTimeMillis() == start) {
      Thread.onSpinWait();
    }
  }

  // ------------------------------------------------------------------ rare JDBC-only seeding

  /**
   * Raw-JDBC seed for a node (+ matching version-1 {@code revision} row for non-folder types)
   * whose creator/owner topology is NOT producible via the public API — e.g. a child whose owner
   * differs from its structural parent's owner (the real {@code createFolder}/{@code upload}
   * mutations always inherit the parent's owner), or a ghost creator/owner id never registered
   * with user-management (the real API always stamps the AUTHENTICATED caller as creator/owner).
   * Mirrors exactly what {@code NodeRepositoryImpl#createNewNode} + {@code
   * FileVersionRepositoryImpl#createNewFileVersion} (the production code the old seam's {@code
   * DatabasePopulator#addNode} drove via Arc) persist for a freshly-created node: {@code
   * index_status=1}, {@code hidden=false}, {@code current_version=1} for non-{@code FOLDER}/{@code
   * ROOT} types (left {@code NULL} otherwise), one {@code revision} row (version 1, {@code
   * editor_id = ownerId}, empty digest, {@code keep_forever=false}) for any non-folder type.
   *
   * <p>Use ONLY for the rare API-observable-but-not-API-creatable pre-state (D1 rule 4 of the
   * acceptance-to-Quarkus-tests plan); every other fixture must go through the {@code seedXxx} API
   * helpers above.
   */
  protected static void seedInconsistentNode(
      String nodeId,
      String creatorId,
      String ownerId,
      String parentId,
      String name,
      NodeType type,
      String ancestorIds,
      long size,
      String mimeType)
      throws SQLException {
    long now = System.currentTimeMillis();
    boolean isFolderLike = type == NodeType.FOLDER || type == NodeType.ROOT;
    short nodeCategory = type == NodeType.ROOT ? (short) 0 : isFolderLike ? (short) 1 : (short) 2;

    try (Connection connection = jdbcConnection()) {
      try (PreparedStatement node =
          connection.prepareStatement(
              "INSERT INTO node (owner_id, node_id, folder_id, name, node_type, node_category,"
                  + " description, index_status, creation_timestamp, updated_timestamp,"
                  + " creator_id, editor_id, current_version, ancestor_ids, size, hidden)"
                  + " VALUES (?, ?, ?, ?, ?, ?, '', 1, ?, ?, ?, NULL, ?, ?, ?, false)")) {
        node.setString(1, ownerId);
        node.setString(2, nodeId);
        node.setString(3, parentId);
        node.setString(4, name);
        node.setString(5, type.name());
        node.setShort(6, nodeCategory);
        node.setLong(7, now);
        node.setLong(8, now);
        node.setString(9, creatorId);
        if (isFolderLike) {
          node.setNull(10, Types.INTEGER);
        } else {
          node.setInt(10, 1);
        }
        node.setString(11, ancestorIds);
        node.setLong(12, size);
        node.executeUpdate();
      }

      if (!isFolderLike) {
        try (PreparedStatement revision =
            connection.prepareStatement(
                "INSERT INTO revision (node_id, version, mime_type, size, digest, editor_id,"
                    + " timestamp, is_autosave, keep_forever, cloned_from_version)"
                    + " VALUES (?, 1, ?, ?, '', ?, ?, false, false, NULL)")) {
          revision.setString(1, nodeId);
          revision.setString(2, mimeType);
          revision.setLong(3, size);
          revision.setString(4, ownerId);
          revision.setLong(5, now);
          revision.executeUpdate();
        }
      }
    }
    tickClock();
  }

  /**
   * Raw-JDBC {@code share} row insert for a target-equals-owner "share with myself" pre-state:
   * {@code createShareFetcher} explicitly REJECTS {@code targetUserId.equals(ownerId)} with a
   * {@code shareCreationError} (see {@code ShareDataFetcher#createShareFetcher}), so a node shared
   * with its own owner is NOT producible via the public API. Mirrors {@code
   * ShareRepository#upsertShare(nodeId, targetUserId, ACL.decode(permission), true, false,
   * Optional.empty())}'s persisted row shape exactly (direct=true, created_via_link=false, no
   * expiry).
   */
  protected static void seedShareRawJdbc(String nodeId, String targetUserId, ACL.SharePermission permission)
      throws SQLException {
    try (Connection connection = jdbcConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "INSERT INTO share (node_id, rights, timestamp, target_uuid, expire_date, direct,"
                    + " created_via_link) VALUES (?, ?, ?, ?, NULL, true, false)")) {
      statement.setString(1, nodeId);
      statement.setShort(2, permission.encode());
      statement.setLong(3, System.currentTimeMillis());
      statement.setString(4, targetUserId);
      statement.executeUpdate();
    }
  }

  // ------------------------------------------------------------------------------ page tokens

  /**
   * Forges a {@code findNodes} page-token JSON (base64-encoded) with the {@code signature} field
   * OMITTED entirely. Ported verbatim (pure string building, no {@code @Inject}/in-JVM secret) from
   * the deleted seam's {@code QuarkusTestDataAccess#forgeTamperedPageTokenMissingSignature}.
   */
  protected static String forgeTamperedPageTokenMissingSignature() {
    return Base64.getEncoder()
        .encodeToString(buildTamperedPageTokenJson(null).getBytes(StandardCharsets.UTF_8));
  }

  /**
   * Forges a {@code findNodes} page-token JSON (base64-encoded) with a deliberately WRONG {@code
   * signature} value. Ported verbatim from {@code
   * QuarkusTestDataAccess#forgeTamperedPageTokenWithWrongSignature}.
   */
  protected static String forgeTamperedPageTokenWithWrongSignature(String wrongSignature) {
    return Base64.getEncoder()
        .encodeToString(
            buildTamperedPageTokenJson(wrongSignature).getBytes(StandardCharsets.UTF_8));
  }

  private static String buildTamperedPageTokenJson(String signature) {
    String jsonKeySet =
        "{\"operator\":\"OR\",\"expressions\":["
            + "{\"column\":\"node_category\",\"order\":\"ASCENDING\",\"value\":1},"
            + "{\"operator\":\"AND\",\"expressions\":["
            + "{\"column\":\"node_category\",\"order\":\"EQUAL\",\"value\":1},"
            + "{\"column\":\"name\",\"order\":\"ASCENDING\",\"value\":\"folder child\"}]}]}";

    String signatureField = signature == null ? "" : "\"signature\": \"" + signature + "\",\n  ";

    return String.format(
        """
        {
          %s"limit": 1,
          "keywords": [],
          "keySet": %s,
          "sort": "NAME_ASC",
          "flagged": null,
          "folderId": "77777777-7777-7777-7777-777777777777",
          "cascade": null,
          "sharedWithMe": null,
          "sharedByMe": null,
          "directShare": null,
          "nodeType": null,
          "ownerId": null
        }""",
        signatureField, jsonKeySet);
  }
}
