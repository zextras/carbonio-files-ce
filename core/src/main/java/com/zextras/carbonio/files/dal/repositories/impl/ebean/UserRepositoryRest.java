// SPDX-FileCopyrightText: 2022 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.dal.repositories.impl.ebean;

import com.google.inject.Inject;
import com.zextras.carbonio.files.Files;
import com.zextras.carbonio.files.cache.Cache;
import com.zextras.carbonio.files.cache.CacheHandler;
import com.zextras.carbonio.files.config.FilesConfig;
import com.zextras.carbonio.files.dal.dao.User;
import com.zextras.carbonio.files.dal.dao.UserMyself;
import com.zextras.carbonio.files.dal.repositories.interfaces.UserRepository;
import com.zextras.carbonio.usermanagement.UserManagementClient;
import com.zextras.carbonio.usermanagement.entities.UserId;
import io.vavr.control.Try;
import java.util.Optional;
import java.util.Properties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class UserRepositoryRest implements UserRepository {

  private static final Logger logger = LoggerFactory.getLogger(UserRepositoryRest.class);

  private final String usermanagementUrl;
  private final Cache<User> userCache;

  @Inject
  public UserRepositoryRest(FilesConfig filesConfig, CacheHandler cacheHandler) {
    Properties p = filesConfig.getProperties();
    usermanagementUrl =
        "http://"
            + p.getProperty(Files.Config.UserManagement.URL, "127.78.0.2")
            + ":"
            + p.getProperty(Files.Config.UserManagement.PORT, "20001");

    userCache = cacheHandler.getUserCache();
  }

  // no cache on this one since it is not requested often and we always want the updated version
  @Override
  public Optional<UserMyself> getUserMyselfByCookieNotCached(String cookies) {
    return UserManagementClient.atURL(usermanagementUrl)
        .getUserMyself(cookies)
        .onFailure(failure -> logger.error(failure.getMessage()))
        .map(
            userInfo ->
                new UserMyself(
                    userInfo.getId().getUserId(),
                    userInfo.getFullName(),
                    userInfo.getEmail(),
                    userInfo.getDomain(),
                    userInfo.getLocale(),
                    userInfo.getUserType()))
        .toJavaOptional();
  }

  @Override
  public Optional<User> getUserById(String cookies, String userId) {
    return userCache
        .get(userId)
        .or(
            () ->
                UserManagementClient.atURL(usermanagementUrl)
                    .getUserById(cookies, userId)
                    .onFailure(failure -> logger.error(failure.getMessage()))
                    .map(
                        userInfo -> {
                          User user =
                              new User(
                                  userInfo.getId().getUserId(),
                                  userInfo.getFullName(),
                                  userInfo.getEmail(),
                                  userInfo.getDomain(),
                                  userInfo.getStatus(),
                                  userInfo.getUserType());
                          userCache.add(user.getId(), user);
                          userCache.add(user.getEmail(), user);

                          return user;
                        })
                    .toJavaOptional());
  }

  @Override
  public Optional<User> getUserByEmail(String cookies, String userEmail) {

    return userCache
        .get(userEmail)
        .or(
            () ->
                UserManagementClient.atURL(usermanagementUrl)
                    .getUserByEmail(cookies, userEmail)
                    .onFailure(failure -> logger.error(failure.getMessage()))
                    .map(
                        userInfo -> {
                          User user =
                              new User(
                                  userInfo.getId().getUserId(),
                                  userInfo.getFullName(),
                                  userInfo.getEmail(),
                                  userInfo.getDomain(),
                                  userInfo.getStatus(),
                                  userInfo.getUserType());
                          userCache.add(user.getId(), user);
                          userCache.add(user.getEmail(), user);

                          return user;
                        })
                    .toJavaOptional());
  }

  @Override
  public Try<UserId> validateToken(String carbonioUserToken) {
    return UserManagementClient.atURL(usermanagementUrl)
        .validateUserToken(carbonioUserToken)
        .orElse(
            () -> {
              logger.info("Non-valid token");
              return Try.failure(new Exception("Invalid token"));
            });
  }
}
