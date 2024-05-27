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
   * no cache on this one since it is not requested often and we always want the updated version
   */
  Optional<UserMyself> getUserMyselfByCookieNotCached(String cookies);

  Optional<User> getUserById(String cookies, String userId);

  Optional<User> getUserByEmail(String cookies, String userEmail);

  Try<UserId> validateToken(String carbonioUserToken);
}
