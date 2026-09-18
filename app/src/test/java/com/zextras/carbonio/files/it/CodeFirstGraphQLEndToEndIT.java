// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.it;

import com.zextras.carbonio.files.FilesStackTestResource;
import com.zextras.carbonio.files.TestUtils;
import com.zextras.carbonio.files.api.utilities.GraphqlCommandBuilder;
import com.zextras.carbonio.files.it.support.AbstractFilesIT;
import io.restassured.RestAssured;
import io.restassured.response.Response;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * End-to-end behavioral smoke for the code-first SmallRye GraphQL cutover.
 *
 * <p>Covers wiring properties unique to the code-first engine that are NOT duplicated from the
 * per-operation ApiIT classes:
 *
 * <ol>
 *   <li>{@code /public/graphql} reroute parity: the same {@code @PermitAll} query posted to {@code
 *       /public/graphql} (no cookie, legacy path — rerouted by {@link
 *       com.zextras.carbonio.files.graphql.PublicGraphQLRoute}) returns the IDENTICAL JSON body as
 *       the direct {@code /graphql} call with a valid cookie. This proves the Vert.x reroute
 *       preserves the POST body end-to-end (CO-FIXME: public-graphql forward).
 *   <li>Authenticated happy-path for a READ ({@code getNode}) and a WRITE ({@code createFolder})
 *       via the SmallRye code-first resolvers against the real Postgres Testcontainer: the JSON
 *       response shape matches the contract ({@code id}, {@code name}, {@code created_at} as a
 *       non-zero long/BigInteger on the wire).
 *   <li>Auth 401/403/happy on {@code /graphql}: exhaustively covered by {@link AuthApiIT} — NOT
 *       duplicated here.
 *   <li>Per-operation public-op / find-public-nodes / get-public-node behaviors: covered by {@link
 *       GetPublicNodeApiIT} and {@link PublicFindNodesApiIT} — NOT duplicated here.
 * </ol>
 */
class CodeFirstGraphQLEndToEndIT extends AbstractFilesIT {

  private static final String OWNER_ID = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
  private static final String OWNER_COOKIE = "ZM_AUTH_TOKEN=fake-token";

  @BeforeAll
  static void registerUsers() {
    FilesStackTestResource.getUserManagementService().registerToken("fake-token", OWNER_ID);
  }

  // ── helpers ───────────────────────────────────────────────────────────────────

  /**
   * Creates a public link for {@code nodeId} via the real {@code createLink} mutation and returns
   * the {@code public_id} (the last 50 characters of the link {@code url} — the format {@code
   * LinkDataFetcher} uses).
   */
  private String createLinkAndGetPublicId(String nodeId) {
    String mutation =
        GraphqlCommandBuilder.aMutationBuilder("createLink")
            .withString("node_id", nodeId)
            .withWantedResultFormat("{ url }")
            .build();
    Response response = graphql(mutation, OWNER_COOKIE);
    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    String url =
        (String)
            TestUtils.jsonResponseToMap(response.getBody().asString(), "createLink").get("url");
    Assertions.assertThat(url).isNotNull().hasSizeGreaterThanOrEqualTo(50);
    return url.substring(url.length() - 50);
  }

  // ── /public/graphql reroute parity ────────────────────────────────────────────

  /**
   * Posts {@code getPublicNode} to {@code /public/graphql} (no cookie, the legacy path rerouted by
   * {@code PublicGraphQLRoute}) and to {@code /graphql} (with a valid cookie, where {@code
   * getPublicNode} is {@code @PermitAll}). Both must return HTTP 200 with identical JSON, proving
   * the Vert.x reroute preserves the POST body end-to-end and that the unified SmallRye schema
   * serves the same resolver regardless of which path the request originated from.
   */
  @Test
  void givenPublicLinkWhenSameQueryPostedToLegacyAndDirectEndpointsThenResponseBodiesMatch() {
    // Given — a file with a public link.
    String fileId =
        seedFile(
            "parity-file.txt", LOCAL_ROOT, "parity".getBytes(StandardCharsets.UTF_8), OWNER_COOKIE);
    String publicId = createLinkAndGetPublicId(fileId);

    String query =
        GraphqlCommandBuilder.aQueryBuilder("getPublicNode")
            .withString("node_link_id", publicId)
            .withWantedResultFormat("{ ... on PublicFile { id name } }")
            .build();
    String payload = TestUtils.queryPayload(query);

    // When — POST to /public/graphql (no cookie, legacy path, rerouted)
    Response legacyResponse =
        RestAssured.given().contentType("application/json").body(payload).post("/public/graphql/");

    // When — POST to /graphql (with a valid cookie; getPublicNode is @PermitAll so it succeeds)
    Response directResponse =
        RestAssured.given()
            .contentType("application/json")
            .header("Cookie", OWNER_COOKIE)
            .body(payload)
            .post("/graphql/");

    // Then — both 200, same JSON body.
    Assertions.assertThat(legacyResponse.getStatusCode())
        .as("/public/graphql (rerouted) must return 200")
        .isEqualTo(200);
    Assertions.assertThat(directResponse.getStatusCode())
        .as("/graphql (direct, with cookie) must return 200")
        .isEqualTo(200);

    // No GraphQL errors on either path.
    Assertions.assertThat(TestUtils.jsonResponseToErrors(legacyResponse.getBody().asString()))
        .as("no errors on /public/graphql")
        .isEmpty();
    Assertions.assertThat(TestUtils.jsonResponseToErrors(directResponse.getBody().asString()))
        .as("no errors on /graphql")
        .isEmpty();

    // Bodies are identical — the reroute preserved the POST payload end-to-end.
    Assertions.assertThat(legacyResponse.getBody().asString())
        .as(
            "/public/graphql (rerouted) and /graphql (direct) must return the same JSON for the"
                + " same @PermitAll getPublicNode query")
        .isEqualTo(directResponse.getBody().asString());
  }

