// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.graphql.auth;

import com.zextras.carbonio.files.Constants.API.Headers;
import com.zextras.carbonio.files.dal.dao.UserMyself;
import com.zextras.carbonio.files.dal.dao.UserStatus;
import com.zextras.carbonio.files.dal.dao.UserType;
import com.zextras.carbonio.files.dal.repositories.interfaces.UserRepository;
import io.quarkus.security.AuthenticationFailedException;
import io.quarkus.security.credential.Credential;
import io.quarkus.security.identity.IdentityProviderManager;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.vertx.http.runtime.security.ChallengeData;
import io.quarkus.vertx.http.runtime.security.HttpAuthenticationMechanism;
import io.smallrye.mutiny.Uni;
import io.vertx.core.http.Cookie;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.security.Permission;
import java.security.Principal;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * HTTP-level authentication mechanism for the SmallRye GraphQL endpoint ({@code POST /graphql}).
 *
 * <p>The mechanism handles ONLY requests whose path starts with {@code /graphql}; all other paths
 * receive {@code nullItem} so other mechanisms (or the default) can handle them.
 *
 * <p>For requests on {@code /graphql}: no {@code ZM_AUTH_TOKEN} cookie → anonymous identity ({@code
 * nullItem}), which allows {@code @PermitAll} public operations ({@code getPublicNode}, {@code
 * findPublicNodes}) to run unauthenticated (CO-3482). A cookie that cannot be resolved to a known
 * user → 401. An authenticated-but-not-entitled user → 403.
 *
 * <p>Auth/forbidden challenge bodies: for 401 challenges ({@link AuthenticationFailedException}),
 * Quarkus calls {@link #sendChallenge} on this mechanism — overridden to write the stored message
 * as the plain-text body. For 403 rejections ({@link ForbiddenException}), Quarkus calls {@code
 * ctx.fail(403)} which bypasses the mechanism's challenge path entirely. A Vert.x failure handler
 * registered in {@link #registerForbiddenBodyHandler(Router)} intercepts these 403 failures on the
 * {@code /graphql} routes and writes the stored message body (same routing-context key).
 */
@ApplicationScoped
public class FilesGraphQLAuthMechanism implements HttpAuthenticationMechanism {

  public static final String IDENTITY_ATTRIBUTE = "carbonio.files.userMyself";

  static final String CTX_STATUS = "files.auth.status";
  static final String CTX_MESSAGE = "files.auth.message";

  private final UserRepository userRepository;

  @Inject
  public FilesGraphQLAuthMechanism(UserRepository userRepository) {
    this.userRepository = userRepository;
  }

  /**
   * Registers a router-level 403 error handler that writes the stored challenge message as the
   * response body. Quarkus handles {@link ForbiddenException} thrown from {@code authenticate()}
   * via {@code ctx.fail(403, ex)}, which bypasses {@link #sendChallenge} entirely — the Vert.x
   * {@code router.errorHandler(403, ...)} hook is the correct intercept point.
   *
   * <p>The handler only acts when {@code CTX_MESSAGE} is present (set exclusively by this
   * mechanism), so it does not interfere with other 403 responses in the application. If the
   * message is absent, the handler calls {@code ctx.next()} to pass control to Vert.x's default 403
   * responder.
   */
  public void registerForbiddenBodyHandler(@Observes Router router) {
    router.errorHandler(
        403,
        ctx -> {
          if (ctx.response().ended()) {
            return;
          }
          Object msg = ctx.get(CTX_MESSAGE);
          String body = msg instanceof String s ? s : "Forbidden";
          ctx.response().setStatusCode(403).end(body);
        });
  }

  @Override
  public Uni<SecurityIdentity> authenticate(RoutingContext ctx, IdentityProviderManager idm) {
    String path = ctx.normalizedPath();
    if (!"/graphql".equals(path) && !path.startsWith("/graphql/")) {
      return Uni.createFrom().nullItem();
    }

    // No cookie → anonymous identity; @PermitAll ops (getPublicNode, findPublicNodes) succeed
    // unauthenticated on /graphql. A present-but-unresolvable cookie → 401.
    Cookie zmCookie = ctx.request().getCookie(Headers.COOKIE_ZM_AUTH_TOKEN);
    if (zmCookie == null) {
      return Uni.createFrom().nullItem();
    }
    String header = ctx.request().getHeader("Cookie");
    String cookies =
        (header != null && !header.isBlank())
            ? header
            : Headers.COOKIE_ZM_AUTH_TOKEN + "=" + zmCookie.getValue();

    Optional<UserMyself> optUser = userRepository.getUserMyselfByCookie(cookies);
    if (optUser.isEmpty()) {
      ctx.put(CTX_STATUS, 401);
      ctx.put(CTX_MESSAGE, "Unable to find requested user");
      return Uni.createFrom()
          .failure(new AuthenticationFailedException("Unable to find requested user"));
    }
    UserMyself user = optUser.get();

    /*
     * Authenticated-but-not-entitled: store status 403 and throw AuthenticationFailedException so
     * sendChallenge() writes the body. ForbiddenException would bypass sendChallenge entirely and
     * produce an empty 403; AuthenticationFailedException routes through our override correctly.
     */
    if (!UserStatus.ACTIVE.equals(user.getStatus())) {
      ctx.put(CTX_STATUS, 403);
      ctx.put(CTX_MESSAGE, "User is not active");
      return Uni.createFrom().failure(new AuthenticationFailedException("User is not active"));
    }
    if (UserType.GUEST.equals(user.getType())) {
      ctx.put(CTX_STATUS, 403);
      ctx.put(CTX_MESSAGE, "User is not internal");
      return Uni.createFrom().failure(new AuthenticationFailedException("User is not internal"));
    }
    if (!"TRUE"
        .equals(
            user.getCarbonioAttributes().getOrDefault("carbonioFeatureFilesEnabled", "FALSE"))) {
      ctx.put(CTX_STATUS, 403);
      ctx.put(CTX_MESSAGE, "Files feature is not enabled for user");
      return Uni.createFrom()
          .failure(new AuthenticationFailedException("Files feature is not enabled for user"));
    }

    Map<String, Object> attrs = new HashMap<>();
    attrs.put(IDENTITY_ATTRIBUTE, user);
    attrs.put("cookies", cookies);

    SecurityIdentity identity =
        new SecurityIdentity() {
          @Override
          public Principal getPrincipal() {
            return user::getEmail;
          }

          @Override
          public boolean isAnonymous() {
            return false;
          }

          @Override
          public Set<String> getRoles() {
            return Set.of("files-user");
          }

          @Override
          public boolean hasRole(String role) {
            return "files-user".equals(role);
          }

          @Override
          public Set<Permission> getPermissions() {
            return Set.of();
          }

          @Override
          public <T extends Credential> T getCredential(Class<T> cls) {
            return null;
          }

          @Override
          public Set<Credential> getCredentials() {
            return Set.of();
          }

          @SuppressWarnings("unchecked")
          @Override
          public <T> T getAttribute(String key) {
            return (T) attrs.get(key);
          }

          @Override
          public Map<String, Object> getAttributes() {
            return attrs;
          }

          @Override
          public Uni<Boolean> checkPermission(Permission permission) {
            return Uni.createFrom().item(false);
          }
        };

    return Uni.createFrom().item(identity);
  }

  /**
   * Writes the stored challenge message (set by {@link #authenticate} into the routing-context
   * attributes) as the plain-text HTTP response body, preserving the status/message contract that
   * the legacy {@code FilesAuthenticationFilter} wrote directly: 401 for genuine auth failures
   * (missing/unresolvable credentials) and 403 for authenticated-but-not-entitled users (CO-3482).
   *
   * <p>Called by Quarkus for {@link AuthenticationFailedException} (401 path). For {@link
   * ForbiddenException} (403 path), Quarkus uses {@code ctx.fail(403)} and the failure handler
   * registered by {@link #registerForbiddenBodyHandler(Router)} writes the body instead.
   */
  @Override
  public Uni<Boolean> sendChallenge(RoutingContext ctx) {
    Object rawStatus = ctx.get(CTX_STATUS);
    Object rawMessage = ctx.get(CTX_MESSAGE);
    int status = rawStatus instanceof Integer ? (Integer) rawStatus : 401;
    String message = rawMessage instanceof String ? (String) rawMessage : "Unauthorized";
    return Uni.createFrom()
        .emitter(
            emitter ->
                ctx.response()
                    .setStatusCode(status)
                    .end(message)
                    .onComplete(
                        ar -> {
                          if (ar.succeeded()) {
                            emitter.complete(true);
                          } else {
                            emitter.fail(ar.cause());
                          }
                        }));
  }

  @Override
  public Uni<ChallengeData> getChallenge(RoutingContext ctx) {
    Object rawStatus = ctx.get(CTX_STATUS);
    int status = rawStatus instanceof Integer ? (Integer) rawStatus : 401;
    return Uni.createFrom().item(new ChallengeData(status, null, null));
  }

  @Override
  public Set<Class<? extends io.quarkus.security.identity.request.AuthenticationRequest>>
      getCredentialTypes() {
    return Set.of();
  }
}
