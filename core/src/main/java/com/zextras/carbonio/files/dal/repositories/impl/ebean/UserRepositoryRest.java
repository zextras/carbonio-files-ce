// SPDX-FileCopyrightText: 2022 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.dal.repositories.impl.ebean;

import com.google.inject.Inject;
import com.zextras.carbonio.files.cache.Cache;
import com.zextras.carbonio.files.cache.CacheHandler;
import com.zextras.carbonio.files.dal.repositories.interfaces.UserRepository;
import com.zextras.carbonio.usermanagement.UserManagementClient;
import com.zextras.carbonio.usermanagement.entities.UserInfo;
import com.zextras.carbonio.usermanagement.entities.UserMyself;

import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/*
  * This class is responsible for fetching user data from the user management service.
  * Brief explanation of the caches because there is a cachenception here:
  * we cache users in Files, and User Management also caches users; we can tell user management to ignore its cache
  * and retrieve the user fresh, but when we do so we want to also ignore the Files cache otherwise it would be useless;
  * we do not have a cache on Files for the getUserMyself method because it is not requested often and we always want the updated version.
  * So, aside from getUserMyself, if ignoreCache is true we ignore both Files and UM cache.
  *
  * Dev update: we agreed that having a cache on UserManagement is not optimal since user's data can change rather rapidly
  * (think of userStatus) and we would like to control this cache at the service level; so
  * TODO when cache is removed from UM we should implement a reasonable cache on Files auth methods.
 */
public class UserRepositoryRest implements UserRepository {

  private static final Logger logger = LoggerFactory.getLogger(UserRepositoryRest.class);

  private final Cache<UserInfo> userCache;
  private final UserManagementClient userManagementClient;

  @Inject
  public UserRepositoryRest(CacheHandler cacheHandler, UserManagementClient userManagementClient) {
    this.userManagementClient = userManagementClient;
    userCache = cacheHandler.getUserCache();
  }

  //TODO when we remove the cache from UM, we should implement some kind of optional cache system on Files,
  // since sometimes we want the fresh user object while other times it's not needed
  @Override
  public Optional<UserMyself> getUserMyselfByCookieNotCached(String cookies) {
    return userManagementClient
        .getUserMyself(cookies, true)
        .onFailure(failure -> logger.error(failure.getMessage()))
        .toJavaOptional();
  }

  @Override
  public Optional<UserInfo> getUserById(String cookies, String userId, boolean ignoreCache) {
    return (ignoreCache ? Optional.<UserInfo>empty() : userCache.get(userId))
        .or(
            () ->
                userManagementClient
                  .getUserById(cookies, userId, ignoreCache)
                  .onFailure(failure -> logger.error(failure.getMessage()))
                  .peek(
                      userInfo -> {
                          userCache.add(userInfo.getId().getUserId(), userInfo);
                          userCache.add(userInfo.getEmail(), userInfo);
                      })
                  .toJavaOptional());
  }

  @Override
  public Optional<UserInfo> getUserByEmail(String cookies, String userEmail, boolean ignoreCache) {
    return (ignoreCache ? Optional.<UserInfo>empty() : userCache.get(userEmail))
        .or(
            () ->
                userManagementClient
                  .getUserByEmail(cookies, userEmail, ignoreCache)
                  .onFailure(failure -> logger.error(failure.getMessage()))
                  .peek(
                      userInfo -> {
                          userCache.add(userInfo.getId().getUserId(), userInfo);
                          userCache.add(userInfo.getEmail(), userInfo);
                      })
                  .toJavaOptional());
  }
}
