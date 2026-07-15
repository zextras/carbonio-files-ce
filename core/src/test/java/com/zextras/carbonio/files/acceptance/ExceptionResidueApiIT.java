// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.acceptance;

import com.zextras.carbonio.files.TestUtils;
import com.zextras.carbonio.files.acceptance.seam.FilesTestApp;
import com.zextras.carbonio.files.acceptance.seam.impl.GuiceNettyFilesTestAppBuilder;
import com.zextras.carbonio.files.api.utilities.GraphqlCommandBuilder;
import com.zextras.carbonio.files.api.utilities.entities.PopulatorNode;
import com.zextras.carbonio.files.dal.dao.ebean.NodeType;
import com.zextras.carbonio.files.utilities.http.HttpRequest;
import com.zextras.carbonio.files.utilities.http.HttpResponse;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Task 4.3 of the acceptance coverage-expansion plan: {@code ExceptionsHandler} branches not
 * exercised by any other {@code *ApiIT} -- oversized-aggregated-request and
 * {@code InvalidTokenSignException}.
 *
 * <p><b>Per-TEST (not per-class) {@link FilesTestApp} lifecycle -- deliberate, see FINDING #3
 * on the first test below:</b> unlike every other {@code *ApiIT} in this suite (which shares one
 * {@code @BeforeAll}/{@code @AfterAll} app for the whole class), this class rebuilds and fully
 * closes the app around EACH test. The connection-abort this class's first scenario deliberately
 * provokes is unsafe to share with any other test's connection pool (real-HTTP transport) or
 * Simulator lifecycle (both transports) -- see FINDING #3.
 */
class ExceptionResidueApiIT {

  FilesTestApp app;

  private static final String REQUESTER_ID = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";

  @BeforeEach
  void init() {
    app =
        GuiceNettyFilesTestAppBuilder.aFilesTestApp()
            .withDatabase()
            .withServiceDiscover()
            .withUserManagement(Map.of("fake-token", REQUESTER_ID))
            .build();
    app.backdoor().resetDatabase();
  }

  @AfterEach
  void cleanUp() {
    app.close();
  }

