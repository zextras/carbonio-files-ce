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
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Instance;
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
 * <p>On any failure it throws a {@link WebApplicationException} with HTTP 401, matching the legacy
 * behaviour of an unauthenticated blob request.
 */
@ApplicationScoped
public class BlobAuthenticator {

  private static final Logger logger = LoggerFactory.getLogger(BlobAuthenticator.class);

  private final UserRepository userRepository;

  // P9 CE seam: additional auth schemes (e.g. Advanced JWT), tried before the cookie path. CE
  // registers none, so the resolution below is unchanged.
  private final Instance<SupplementaryAuthenticator> supplementaryAuthenticators;

  @Inject
  public BlobAuthenticator(
      UserRepository userRepository,
      @Any Instance<SupplementaryAuthenticator> supplementaryAuthenticators) {
    this.userRepository = userRepository;
    this.supplementaryAuthenticators = supplementaryAuthenticators;
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
    // Try any supplementary authenticator first; the first one that resolves a user short-circuits
    // the standard cookie path. CE registers none, so this loop is a no-op.
    for (SupplementaryAuthenticator authenticator : supplementaryAuthenticators) {
      Optional<UserMyself> resolved = authenticator.authenticate(cookieHeader, zmAuthToken);
      if (resolved.isPresent()) {
        return resolved.get();
      }
    }

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
      throw unauthorized("User is not active");
    }

    if (user.getType().equals(UserType.GUEST)) {
      throw unauthorized("User is not internal");
    }

    String carbonioFeatureFilesEnabled =
        user.getCarbonioAttributes().getOrDefault("carbonioFeatureFilesEnabled", "FALSE");
    if (carbonioFeatureFilesEnabled.equals("FALSE")) {
      throw unauthorized("User is not internal");
    }

    return user;
  }

  /**
   * Builds a 401 whose body carries the legacy {@code AuthenticationException} message shape ({@code
   * "Failed to authenticate request: <reason>"}), matching the Netty {@code ExceptionsHandler} which
   * wrote {@code cause.getMessage()} as the 401 body. The acceptance suite ({@code AuthApiIT}, blob
   * route) asserts the body contains the reason fragment, so it must not be empty.
   */
  private WebApplicationException unauthorized(String reason) {
    String message = "Failed to authenticate request: " + reason;
    logger.error(message);
    return new WebApplicationException(
        Response.status(Response.Status.UNAUTHORIZED).entity(message).type("text/plain").build());
  }
}
