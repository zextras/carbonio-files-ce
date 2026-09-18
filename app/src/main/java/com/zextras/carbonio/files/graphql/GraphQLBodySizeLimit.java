// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.graphql;

import io.vertx.ext.web.Router;
import io.vertx.ext.web.handler.BodyHandler;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;

/**
 * Restores the 256 KiB body-size cap that the legacy Netty pipeline enforced for GraphQL requests
 * via {@code HttpObjectAggregator(256 * 1024)}.
 *
 * <p>The global Quarkus HTTP body-size limit is intentionally disabled ({@code
 * quarkus.http.limits.max-body-size=} empty in {@code application.properties}) so large file
 * uploads are never pre-empted by an HTTP-layer cap. The GraphQL route does not serve large
 * payloads (it carries JSON query strings), so the legacy 256 KiB cap is safe and correct for it.
 *
 * <p>A route-specific {@link BodyHandler} with {@code bodyLimit} set to 256 KiB is registered at
 * order {@code -200} — before SmallRye GraphQL's own handler — for {@code POST /graphql}.
 */
@ApplicationScoped
public class GraphQLBodySizeLimit {

  /** 256 KiB — matches the legacy Netty {@code HttpObjectAggregator} cap. */
  private static final long MAX_GRAPHQL_BODY_BYTES = 256L * 1024;

  public void register(@Observes Router router) {
    BodyHandler limitedBodyHandler =
        BodyHandler.create().setBodyLimit(MAX_GRAPHQL_BODY_BYTES).setDeleteUploadedFilesOnEnd(true);
    router.post("/graphql").order(-200).handler(limitedBodyHandler);
    router.post("/graphql/").order(-200).handler(limitedBodyHandler);
  }
}