  /**
   * {@code /upload-to}'s {@code HttpObjectAggregator(256 * 1024)} (see {@code
   * HttpRoutingHandler#channelRead0}) sits FIRST in the pipeline, ahead of the auth-handler. So
   * an over-limit request is rejected before authentication -- no cookie is required to reach
   * this branch at all.
   *
   * <p><b>FINDING #1 (seam-usage correction -- required to even exercise this branch):</b> {@code
   * FilesTestApp#sendForm} cannot reliably reproduce an oversized request on both transports.
   * {@code java.net.http.HttpClient} treats {@code Content-Length} as a JDK-restricted header
   * (verified empirically: setting it explicitly throws {@code IllegalArgumentException:
   * restricted header name: "Content-Length"} from {@code RealHttpFilesTestApp#exchange}) and
   * always computes it itself from the real body -- so the real-HTTP transport needs no trick.
   * But {@code TestUtils#sendFormRequest} (the embedded transport) builds ONE single-shot {@code
   * DefaultFullHttpRequest} that already carries the whole body; per Netty's own {@code
   * MessageAggregator#decode} (traced from the actual 4.2.16.Final classes in this project's
   * dependency tree), the accumulated-size recheck only runs for a message delivered as SEPARATE
   * {@code HttpContent}/{@code LastHttpContent} objects after an initial headers-only {@code
   * HttpRequest} -- never for an already-complete {@code FullHttpMessage}, and with no {@code
   * Content-Length} header at all (which {@code sendFormRequest} never sets) the header-based
   * check is skipped too. Empirically, sending a 300000-byte body via {@code sendForm} on the
   * embedded transport sailed straight through the aggregator to the auth-handler (observed:
   * HTTP 401 "Missing cookies", not 413) -- so {@code sendForm} cannot exercise this branch on
   * the embedded transport at all. {@link FilesTestApp#upload} is the correct call instead: it
   * writes a headers-only {@code DefaultHttpRequest} (with a real, byte-array-derived {@code
   * Content-Length}) followed by a separate {@code DefaultLastHttpContent} on the embedded
   * transport, and a real socket write with an accurate JDK-computed {@code Content-Length} on
   * the real-HTTP transport -- both paths land on the SAME Netty header-based oversized check
   * ({@code isContentLengthInvalid}), symmetrically, regardless of {@code /upload-to}'s body
   * being JSON rather than a blob (the seam's {@code upload()} is transport-plumbing only, blind
   * to content semantics).
   *
   * <p><b>FINDING #2 (the brief's "413 via ExceptionsHandler" assumption is WRONG):</b>
   * decompiling the actual Netty 4.2.16.Final classes in use ({@code
   * HttpObjectAggregator#handleOversizedMessage}) shows that once the declared {@code
   * Content-Length} exceeds {@code maxContentLength}, Netty's aggregator itself writes a static
   * {@code TOO_LARGE}/{@code TOO_LARGE_CLOSE} {@code DefaultFullHttpResponse(HTTP_1_1,
   * REQUEST_ENTITY_TOO_LARGE, Unpooled.EMPTY_BUFFER)} DIRECTLY and closes the connection --
   * BEFORE the request ever reaches {@code auth-handler}, {@code procedure-handler}, or {@code
   * ExceptionsHandler}. So {@code ExceptionsHandler}'s own {@code RequestEntityTooLargeException}
   * branch (whose payload would be the string {@code "413 Request Entity Too Large"}) is NEVER
   * exercised by this path: the actual, observed body is EMPTY, not that string. Confirmed
   * empirically below on both transports, not merely inferred from bytecode.
   *
   * <p><b>FINDING #3 (real-HTTP-transport harness bug -- worked around by per-test app isolation,
   * not papered over):</b> initially this scenario ran against a class-shared, {@code
   * @BeforeAll}-built {@code app} (the pattern every other {@code *ApiIT} uses). On the real-HTTP
   * transport that corrupted whichever test ran AFTER it: {@code java.net.http.HttpClient}
   * pools/reuses the underlying TCP connection across calls made through the same client
   * instance, and {@code RealHttpFilesTestApp}'s comment ("every request closes its channel...
   * so there is no HTTP keep-alive reuse and thus no per-request pipeline-mutation clash") does
   * not hold for THIS branch: Netty's aggregator closes the connection asynchronously via a
   * listener, and the client's pool can race ahead and reuse the not-yet-fully-closed connection
   * for the next request; when it does, {@code HttpRoutingHandler#channelRead0} runs its
   * route-matched {@code context.pipeline().addLast(...)} mutation a SECOND time on the SAME
   * (stale) pipeline, which already has a handler at that name from the first request --
   * observed empirically as {@code IllegalArgumentException: Duplicate handler name:
   * exceptions-handler}, itself caught by the STALE exceptions-handler and mapped to a spurious
   * 400 on the FOLLOWING test. This is a real test-harness limitation (not a src/main bug, and
   * not something this task is scoped to fix in the shared seam). Worked around at the CLASS
   * level (see this class's javadoc): every test in this file gets its own freshly-built,
   * fully-closed-before-the-next-one {@link FilesTestApp} ({@code @BeforeEach}/{@code
   * @AfterEach}, not {@code @BeforeAll}/{@code @AfterAll}) -- an in-process, single, per-test
   * {@code FilesTestApp} (attempted first) is NOT safe either: {@code Simulator}'s database
   * wiring registers a process-wide Ebean/HikariCP default that a second, concurrently-open
   * Simulator's teardown tears down out from under the first (observed empirically as {@code
   * HikariDataSource (files-db-pool) has been closed} on the OTHER test) -- so isolation must be
   * temporal (never two {@code Simulator}s alive at once), not merely a second parallel instance.
   */
  @Test
  void givenABodyOverTheAggregatorLimitUploadToShouldReturn413WithAnEmptyBodyNotExceptionsHandlersPayload() {
    // Given -- a body genuinely larger than /upload-to's 256KB HttpObjectAggregator limit.
    byte[] oversizedBody = new byte[300_000];
    Arrays.fill(oversizedBody, (byte) 'x');

    // When
    HttpResponse httpResponse =
        app.upload(HttpRequest.ofUpload("POST", "/upload-to", null, null, oversizedBody));

    // Then -- 413, but the body is EMPTY: Netty's own aggregator short-circuit, not
    // ExceptionsHandler's REQUEST_ENTITY_TOO_LARGE branch (which would read "413 Request Entity
    // Too Large"). Document the divergence rather than asserting the naive expectation.
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(413);
    Assertions.assertThat(httpResponse.getBodyPayload()).isEmpty();
  }

