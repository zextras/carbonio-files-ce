// SPDX-FileCopyrightText: 2022 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.dal.repositories.interfaces;

import com.zextras.carbonio.files.dal.dao.UserInfo;
import com.zextras.carbonio.files.dal.dao.UserMyself;

import java.util.Optional;

public interface UserRepository {

  /**
   * @param cookies is a {@link String} representing the cookie of the requester used to fetch all the metadata of the requester itself
   * @return a {@link Optional} of the requested {@link UserMyself} if the cookie is valid, otherwise it returns an {@link Optional#empty}
   */
  Optional<UserMyself> getUserMyselfByCookie(String cookies);

  Optional<UserInfo> getUserById(String cookies, String userId);

  Optional<UserInfo> getUserByEmail(String cookies, String userEmail);
}
