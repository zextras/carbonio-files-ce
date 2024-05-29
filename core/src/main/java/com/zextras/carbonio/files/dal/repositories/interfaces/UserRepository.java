// SPDX-FileCopyrightText: 2022 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.dal.repositories.interfaces;

import com.zextras.carbonio.files.dal.dao.User;
import com.zextras.carbonio.files.dal.dao.UserMyself;
import com.zextras.carbonio.usermanagement.entities.UserId;
import io.vavr.control.Try;

import java.util.Optional;

public interface UserRepository {

  /**
   * Retrieves a {@link UserMyself} without caching the response and/or checking if it already retrieved since it is not requested often and we always want the updated version.
   *
   * @param cookies is a {@link String} representing the cookie of the requester used to fetch all the metadata of the requester itself
   * @return a {@link Optional} of the requested {@link UserMyself} if the cookie is valid, otherwise it returns an {@link Optional#empty}
   */
  Optional<UserMyself> getUserMyselfByCookieNotCached(String cookies);

  Optional<User> getUserById(String cookies, String userId);

  Optional<User> getUserByEmail(String cookies, String userEmail);

  Try<UserId> validateToken(String carbonioUserToken);
}
