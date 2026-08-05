// SPDX-FileCopyrightText: 2022 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.dal.repositories.interfaces;

import com.zextras.carbonio.files.dal.dao.UserInfo;
import com.zextras.carbonio.files.dal.dao.UserMyself;

import java.util.Optional;

public interface UserRepository {

  Optional<UserMyself> getUserMyselfByCookie(String cookies);

  Optional<UserInfo> getUserById(String cookies, String userId);

  Optional<UserInfo> getUserByEmail(String cookies, String userEmail);
}