  // ── authenticated happy-path: getNode (read) ─────────────────────────────────

  /**
   * An authenticated {@code getNode} query returns the node's {@code id} and {@code name} from the
   * real Postgres Testcontainer, proving the SmallRye code-first resolver chain runs end-to-end.
   */
  @Test
  @SuppressWarnings("unchecked")
  void givenAuthenticatedUserWhenGetNodeCalledThenNodeIdAndNameAreReturned() {
    // Given
    String folderId = seedFolder("e2e-read-folder", LOCAL_ROOT, OWNER_COOKIE);

    String query =
        GraphqlCommandBuilder.aQueryBuilder("getNode")
            .withString("node_id", folderId)
            .withWantedResultFormat("{ id name }")
            .build();

    // When
    Response response = graphql(query, OWNER_COOKIE);

    // Then
    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    Assertions.assertThat(TestUtils.jsonResponseToErrors(response.getBody().asString())).isEmpty();

    Map<String, Object> node =
        TestUtils.jsonResponseToMap(response.getBody().asString(), "getNode");
    Assertions.assertThat(node).isNotNull();
    Assertions.assertThat(node.get("id")).isEqualTo(folderId);
    Assertions.assertThat(node.get("name")).isEqualTo("e2e-read-folder");
  }

  // ── authenticated happy-path: createFolder (mutation) ────────────────────────

  /**
   * An authenticated {@code createFolder} mutation returns the created folder's {@code id}, {@code
   * name} and {@code created_at} — proving the SmallRye mutation resolver + BigInteger wire
   * serialization work end-to-end against the real Postgres Testcontainer.
   */
  @Test
  void givenAuthenticatedUserWhenCreateFolderMutationCalledThenFolderIsCreatedWithCorrectShape() {
    // Given / When
    String mutation =
        GraphqlCommandBuilder.aMutationBuilder("createFolder")
            .withString("destination_id", LOCAL_ROOT)
            .withString("name", "e2e-mutation-folder")
            .withWantedResultFormat("{ id name created_at }")
            .build();
    Response response = graphql(mutation, OWNER_COOKIE);

    // Then
    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    Assertions.assertThat(TestUtils.jsonResponseToErrors(response.getBody().asString())).isEmpty();

    Map<String, Object> folder =
        TestUtils.jsonResponseToMap(response.getBody().asString(), "createFolder");
    Assertions.assertThat(folder).isNotNull();
    Assertions.assertThat(folder.get("id"))
        .as("server-generated id must be a non-null string")
        .isNotNull()
        .isInstanceOf(String.class);
    Assertions.assertThat(folder.get("name")).isEqualTo("e2e-mutation-folder");
    Assertions.assertThat(((Number) folder.get("created_at")).longValue())
        .as("created_at is a BigInteger/long on the wire, must be a positive epoch-millis")
        .isGreaterThan(0L);
  }

  // ── unauthenticated findPublicNodes on /public/graphql ────────────────────────

  /**
   * {@code findPublicNodes} (a {@code @PermitAll} op) via {@code /public/graphql} (no cookie)
   * succeeds and returns a {@code PublicNodePage} with the child node. Complements {@link
   * PublicFindNodesApiIT}'s per-scenario coverage with a smoke check that the rerouted path reaches
   * the correct code-first resolver.
   *
   * <p>{@code findPublicNodes} takes the ACTUAL folder UUID as {@code folder_id} plus the
   * 50-character {@code node_link_id} (the public link's {@code public_id}) — same convention as
   * the old {@code findNodes} public op.
   */
  @Test
  @SuppressWarnings("unchecked")
  void givenPublicFolderLinkWhenFindPublicNodesCalledViaLegacyEndpointThenPageIsReturned() {
    // Given — a public-link folder with one file child.
    String folderId = seedFolder("public-parent", LOCAL_ROOT, OWNER_COOKIE);
    seedFile("public-child.txt", folderId, "data".getBytes(StandardCharsets.UTF_8), OWNER_COOKIE);
    String folderPublicLinkId = createLinkAndGetPublicId(folderId);

    String query =
        GraphqlCommandBuilder.aQueryBuilder("findPublicNodes")
            .withString("folder_id", folderId)
            .withString("node_link_id", folderPublicLinkId)
            .withInteger("limit", 10)
            .withWantedResultFormat("{ nodes { ... on PublicFile { id name } } }")
            .build();

    // When — via /public/graphql (no cookie, rerouted to /graphql with anonymous identity)
    Response response = publicGraphql(query);

    // Then — 200, no errors, at least the child is returned
    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    Assertions.assertThat(TestUtils.jsonResponseToErrors(response.getBody().asString())).isEmpty();

    Map<String, Object> page =
        TestUtils.jsonResponseToMap(response.getBody().asString(), "findPublicNodes");
    Assertions.assertThat(page).isNotNull();

    List<Map<String, Object>> nodes = (List<Map<String, Object>>) page.get("nodes");
    Assertions.assertThat(nodes).isNotNull().isNotEmpty();
    // The system stores the base name without extension: "public-child.txt" → name="public-child"
    Assertions.assertThat(nodes).extracting(n -> n.get("name")).contains("public-child");
  }
}
