// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.rest.resources;

import com.zextras.carbonio.files.dal.dao.UserMyself;
import com.zextras.carbonio.files.dal.repositories.interfaces.UserRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Admin-only gate for the config write endpoints, the JAX-RS counterpart of {@link
 * BlobAuthenticator} for administrators. It reads the {@code ZM_ADMIN_AUTH_TOKEN} cookie, resolves
 * the caller through user-management {@code /myself} (which reflects the mailbox attr {@code
 * zimbraIsAdminAccount} as {@code isGlobalAdmin}) and requires {@code isGlobalAdmin == true}.
 *
 * <p>This mirrors powerstore's global-admin write gate: the predicate is the same {@code
 * zimbraIsAdminAccount} flag, and reading it specifically from the {@code ZM_ADMIN_AUTH_TOKEN}
 * cookie is what scopes the endpoint to the admin-console context (a browser only sends that cookie
 * for admin-console-originated requests). Delegated / domain admins have {@code
 * zimbraIsAdminAccount=false} and are therefore rejected — global admin only, by design.
 *
 * <p>Every failure (missing token, unresolvable token, non-global-admin) is a 401.
 */
@ApplicationScoped
public class AdminAuthenticator {

  private static final Logger logger = LoggerFactory.getLogger(AdminAuthenticator.class);

  private final UserRepository userRepository;

  @Inject
  public AdminAuthenticator(UserRepository userRepository) {
    this.userRepository = userRepository;
  }

  /**
   * Resolves and authorizes a global-administrator caller from the admin auth token.
   *
   * @param adminToken the raw {@code ZM_ADMIN_AUTH_TOKEN} cookie value (may be {@code null})
   * @return the authenticated global-admin {@link UserMyself}
   * @throws WebApplicationException with status 401 if the token is missing/invalid or the account
   *     is not a global administrator
   */
  public UserMyself requireGlobalAdmin(String adminToken) {
    if (adminToken == null || adminToken.isBlank()) {
      throw unauthorized("Missing admin token");
    }

    UserMyself user =
        userRepository
            .getUserMyselfByToken(adminToken)
            .orElseThrow(() -> unauthorized("Unable to resolve admin user"));

    if (!user.isGlobalAdmin()) {
      throw unauthorized("User is not a global administrator");
    }

    return user;
  }

  private WebApplicationException unauthorized(String reason) {
    String message = "Failed to authenticate admin request: " + reason;
    logger.error(message);
    return new WebApplicationException(
        Response.status(Response.Status.UNAUTHORIZED).entity(message).type("text/plain").build());
  }
}
