// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.graphql;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;

import com.zextras.carbonio.files.FilesStackTestResource;
import com.zextras.carbonio.files.dal.dao.ebean.NodeType;
import com.zextras.carbonio.files.dal.repositories.interfaces.LinkRepository;
import com.zextras.carbonio.files.dal.repositories.interfaces.NodeRepository;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * End-to-end smoke test proving the P3e GraphQL engine wiring: the graphql-java providers, the
 * Vert.x routes, the authentication filter (backed by an in-process user-management gRPC stub) and
 * the request-scoped DataLoader execution (no {@code ContextNotActiveException}).
 *
 * <p>Uses {@code @QuarkusTest} so data can be seeded in-JVM via the injected repositories (which
 * persist through the request-scoped {@code EntityManager} and commit). Seed rows use fresh UUIDs
 * per test, so they never collide with the shared singleton Postgres of other IT classes.
 */
@QuarkusTest
@QuarkusTestResource(FilesStackTestResource.class)
class GraphQLWiringIT {

  @Inject NodeRepository nodeRepository;
  @Inject LinkRepository linkRepository;

  private String authFolderId;
  private String publicFolderId;
  private String publicLinkPublicId;

  @BeforeEach
  void seed() {
    // Authenticated node: a folder OWNED by the stubbed test user, so PermissionsChecker grants
    // OWNER permissions and getNode returns it.
    authFolderId = UUID.randomUUID().toString();
    nodeRepository.createNewNode(
        authFolderId,
        FilesStackTestResource.TEST_USER_ID,
        FilesStackTestResource.TEST_USER_ID,
        "LOCAL_ROOT",
        "auth-folder-" + authFolderId,
        "desc",
        NodeType.FOLDER,
        "LOCAL_ROOT",
        0L);

    // Public node: a folder reachable via a non-expired public link (no access code).
    publicFolderId = UUID.randomUUID().toString();
    nodeRepository.createNewNode(
        publicFolderId,
        "public-owner",
        "public-owner",
        "LOCAL_ROOT",
        "public-folder-" + publicFolderId,
        "desc",
        NodeType.FOLDER,
        "LOCAL_ROOT",
        0L);

    publicLinkPublicId = UUID.randomUUID().toString().replace("-", "");
    linkRepository.createLink(
        UUID.randomUUID().toString(),
        publicFolderId,
        publicLinkPublicId,
        Optional.empty(),
        Optional.empty(),
        Optional.empty());
  }

  // -------------------------------------------------------------------- (a) public /public/graphql

  @Test
  void publicGraphQlResolvesGetPublicNodeAgainstSeededData() {
    String query =
        "{\"query\":\"query { getPublicNode(node_link_id: \\\""
            + publicLinkPublicId
            + "\\\") { id name type } }\"}";

    RestAssured.given()
        .contentType(ContentType.JSON)
        .body(query)
        .when()
        .post("/public/graphql")
        .then()
        .statusCode(200)
        .body("errors", nullValue())
        .body("data.getPublicNode.id", equalTo(publicFolderId))
        .body("data.getPublicNode.name", equalTo("public-folder-" + publicFolderId))
        .body("data.getPublicNode.type", equalTo("FOLDER"));
  }

  // --------------------------------------------------------- (b) authenticated /graphql + DataLoader

  @Test
  void authenticatedGraphQlResolvesGetNodeTriggeringDataLoader() {
    // getNode is wired to a DataLoader (NodeBatchLoader); a 200 with the node payload and NO errors
    // proves the loader ran on the request-scoped thread (no ContextNotActiveException).
    String query =
        "{\"query\":\"query { getNode(node_id: \\\""
            + authFolderId
            + "\\\") { id name type } }\"}";

    RestAssured.given()
        .contentType(ContentType.JSON)
        .cookie("ZM_AUTH_TOKEN", FilesStackTestResource.AUTH_TOKEN)
        .body(query)
        .when()
        .post("/graphql")
        .then()
        .statusCode(200)
        .body("errors", nullValue())
        .body("data.getNode.id", equalTo(authFolderId))
        .body("data.getNode.name", equalTo("auth-folder-" + authFolderId))
        .body("data.getNode.type", equalTo("FOLDER"));
  }

  // ------------------------------------------------------------------------ (c) auth failure → 401

  @Test
  void authenticatedGraphQlWithMissingCookieReturns401() {
    String query = "{\"query\":\"query { getNode(node_id: \\\"whatever\\\") { id } }\"}";

    RestAssured.given()
        .contentType(ContentType.JSON)
        .body(query)
        .when()
        .post("/graphql")
        .then()
        .statusCode(401);
  }

  @Test
  void authenticatedGraphQlWithInvalidCookieReturns401() {
    String query = "{\"query\":\"query { getNode(node_id: \\\"whatever\\\") { id } }\"}";

    RestAssured.given()
        .contentType(ContentType.JSON)
        .cookie("ZM_AUTH_TOKEN", "not-a-valid-token-" + System.nanoTime())
        .body(query)
        .when()
        .post("/graphql")
        .then()
        .statusCode(401);
  }
}
