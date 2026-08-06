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
import java.util.Arrays;
import java.util.List;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * {@code com.zextras.carbonio.files.acceptance.ExceptionResidueApiIT} rewritten as an
 * out-of-process {@code @QuarkusIntegrationTest} on {@link AbstractFilesIT}: exception-handling
 * branches not exercised by any other {@code *ApiIT} — the {@code /upload-to} oversized-body guard
 * and the tampered-page-token {@code InvalidTokenSignException} path.
 *
 * <p><b>The seam's per-TEST (not per-class) app lifecycle is OBSOLETE under
 * {@code @QuarkusIntegrationTest} and is dropped here.</b> The seam's elaborate
 * {@code @BeforeEach}/{@code @AfterEach} app rebuild existed ONLY to work around a real bug in the
 * seam's OWN real-HTTP harness (see the deleted class's FINDING #3): {@code
 * java.net.http.HttpClient} connection pooling could race a still-closing connection from the
 * oversized-body test into the NEXT test, landing on a stale Netty pipeline that had already had
 * {@code HttpRoutingHandler#channelRead0}'s route-matched handler added once — a "Duplicate handler
 * name" clash unique to that bespoke pipeline-mutation-per-request design. The out-of-process app
 * under test here is a plain RESTEasy Reactive/Vert.x server with no such per-route pipeline
 * mutation (confirmed by reading {@code ProcedureResource}, the Quarkus port of the legacy {@code
 * ProcedureController}, which has no pipeline-touching code at all) — so this harness bug cannot
 * recur, and every {@code AbstractFilesIT} class already shares one launched app for the whole
 * suite, cleaned up per-method by {@link #resetDb()}.
 *
 * <p><b>FINDING (carried over, and CONFIRMED still true under Quarkus):</b> {@code
 * ProcedureResource}'s javadoc states it reproduces the legacy Netty {@code
 * HttpObjectAggregator(256 * 1024)}'s behaviour on purpose: an explicit {@code Content-Length}
 * check ({@code UPLOAD_TO_MAX_BODY_SIZE_BYTES = 256 * 1024}) runs FIRST, ahead of authentication,
 * returning a bare {@code Response.status(413).build()} — an EMPTY body, not {@code
 * ExceptionsHandler}'s {@code "413 Request Entity Too Large"} string (there is no such handler in
 * the Quarkus app at all). No cookie is required to reach this branch.
 *
 * <p><b>FINDING (carried over):</b> every throw site of {@code InvalidTokenSignException} is
 * confined to {@code PageQuery} ({@code fromToken}/{@code computeHmac}/{@code toToken}), invoked
 * ONLY from the {@code findNodes} / public {@code findNodes} GraphQL data-fetchers. Both run inside
 * graphql-java's query-execution engine, which catches any {@link RuntimeException} thrown by a
 * {@code DataFetcher} and converts it into a {@code GraphQLError} entry in the (still-200) response
 * — the exception never propagates to an HTTP-level exception mapper. The actual,
 * empirically-observed status is 200 with a GraphQL execution error, asserted explicitly here.
 */
class ExceptionResidueApiIT extends AbstractFilesIT {

  private static final String OWNER_ID = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
  private static final String OWNER_COOKIE = "ZM_AUTH_TOKEN=fake-token";

  @BeforeAll
  static void registerUsers() {
    FilesStackTestResource.getUserManagementService().registerToken("fake-token", OWNER_ID);
  }

  /** Creates a public link and returns its public id (the last 50 chars of the returned url). */
  private static String createLink(String nodeId, String ownerCookie) {
    String mutation =
        GraphqlCommandBuilder.aMutationBuilder("createLink")
            .withString("node_id", nodeId)
            .withWantedResultFormat("{ url }")
            .build();
    Response response = graphql(mutation, ownerCookie);
    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    String url =
        (String)
            TestUtils.jsonResponseToMap(response.getBody().asString(), "createLink").get("url");
    return url.substring(url.length() - 50);
  }

