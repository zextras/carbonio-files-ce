// SPDX-FileCopyrightText: 2022 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.dal.repositories.interfaces;

import com.zextras.carbonio.files.dal.dao.User;
import com.zextras.carbonio.usermanagement.entities.UserId;
import io.vavr.control.Try;
import java.util.Optional;
import java.util.UUID;

public interface UserRepository {

  Optional<User> getUserById(
    String cookies,
    UUID userUUID
  );

  Optional<User> getUserByEmail(
    String cookies,
    String userEmail
  );

  Try<UserId> validateToken(String carbonioUserToken);

}
