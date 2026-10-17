// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.graphql.auth;

import com.zextras.carbonio.files.dal.dao.UserMyself;
import com.zextras.carbonio.files.dal.dao.UserStatus;
import com.zextras.carbonio.files.dal.dao.UserType;
import com.zextras.carbonio.files.dal.repositories.interfaces.UserRepository;
import io.quarkus.security.AuthenticationFailedException;
import io.quarkus.security.credential.Credential;
import io.quarkus.security.identity.AuthenticationRequestContext;
import io.quarkus.security.identity.IdentityProvider;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.vertx.http.runtime.security.HttpSecurityUtils;
import io.smallrye.mutiny.Uni;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.security.Permission;
import java.security.Principal;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Resolves a {@link FilesAuthenticationRequest} to a {@link SecurityIdentity} through the
 * user-management lookup. The lookup is a blocking HTTP call, so it always runs inside {@link
 * AuthenticationRequestContext#runBlocking} to keep the Vert.x event loop free (the legacy stack
 * attached its filter as a {@code blockingHandler} for the same reason).
 */
@ApplicationScoped
public class FilesIdentityProvider implements IdentityProvider<FilesAuthenticationRequest> {

  private static final Logger logger = LoggerFactory.getLogger(FilesIdentityProvider.class);

  private final UserRepository userRepository;

  @Inject
  public FilesIdentityProvider(UserRepository userRepository) {
    this.userRepository = userRepository;
  }

  @Override
  public Class<FilesAuthenticationRequest> getRequestType() {
    return FilesAuthenticationRequest.class;
  }

  @Override
  public Uni<SecurityIdentity> authenticate(
      FilesAuthenticationRequest request, AuthenticationRequestContext context) {
    RoutingContext ctx = HttpSecurityUtils.getRoutingContextAttribute(request);
    return context.runBlocking(() -> resolve(request, ctx));
  }

  private SecurityIdentity resolve(FilesAuthenticationRequest request, RoutingContext ctx) {
    String cookies = request.getCookies();
    Optional<UserMyself> optUser = userRepository.getUserMyselfByCookie(cookies);
    if (optUser.isEmpty()) {
      throw reject(
          ctx, 401, "Failed to authenticate request", request, "Unable to find requested user");
    }
    UserMyself user = optUser.get();

    if (!UserStatus.ACTIVE.equals(user.getStatus())) {
      throw reject(ctx, 403, "Failed to authorize request", request, "User is not active");
    }
    if (UserType.GUEST.equals(user.getType())) {
      throw reject(ctx, 403, "Failed to authorize request", request, "User is not internal");
    }
    if ("FALSE"
        .equals(
            user.getCarbonioAttributes().getOrDefault("carbonioFeatureFilesEnabled", "FALSE"))) {
      throw reject(
          ctx,
          403,
          "Failed to authorize request",
          request,
          "Files feature is not enabled for user");
    }

    Map<String, Object> attrs = new HashMap<>();
    attrs.put(FilesGraphQLAuthMechanism.IDENTITY_ATTRIBUTE, user);
    attrs.put("cookies", cookies);

    return new SecurityIdentity() {
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
  }

  private static AuthenticationFailedException reject(
      RoutingContext ctx,
      int status,
      String prefix,
      FilesAuthenticationRequest request,
      String reason) {
    logger.warn(
        "Authentication rejected: status={} uri={} reason={}",
        status,
        ctx.normalizedPath(),
        reason);
    ctx.put(FilesGraphQLAuthMechanism.CTX_STATUS, status);
    ctx.put(
        FilesGraphQLAuthMechanism.CTX_MESSAGE,
        String.format("%s %s: %s", prefix, request.getUri(), reason));
    return new AuthenticationFailedException(reason);
  }
}