  /**
   * {@code /upload-to}'s {@code Content-Length} guard (see {@code ProcedureResource}) sits FIRST in
   * the resource method, ahead of authentication. So an over-limit request is rejected before
   * authentication — no cookie is required to reach this branch at all.
   */
  @Test
  void givenABodyOverTheContentLengthLimitUploadToShouldReturn413WithAnEmptyBody() {
    // Given — a body genuinely larger than /upload-to's 256KB Content-Length guard.
    byte[] oversizedBody = new byte[300_000];
    Arrays.fill(oversizedBody, (byte) 'x');

    // When — deliberately no Cookie header: the guard runs before auth.
    Response response =
        RestAssured.given().contentType("application/json").body(oversizedBody).post("/upload-to");

    // Then — 413, EMPTY body: ProcedureResource's own guard, ported to reproduce the legacy
    // aggregator's empty-body short-circuit exactly (not a JSON error payload).
    Assertions.assertThat(response.getStatusCode()).isEqualTo(413);
    Assertions.assertThat(response.getBody().asString()).isEmpty();
  }

  /**
   * Reproduces the exact fixture/forged-token construction already used by {@code
   * PublicFindNodesApiIT}'s "hacked page token without signature" test, but states the STATUS-code
   * mapping explicitly (this class's stated purpose), rather than only the GraphQL error message.
   * The forged token's embedded {@code folderId} is a fixed, unrelated literal by design (see
   * {@code PublicFindNodesApiIT}'s class javadoc): this legacy-shaped JSON fails to
   * Jackson-deserialize against the port's actual {@code PageToken} shape at all (a MALFORMED token
   * — see {@code NodeRepositoryImpl#decodeToken}), before that embedded value — or any signature —
   * is ever consulted, so the seeded nodes below need not (and structurally cannot, since
   * API-seeding cannot choose a caller ID) share the forged token's hard-coded ids.
   */
  @Test
  void givenATamperedPageTokenTheActualStatusIs200WithAGraphQLErrorNotAnHttp401() {
    // Given — a public folder (the query target) plus an unrelated, non-public folder tree (a
    // decoy an attacker might try to pivot into), ported for scenario fidelity only.
    String publicFolderId = seedFolder("public folder", LOCAL_ROOT, OWNER_COOKIE);
    String publicId = createLink(publicFolderId, OWNER_COOKIE);
    String notPublicFolderId = seedFolder("not public folder", LOCAL_ROOT, OWNER_COOKIE);
    seedFolder("folder child", notPublicFolderId, OWNER_COOKIE);
    seedFolder("folder child 2", notPublicFolderId, OWNER_COOKIE);

    String pageTokenHacked = forgeTamperedPageTokenMissingSignature();

    String query =
        GraphqlCommandBuilder.aQueryBuilder("findNodes")
            .withString("folder_id", publicFolderId)
            .withInteger("limit", 1)
            .withString("node_link_id", publicId)
            .withString("page_token", pageTokenHacked)
            .withWantedResultFormat("{ nodes { id name }, page_token }")
            .build();

    // When
    Response response = publicGraphql(query);

    // Then — the ACTUAL, observed behaviour: 200 with a GraphQL error, not a 401 HTTP status.
    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);

    List<String> errors = TestUtils.jsonResponseToErrors(response.getBody().asString());
    // The forged JSON is the LEGACY PageQuery shape (keySet/signature fields foreign to the
    // port's actual PageToken), so it fails to Jackson-deserialize at all: a MALFORMED token, not
    // a signature mismatch (see NodeRepositoryImpl#decodeToken) — the message must not claim a
    // signature was checked when the failure never got that far.
    Assertions.assertThat(errors)
        .hasSize(1)
        .containsExactly("Exception while fetching data (/findNodes) : Malformed page token");
  }
}
