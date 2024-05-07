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

  // separated from normal users cache since there can be only one usermyself
  // cached and to ditch casting
  private Optional<UserMyself> userMyselfCached;

  @Inject
  public UserRepositoryRest(FilesConfig filesConfig, CacheHandler cacheHandler) {
    Properties p = filesConfig.getProperties();
    usermanagementUrl =
        "http://"
            + p.getProperty(Files.Config.UserManagement.URL, "127.78.0.2")
            + ":"
            + p.getProperty(Files.Config.UserManagement.PORT, "20001");

    userCache = cacheHandler.getUserCache();
    userMyselfCached = Optional.empty();
  }

  @Override
  public Optional<UserMyself> getUserMyselfByCookie(
      String cookies, String userId // id only used to check myselfuser in cache
      ) {
    return userMyselfCached
        .filter(user -> user.getId().equals(userId))
        .or(
            () ->
                UserManagementClient.atURL(usermanagementUrl)
                    .getUserMyself(cookies)
                    .onFailure(failure -> logger.error(failure.getMessage()))
                    .map(
                        userInfo -> {
                          UserMyself user =
                              new UserMyself(
                                  userInfo.getId().getUserId(),
                                  userInfo.getFullName(),
                                  userInfo.getEmail(),
                                  userInfo.getDomain(),
                                  userInfo.getLocale());
                          userCache.add(user.getId(), user);
                          userCache.add(user.getEmail(), user);
                          userMyselfCached = Optional.of(user);

                          System.out.println(user.getLocale().toLanguageTag());
                          return user;
                        })
                    .toJavaOptional());
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
                                  userInfo.getDomain());
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
                                  userInfo.getDomain());
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