  /**
   * Reproduces the exact fixture/forged-token construction already used by {@code
   * PublicFindNodesApiIT}'s "hacked page token without signature" test, but states the STATUS-code
   * mapping explicitly (this class's stated purpose), rather than only the GraphQL error message.
   *
   * <p><b>FINDING (the brief's "InvalidTokenSignException -> 401" assumption is WRONG / this
   * branch is UNREACHABLE from any traced HTTP path):</b> every throw site of {@code
   * InvalidTokenSignException} is confined to {@code PageQuery} ({@code fromToken}/{@code
   * computeHmac}/{@code toToken}), which is invoked ONLY from within the {@code findNodes} /
   * public {@code findNodes} GraphQL data-fetchers (via {@code NodeRepositoryEbean}) -- plus one
   * unrelated throw in {@code FilesConfig#generateHmacSha256Key} (only reachable if the JVM lacks
   * {@code HmacSHA256}, a standard algorithm guaranteed by every conforming JDK, and only invoked
   * at config-generation time, never per-request). Both authenticated and public {@code
   * findNodes} run inside graphql-java's query-execution engine, which catches any {@link
   * RuntimeException} thrown by a {@code DataFetcher} and converts it into a {@code
   * GraphQLError} entry in the (still-200) response -- the exception NEVER propagates to the
   * Netty pipeline's {@code exceptionCaught} chain, so {@code ExceptionsHandler}'s {@code
   * InvalidTokenSignException -> 401} branch can never fire from any request this suite (or, as
   * far as static analysis of the call graph shows, ANY HTTP request) can construct. This joins
   * the plan's confirmed-unreachable residue (alongside {@code Permissions.equals()} etc.): it is
   * a structurally dead {@code ExceptionsHandler} branch, not merely an untested one. The actual,
   * empirically-observed status is 200 with a GraphQL execution error -- asserted explicitly here
   * instead of the fictitious 401.
   */
  @Test
  void givenATamperedPageTokenTheActualStatusIs200WithAGraphQLErrorNotExceptionsHandlers401Mapping() {
    // Given -- identical fixture to PublicFindNodesApiIT's tampered-signature test: a public
    // folder (the query target) plus an unrelated private folder tree whose node ids the forged
    // token's fixed internal payload references.
    String ownerId = REQUESTER_ID;
    app.backdoor()
        .populator()
        .addNode(
            new PopulatorNode(
                "00000000-0000-0000-0000-000000000000",
                ownerId,
                ownerId,
                "LOCAL_ROOT",
                "public folder",
                "",
                NodeType.FOLDER,
                "LOCAL_ROOT",
                0L,
                null))
        .addLink(
            "54ef41f2-8edf-4023-8b70-b29441a8e8b0",
            "00000000-0000-0000-0000-000000000000",
            "abcd1234abcd1234abcd1234abcd1234abcd1234abcd1234ab",
            Optional.empty(),
            Optional.empty(),
            Optional.empty())
        .addNode(
            new PopulatorNode(
                "77777777-7777-7777-7777-777777777777",
                ownerId,
                ownerId,
                "LOCAL_ROOT",
                "not public folder",
                "",
                NodeType.FOLDER,
                "LOCAL_ROOT",
                0L,
                null))
        .addNode(
            new PopulatorNode(
                "88888888-8888-8888-8888-888888888888",
                ownerId,
                ownerId,
                "77777777-7777-7777-7777-777777777777",
                "folder child",
                "",
                NodeType.FOLDER,
                "LOCAL_ROOT,77777777-7777-7777-7777-777777777777",
                0L,
                null))
        .addNode(
            new PopulatorNode(
                "99999999-9999-9999-9999-999999999999",
                ownerId,
                ownerId,
                "77777777-7777-7777-7777-777777777777",
                "folder child 2",
                "",
                NodeType.FOLDER,
                "LOCAL_ROOT,77777777-7777-7777-7777-777777777777",
                0L,
                null));

    String pageTokenHacked = app.backdoor().forgeTamperedPageTokenMissingSignature();

    String bodyPayload =
        GraphqlCommandBuilder.aQueryBuilder("findNodes")
            .withString("folder_id", "00000000-0000-0000-0000-000000000000")
            .withInteger("limit", 1)
            .withString("node_link_id", "abcd1234abcd1234abcd1234abcd1234abcd1234abcd1234ab")
            .withString("page_token", pageTokenHacked)
            .withWantedResultFormat("{ nodes { id name }, page_token }")
            .build();

    final HttpRequest httpRequest = HttpRequest.of("POST", "/public/graphql/", null, bodyPayload);

    // When
    final HttpResponse httpResponse = app.send(httpRequest);

    // Then -- the ACTUAL, observed behaviour: 200 with a GraphQL error, not a 401 HTTP status.
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(200);

    final List<String> errors = TestUtils.jsonResponseToErrors(httpResponse.getBodyPayload());
    Assertions.assertThat(errors)
        .hasSize(1)
        .containsExactly("Exception while fetching data (/findNodes) : Invalid token signature");
  }
}
