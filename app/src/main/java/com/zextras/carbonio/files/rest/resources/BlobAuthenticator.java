// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.rest.resources;

import com.zextras.carbonio.files.Constants.API.Headers;
import com.zextras.carbonio.files.dal.dao.UserMyself;
import com.zextras.carbonio.files.dal.dao.UserStatus;
import com.zextras.carbonio.files.dal.dao.UserType;
import com.zextras.carbonio.files.dal.repositories.interfaces.UserRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Resolves and validates the authenticated requester for the blob REST endpoints. This is the
 * JAX-RS counterpart of {@link com.zextras.carbonio.files.graphql.FilesAuthenticationFilter}: the
 * cookie -&gt; user-management -&gt; status/type/feature checks are identical (ported 1:1 from the
 * legacy Netty {@code AuthenticationHandler}); only the transport differs.
 *
 * <p>On a genuine auth failure (missing/invalid credentials, unresolvable user) it throws a {@link
 * WebApplicationException} with HTTP 401. On an authenticated-but-not-entitled user (inactive
 * account, guest, or {@code carbonioFeatureFilesEnabled} disabled) it throws HTTP 403 instead
 * (CO-3482, ported from devel #301).
 */
@ApplicationScoped
public class BlobAuthenticator {

  private static final Logger logger = LoggerFactory.getLogger(BlobAuthenticator.class);

  private final UserRepository userRepository;

  @Inject
  public BlobAuthenticator(UserRepository userRepository) {
    this.userRepository = userRepository;
  }

  /**
   * Resolves the requester from the request cookies, applying the same checks as the GraphQL auth
   * filter. Prefers the full incoming {@code Cookie} header (kept verbatim for downstream
   * user-management lookups); falls back to a reconstructed {@code ZM_AUTH_TOKEN=...} string.
   *
   * @param cookieHeader the raw {@code Cookie} request header (may be {@code null})
   * @param zmAuthToken the {@code ZM_AUTH_TOKEN} cookie value (may be {@code null})
   * @return the authenticated {@link UserMyself}
   * @throws WebApplicationException with status 401 if authentication fails
   */
  public UserMyself requireUser(String cookieHeader, String zmAuthToken) {
    if (zmAuthToken == null || zmAuthToken.isBlank()) {
      throw unauthorized("Missing cookies");
    }

    String cookies =
        (cookieHeader != null && !cookieHeader.isBlank())
            ? cookieHeader
            : Headers.COOKIE_ZM_AUTH_TOKEN + "=" + zmAuthToken;

    Optional<UserMyself> optUser = userRepository.getUserMyselfByCookieNotCached(cookies);
    if (optUser.isEmpty()) {
      throw unauthorized("Unable to find requested user");
    }

    UserMyself user = optUser.get();

    if (!user.getStatus().equals(UserStatus.ACTIVE)) {
      throw forbidden("User is not active");
    }

    if (user.getType().equals(UserType.GUEST)) {
      throw forbidden("User is not internal");
    }

    String carbonioFeatureFilesEnabled =
        user.getCarbonioAttributes().getOrDefault("carbonioFeatureFilesEnabled", "FALSE");
    if (carbonioFeatureFilesEnabled.equals("FALSE")) {
      throw forbidden("Files feature is not enabled for user");
    }

    return user;
  }

  /**
   * Builds a 401 whose body carries the legacy {@code AuthenticationException} message shape ({@code
   * "Failed to authenticate request: <reason>"}), matching the Netty {@code ExceptionsHandler} which
   * wrote {@code cause.getMessage()} as the 401 body. Reserved for genuine auth failures. The
   * acceptance suite ({@code AuthApiIT}, blob route) asserts the body contains the reason fragment,
   * so it must not be empty.
   */
  private WebApplicationException unauthorized(String reason) {
    String message = "Failed to authenticate request: " + reason;
    logger.error(message);
    return new WebApplicationException(
        Response.status(Response.Status.UNAUTHORIZED).entity(message).type("text/plain").build());
  }

  /**
   * Builds a 403 whose body carries the legacy {@code ForbiddenException} message shape ({@code
   * "Failed to authorize request: <reason>"}), matching the Netty {@code ExceptionsHandler} which
   * wrote {@code cause.getMessage()} as the 403 body (CO-3482, ported from devel #301). Used for
   * authenticated-but-not-entitled users: inactive account, guest user, or feature disabled.
   */
  private WebApplicationException forbidden(String reason) {
    String message = "Failed to authorize request: " + reason;
    logger.error(message);
    return new WebApplicationException(
        Response.status(Response.Status.FORBIDDEN).entity(message).type("text/plain").build());
  }
}
