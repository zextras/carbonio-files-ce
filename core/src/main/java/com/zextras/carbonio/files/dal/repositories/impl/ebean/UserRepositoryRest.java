// SPDX-FileCopyrightText: 2022 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.dal.repositories.impl.ebean;

import com.google.inject.Inject;
import com.zextras.carbonio.files.Files;
import com.zextras.carbonio.files.Files.Config.Log;
import com.zextras.carbonio.files.config.FilesConfig;
import com.zextras.carbonio.files.dal.dao.User;
import com.zextras.carbonio.files.dal.repositories.interfaces.UserRepository;
import com.zextras.carbonio.usermanagement.UserManagementClient;
import com.zextras.carbonio.usermanagement.entities.UserId;
import io.vavr.control.Try;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class UserRepositoryRest implements UserRepository {

  private static final Logger logger = LoggerFactory.getLogger(Log.LOGGER_NAME);
  private final        String usermanagementUrl;

  @Inject
  public UserRepositoryRest(FilesConfig filesConfig) {
    Properties p = filesConfig.getProperties();
    usermanagementUrl = "http://"
      + p.getProperty(Files.Config.UserManagement.URL, "127.78.0.2")
      + ":" + p.getProperty(Files.Config.UserManagement.PORT, "20001");
  }

  @Override
  public Optional<User> getUserById(
    String cookies,
    UUID userUUID
  ) {

    return Optional.ofNullable(
      UserManagementClient
        .atURL(usermanagementUrl)
        .getUserByUUID(cookies, userUUID)
        .map(userInfo -> new User(
          userInfo.getId(),
          userInfo.getFullName(),
          userInfo.getEmail(),
          userInfo.getDomain())
        )
        .getOrNull()
    );
  }

  @Override
  public Optional<User> getUserByEmail(
    String cookies,
    String userEmail
  ) {

    return Optional.ofNullable(
      UserManagementClient
        .atURL(usermanagementUrl)
        .getUserByEmail(cookies, userEmail)
        .map(userInfo -> new User(
          userInfo.getId(),
          userInfo.getFullName(),
          userInfo.getEmail(),
          userInfo.getDomain())
        )
        .getOrNull()
    );

  }

  @Override
  public Try<UserId> validateToken(String carbonioUserToken) {
    return
      UserManagementClient
        .atURL(usermanagementUrl)
        .validateUserToken(carbonioUserToken)
        .orElse(() -> {
          logger.info("Non-valid token");
          return Try.failure(new Exception("Invalid token"));
        });

  }
}
