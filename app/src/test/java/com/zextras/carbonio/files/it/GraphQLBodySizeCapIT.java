// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.it;

import com.zextras.carbonio.files.FilesStackTestResource;
import com.zextras.carbonio.files.it.support.AbstractFilesIT;
import io.restassured.RestAssured;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * F2 (Quarkus-rewrite hardening restoration): the legacy Netty pipeline capped both GraphQL
 * routes' body at 256KB with {@code new HttpObjectAggregator(256 * 1024)} (see {@code
 * core/.../HttpRoutingHandler#channelRead0}, lines ~99-108 for {@code /graphql}, ~199-207 for
 * {@code /public/graphql}). {@code FilesGraphQLRoutes} ported the Vert.x {@code BodyHandler} but
 * never set a body limit on it (Vert.x's default is unlimited), so the cap was silently dropped.
 *
 * <p>Every request is sent via the JDK {@link HttpClient} with {@code
 * BodyPublishers.ofInputStream} (chunked transfer-encoding, no {@code Content-Length} header) to
 * prove the cap holds against actual bytes streamed in, not a trusted header — Vert.x's {@code
 * BodyHandler} tracks the running byte count itself as data arrives, so this is also a genuine
 * test of that (rather than only its Content-Length pre-check).
 *
 * <p>{@code /graphql} needs a VALID authenticated cookie: {@code FilesAuthenticationFilter} is
 * registered at a lower Vert.x route order ({@code -100}) than the GraphQL POST route, so it runs
 * BEFORE the body-size check — an unauthenticated oversized request would be rejected with 401
 * before ever reaching the body limit, which would not exercise this fix at all. This auth-before-
 * body-limit ordering pre-dates this change and is out of scope here (F2 restores the SIZE cap
 * only). {@code /public/graphql} has no auth filter at all, so no cookie is needed there.
 */
class GraphQLBodySizeCapIT extends AbstractFilesIT {

  private static final String REQUESTER_ID = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
  private static final String REQUESTER_COOKIE = "ZM_AUTH_TOKEN=fake-token";

  /** Comfortably over the legacy 256KB (256 * 1024 byte) cap. */
  private static final int OVER_256_KB = 256 * 1024 + 4096;

  @BeforeAll
  static void registerUsers() {
    FilesStackTestResource.getUserManagementService().registerToken("fake-token", REQUESTER_ID);
  }

  private static String baseUrl() {
    return RestAssured.baseURI + ":" + RestAssured.port;
  }

  private static byte[] oversizedJsonBody() {
    byte[] filler = new byte[OVER_256_KB];
    Arrays.fill(filler, (byte) 'a');
    String prefix = "{\"query\":\"";
    byte[] prefixBytes = prefix.getBytes(StandardCharsets.UTF_8);
    byte[] result = new byte[prefixBytes.length + filler.length];
    System.arraycopy(prefixBytes, 0, result, 0, prefixBytes.length);
    System.arraycopy(filler, 0, result, prefixBytes.length, filler.length);
    return result;
  }

  private static HttpResponse<String> postChunked(String path, byte[] body, String cookie)
      throws Exception {
    HttpRequest.Builder builder =
        HttpRequest.newBuilder(URI.create(baseUrl() + path))
            .version(HttpClient.Version.HTTP_1_1)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(body)));
    if (cookie != null) {
      builder.header("Cookie", cookie);
    }
    HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
    return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
  }

  @Test
  void givenAnOversizedChunkedBodyGraphqlShouldReturn413() throws Exception {
    HttpResponse<String> response =
        postChunked("/graphql", oversizedJsonBody(), REQUESTER_COOKIE);

    Assertions.assertThat(response.statusCode()).isEqualTo(413);
  }

  @Test
  void givenAnOversizedChunkedBodyPublicGraphqlShouldReturn413() throws Exception {
    HttpResponse<String> response = postChunked("/public/graphql", oversizedJsonBody(), null);

    Assertions.assertThat(response.statusCode()).isEqualTo(413);
  }
}
