// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.rest.resources;

import com.zextras.carbonio.files.dal.dao.UserMyself;
import java.util.Optional;

/**
 * CE extension seam for additional authentication schemes on the blob/preview REST endpoints, tried
 * before the standard cookie -&gt; user-management path in {@link BlobAuthenticator}. CE registers
 * none, so authentication behaviour is unchanged. The Advanced edition adds a JWT-scoped
 * implementation as an {@code @ApplicationScoped} bean.
 *
 * <p>Note: {@link com.zextras.carbonio.files.graphql.FilesAuthenticationFilter} shares the same
 * cookie -&gt; user-management logic on the GraphQL route; it is a sibling site where this seam
 * could later be wired in the same way.
 */
public interface SupplementaryAuthenticator {

  /**
   * Attempts to resolve the requester from the raw request cookies. Returns a fully-authenticated
   * {@link UserMyself} (already validated by this scheme) to short-circuit the standard path, or
   * {@link Optional#empty()} to defer to the next authenticator / the standard cookie path.
   *
   * @param cookieHeader the raw {@code Cookie} request header (may be {@code null})
   * @param zmAuthToken the {@code ZM_AUTH_TOKEN} cookie value (may be {@code null})
   * @return the authenticated user, or empty to defer
   */
  Optional<UserMyself> authenticate(String cookieHeader, String zmAuthToken);
}
