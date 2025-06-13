// SPDX-FileCopyrightText: 2022 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.dal.repositories.impl.ebean;

import com.google.inject.Inject;
import com.zextras.carbonio.files.Constants;
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

/*
  * This class is responsible for fetching user data from the user management service.
  * Brief explanation of the caches because there is a cachenception here:
  * we cache users in Files, and User Management also caches users; we can tell user management to ignore its cache
  * and retrieve the user fresh, but when we do so we want to also ignore the Files cache otherwise it would be useless;
  * we do not have a cache on Files for the getUserMyself method because it is not requested often and we always want the updated version.
  * So, aside from getUserMyself, if ignoreCache is true we ignore both Files and UM cache.
 */
public class UserRepositoryRest implements UserRepository {

  private static final Logger logger = LoggerFactory.getLogger(UserRepositoryRest.class);

  private final String usermanagementUrl;
  private final Cache<User> userCache;

  @Inject
  public UserRepositoryRest(FilesConfig filesConfig, CacheHandler cacheHandler) {
    Properties p = filesConfig.getProperties();
    usermanagementUrl =
        "http://"
            + p.getProperty(Constants.Config.UserManagement.URL, "127.78.0.2")
            + ":"
            + p.getProperty(Constants.Config.UserManagement.PORT, "20001");

    userCache = cacheHandler.getUserCache();
  }

  // no cache on this one since it is not requested often and we always want the updated version
  @Override
  public Optional<UserMyself> getUserMyselfByCookieNotCached(String cookies) {
    return UserManagementClient.atURL(usermanagementUrl)
        .getUserMyself(cookies, true)
        .onFailure(failure -> logger.error(failure.getMessage()))
        .map(
            userInfo ->
                new UserMyself(
                    userInfo.getId().getUserId(),
                    userInfo.getFullName(),
                    userInfo.getEmail(),
                    userInfo.getDomain(),
                    userInfo.getLocale(),
                    userInfo.getType()))
        .toJavaOptional();
  }

  @Override
  public Optional<User> getUserById(String cookies, String userId, boolean ignoreCache) {
    return (ignoreCache ? Optional.<User>empty() : userCache.get(userId))
        .or(
            () ->
                UserManagementClient.atURL(usermanagementUrl)
                  .getUserById(cookies, userId, ignoreCache)
                  .onFailure(failure -> logger.error(failure.getMessage()))
                  .map(
                      userInfo -> {
                          User user = new User(
                            userInfo.getId().getUserId(),
                            userInfo.getFullName(),
                            userInfo.getEmail(),
                            userInfo.getDomain(),
                            userInfo.getStatus(),
                            userInfo.getType());
                          userCache.add(user.getId(), user);
                          userCache.add(user.getEmail(), user);
                          return user;
                      })
                  .toJavaOptional());
  }

  @Override
  public Optional<User> getUserByEmail(String cookies, String userEmail, boolean ignoreCache) {
    return (ignoreCache ? Optional.<User>empty() : userCache.get(userEmail))
        .or(
            () ->
                UserManagementClient.atURL(usermanagementUrl)
                  .getUserByEmail(cookies, userEmail, ignoreCache)
                  .onFailure(failure -> logger.error(failure.getMessage()))
                  .map(
                      userInfo -> {
                          User user = new User(
                            userInfo.getId().getUserId(),
                            userInfo.getFullName(),
                            userInfo.getEmail(),
                            userInfo.getDomain(),
                            userInfo.getStatus(),
                            userInfo.getType());
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
