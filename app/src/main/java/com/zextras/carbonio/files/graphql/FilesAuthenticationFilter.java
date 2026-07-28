// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.graphql;

import com.zextras.carbonio.files.Constants.API.Headers;
import com.zextras.carbonio.files.Constants.GraphQL.Context;
import com.zextras.carbonio.files.dal.dao.UserMyself;
import com.zextras.carbonio.files.dal.dao.UserStatus;
import com.zextras.carbonio.files.dal.dao.UserType;
import com.zextras.carbonio.files.dal.repositories.interfaces.UserRepository;
import io.vertx.core.http.Cookie;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Vert.x route filter guarding the authenticated {@code /graphql} endpoint. This is the Quarkus
 * replacement for the legacy Netty {@code AuthenticationHandler}: the auth checks are ported 1:1,
 * only the transport differs (a Vert.x blocking route handler instead of a Netty channel handler).
 *
 * <p>It runs BEFORE the GraphQL route (registered with {@code order(-100)} vs. the default {@code 0}
 * of the POST handlers). On success it stores the authenticated {@link UserMyself} and the raw
 * cookie string in the {@link RoutingContext} so the GraphQL route can copy them into the graphql-java
 * context; on failure it short-circuits the request with HTTP 401 (genuine auth failure) or HTTP 403
 * (authenticated but not entitled: inactive, guest, or feature disabled -- CO-3482).
 *
 * <p>It is deliberately attached ONLY to {@code /graphql} (and {@code /graphql/}). The public
 * endpoint {@code /public/graphql} is intentionally NOT filtered — it is reachable without a token.
 */
@ApplicationScoped
public class FilesAuthenticationFilter {

  private static final Logger logger = LoggerFactory.getLogger(FilesAuthenticationFilter.class);

  private final UserRepository userRepository;

  @Inject
  public FilesAuthenticationFilter(UserRepository userRepository) {
    this.userRepository = userRepository;
  }

  /**
   * Registers the auth handler on the Vert.x router. Called once at startup when Quarkus publishes
   * the {@link Router} CDI event. A blocking handler is used because {@link
   * UserRepository#getUserMyselfByCookieNotCached(String)} performs a blocking gRPC call that must
   * not run on the event loop.
   */
  public void registerRoutes(@Observes Router router) {
    router.route("/graphql").order(-100).blockingHandler(this::filter);
    router.route("/graphql/").order(-100).blockingHandler(this::filter);
  }

  /**
   * Core auth logic, ported from the legacy {@code AuthenticationHandler}:
   *
   * <ul>
   *   <li>the {@code ZM_AUTH_TOKEN} cookie must be present and resolve to a real user (otherwise
   *       401 -- genuine authentication failure);
   *   <li>the resolved user must be {@link UserStatus#ACTIVE}, not a {@link UserType#GUEST} and
   *       have {@code carbonioFeatureFilesEnabled} enabled (otherwise 403 -- authenticated but not
   *       entitled; CO-3482).
   * </ul>
   *
   * On success the requester and cookie string are stored in the {@link RoutingContext} and the
   * request proceeds via {@link RoutingContext#next()}.
   */
  void filter(RoutingContext ctx) {
    Cookie zmCookie = ctx.request().getCookie(Headers.COOKIE_ZM_AUTH_TOKEN);
    if (zmCookie == null) {
      rejectUnauthorized(ctx, "Missing cookies");
      return;
    }

    // Prefer the full incoming Cookie header (kept verbatim in the graphql context for downstream
    // user-management lookups); fall back to a reconstructed single-cookie string.
    String cookieHeader = ctx.request().getHeader("Cookie");
    String cookies =
        (cookieHeader != null && !cookieHeader.isBlank())
            ? cookieHeader
            : Headers.COOKIE_ZM_AUTH_TOKEN + "=" + zmCookie.getValue();

    Optional<UserMyself> optUser = userRepository.getUserMyselfByCookieNotCached(cookies);
    if (optUser.isEmpty()) {
      rejectUnauthorized(ctx, "Unable to find requested user");
      return;
    }

    UserMyself user = optUser.get();

    if (!user.getStatus().equals(UserStatus.ACTIVE)) {
      rejectForbidden(ctx, "User is not active");
      return;
    }

    if (user.getType().equals(UserType.GUEST)) {
      rejectForbidden(ctx, "User is not internal");
      return;
    }

    String carbonioFeatureFilesEnabled =
        user.getCarbonioAttributes().getOrDefault("carbonioFeatureFilesEnabled", "FALSE");
    if (carbonioFeatureFilesEnabled.equals("FALSE")) {
      rejectForbidden(ctx, "Files feature is not enabled for user");
      return;
    }

    ctx.put(Context.REQUESTER, user);
    ctx.put(Context.COOKIES, cookies);
    ctx.next();
  }

  /**
   * Ends the request with HTTP 401 and the exact legacy body shape produced by the Netty {@code
   * ExceptionsHandler} for an {@code AuthenticationException}: {@code "Failed to authenticate request
   * <uri>: <reason>"}. Reserved for genuine auth failures (missing/invalid credentials, unresolvable
   * user). The acceptance suite ({@code AuthApiIT}) asserts the response body contains these reason
   * fragments, so the body must not be empty.
   */
  private void rejectUnauthorized(RoutingContext ctx, String reason) {
    String message =
        String.format("Failed to authenticate request %s: %s", ctx.request().uri(), reason);
    logger.error(message);
    ctx.response().setStatusCode(401).end(message);
  }

  /**
   * Ends the request with HTTP 403 and the body shape produced by the Netty {@code
   * ExceptionsHandler} for a {@code ForbiddenException}: {@code "Failed to authorize request
   * <uri>: <reason>"}. Used for authenticated-but-not-entitled users: inactive account, guest
   * user, or {@code carbonioFeatureFilesEnabled} disabled (CO-3482, ported from devel #301).
   */
  private void rejectForbidden(RoutingContext ctx, String reason) {
    String message =
        String.format("Failed to authorize request %s: %s", ctx.request().uri(), reason);
    logger.error(message);
    ctx.response().setStatusCode(403).end(message);
  }
}
