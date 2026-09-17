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
import io.quarkus.security.ForbiddenException;
import io.quarkus.security.credential.Credential;
import io.quarkus.security.identity.IdentityProviderManager;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.vertx.http.runtime.security.ChallengeData;
import io.quarkus.vertx.http.runtime.security.HttpAuthenticationMechanism;
import io.smallrye.mutiny.Uni;
import io.vertx.core.http.Cookie;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.security.Permission;
import java.security.Principal;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@ApplicationScoped
public class FilesGraphQLAuthMechanism implements HttpAuthenticationMechanism {

  public static final String IDENTITY_ATTRIBUTE = "carbonio.files.userMyself";

  private final UserRepository userRepository;

  @Inject
  public FilesGraphQLAuthMechanism(UserRepository userRepository) {
    this.userRepository = userRepository;
  }

  @Override
  public Uni<SecurityIdentity> authenticate(RoutingContext ctx, IdentityProviderManager idm) {
    String path = ctx.normalizedPath();
    if (!"/graphql".equals(path) && !path.startsWith("/graphql/")) {
      return Uni.createFrom().nullItem();
    }

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
      return Uni.createFrom().failure(new AuthenticationFailedException("Invalid token"));
    }
    UserMyself user = optUser.get();
    if (!UserStatus.ACTIVE.equals(user.getStatus())
        || UserType.GUEST.equals(user.getType())
        || !"TRUE"
            .equals(
                user.getCarbonioAttributes()
                    .getOrDefault("carbonioFeatureFilesEnabled", "FALSE"))) {
      return Uni.createFrom().failure(new ForbiddenException());
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

  @Override
  public Uni<ChallengeData> getChallenge(RoutingContext ctx) {
    return Uni.createFrom().item(new ChallengeData(401, null, "Unauthorized"));
  }

  @Override
  public Set<Class<? extends io.quarkus.security.identity.request.AuthenticationRequest>>
      getCredentialTypes() {
    return Set.of();
  }
}
