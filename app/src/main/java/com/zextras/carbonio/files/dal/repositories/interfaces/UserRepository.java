// SPDX-FileCopyrightText: 2022 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.dal.repositories.interfaces;

import com.zextras.carbonio.files.dal.dao.UserInfo;
import com.zextras.carbonio.files.dal.dao.UserMyself;
import java.util.List;
import java.util.Optional;

public interface UserRepository {

  /**
   * @param cookies is a {@link String} representing the cookie of the requester used to fetch all
   *     the metadata of the requester itself
   * @return a {@link Optional} of the requested {@link UserMyself} if the cookie is valid,
   *     otherwise it returns an {@link Optional#empty}
   */
  Optional<UserMyself> getUserMyselfByCookie(String cookies);

  /**
   * Resolves the authenticated user from a bare auth-token value (already extracted from its
   * cookie), forwarded to user-management {@code /myself} as-is. Used by the admin path, which
   * reads the {@code ZM_ADMIN_AUTH_TOKEN} cookie: mailbox validates the token value regardless of
   * the cookie name it arrives under, so the resolved {@link UserMyself} (including {@code
   * isGlobalAdmin}) is correct for the admin session.
   *
   * @param token the raw auth-token value (no {@code NAME=} prefix)
   * @return the resolved {@link UserMyself}, or {@link Optional#empty()} if the token is invalid
   */
  Optional<UserMyself> getUserMyselfByToken(String token);

  Optional<UserInfo> getUserById(String cookies, String userId);

  /**
   * Batch variant of {@link #getUserById}: resolves many users in a single user-management call
   * (POST /internal/users). Unknown ids are simply absent from the returned list. Cookie-less, like
   * the single-id lookup (the /internal endpoint is trusted service-to-service, gated by the mesh).
   *
   * @param userIds the user ids to resolve (must be at most the user-management batch cap of 100;
   *     the caller {@code UserBatchLoader} enforces this via {@code maxBatchSize})
   * @return the {@link UserInfo}s that exist, in no guaranteed order
   */
  List<UserInfo> getUsers(List<String> userIds);

  Optional<UserInfo> getUserByEmail(String cookies, String userEmail);
}
