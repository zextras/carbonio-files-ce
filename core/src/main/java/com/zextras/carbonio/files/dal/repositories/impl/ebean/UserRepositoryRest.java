// SPDX-FileCopyrightText: 2022 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.dal.repositories.impl.ebean;

import com.google.inject.Inject;
import com.zextras.carbonio.files.dal.dao.UserInfo;
import com.zextras.carbonio.files.dal.dao.UserId;
import com.zextras.carbonio.files.dal.dao.UserMyself;
import com.zextras.carbonio.files.dal.dao.UserStatus;
import com.zextras.carbonio.files.dal.dao.UserType;
import com.zextras.carbonio.files.dal.repositories.interfaces.UserRepository;
import com.zextras.carbonio.user_management.sdk.grpc.GetUserByEmailRequest;
import com.zextras.carbonio.user_management.sdk.grpc.GetUserByIdRequest;
import com.zextras.carbonio.user_management.sdk.grpc.GetUserMyselfRequest;
import com.zextras.carbonio.user_management.sdk.grpc.UserInfoProto;
import com.zextras.carbonio.user_management.sdk.grpc.UserInfoResponse;
import com.zextras.carbonio.user_management.sdk.grpc.UserManagementServiceGrpc.UserManagementServiceBlockingStub;
import com.zextras.carbonio.user_management.sdk.grpc.UserMyselfProto;
import com.zextras.carbonio.user_management.sdk.grpc.UserMyselfResponse;
import com.zextras.carbonio.user_management.sdk.grpc.UserTypeProto;
import io.grpc.StatusRuntimeException;
import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Fetches user data from the User Management gRPC service.
 */
public class UserRepositoryRest implements UserRepository {

  private static final Logger logger = LoggerFactory.getLogger(UserRepositoryRest.class);
  private static final String ZM_AUTH_TOKEN_COOKIE = "ZM_AUTH_TOKEN";

  private final UserManagementServiceBlockingStub userManagementStub;

  @Inject
  public UserRepositoryRest(UserManagementServiceBlockingStub userManagementStub) {
    this.userManagementStub = userManagementStub;
  }

  @Override
  public Optional<UserMyself> getUserMyselfByCookieNotCached(String cookies) {
    try {
      String token = extractToken(cookies);
      GetUserMyselfRequest request =
          GetUserMyselfRequest.newBuilder().setToken(token).build();
      UserMyselfResponse response = userManagementStub.getUserMyself(request);
      return Optional.of(mapToUserMyself(response.getUser()));
    } catch (StatusRuntimeException e) {
      logger.error("Failed to get user myself via gRPC: {}", e.getMessage());
      return Optional.empty();
    }
  }

  @Override
  public Optional<UserInfo> getUserById(String cookies, String userId) {
    try {
      String token = extractToken(cookies);
      GetUserByIdRequest request =
          GetUserByIdRequest.newBuilder()
              .setToken(token)
              .setUserId(userId)
              .build();
      UserInfoResponse response = userManagementStub.getUserById(request);
      return Optional.of(mapToUserInfo(response.getUser()));
    } catch (StatusRuntimeException e) {
      logger.error("Failed to get user by id via gRPC: {}", e.getMessage());
      return Optional.empty();
    }
  }

  @Override
  public Optional<UserInfo> getUserByEmail(String cookies, String userEmail) {
    try {
      String token = extractToken(cookies);
      GetUserByEmailRequest request =
          GetUserByEmailRequest.newBuilder()
              .setToken(token)
              .setUserEmail(userEmail)
              .build();
      UserInfoResponse response = userManagementStub.getUserByEmail(request);
      return Optional.of(mapToUserInfo(response.getUser()));
    } catch (StatusRuntimeException e) {
      logger.error("Failed to get user by email via gRPC: {}", e.getMessage());
      return Optional.empty();
    }
  }

  /**
   * Extracts the raw ZM_AUTH_TOKEN value from a cookie header string. The cookie string has the
   * format {@code "ZM_AUTH_TOKEN=abc123; other_cookie=xyz"}.
   *
   * @param cookies the full cookie header string
   * @return the raw token value
   */
  private String extractToken(String cookies) {
    return Arrays.stream(cookies.split(";"))
        .map(String::trim)
        .filter(c -> c.startsWith(ZM_AUTH_TOKEN_COOKIE + "="))
        .map(c -> c.substring(ZM_AUTH_TOKEN_COOKIE.length() + 1))
        .findFirst()
        .orElse(cookies);
  }

  /**
   * Maps a {@link UserMyselfProto} to the local {@link UserMyself} domain type.
   */
  private UserMyself mapToUserMyself(UserMyselfProto proto) {
    UserInfoProto info = proto.getInfo();
    return new UserMyself(
        new UserId(info.getUserId()),
        info.getEmail(),
        info.getFullName(),
        info.getDomain(),
        mapStatus(info.getStatus()),
        parseLocale(proto.getLocale()),
        mapType(info.getType()),
        proto.getFeaturesList());
  }

  /**
   * Maps a {@link UserInfoProto} to the local {@link UserInfo} domain type.
   */
  private UserInfo mapToUserInfo(UserInfoProto proto) {
    return new UserInfo(
        new UserId(proto.getUserId()),
        proto.getEmail(),
        proto.getFullName(),
        proto.getDomain(),
        mapStatus(proto.getStatus()),
        mapType(proto.getType()));
  }

  /**
   * Maps a proto status string to the local {@link UserStatus} enum. Falls back to
   * {@link UserStatus#CLOSED} if the status string is not recognized.
   */
  private UserStatus mapStatus(String status) {
    try {
      return UserStatus.valueOf(status.toUpperCase());
    } catch (IllegalArgumentException e) {
      logger.warn("Unknown user status '{}', defaulting to CLOSED", status);
      return UserStatus.CLOSED;
    }
  }

  /**
   * Maps a proto {@link UserTypeProto} to the local {@link UserType} enum.
   */
  private UserType mapType(UserTypeProto protoType) {
    switch (protoType) {
      case INTERNAL:
        return UserType.INTERNAL;
      case GUEST:
        return UserType.GUEST;
      default:
        return UserType.INTERNAL;
    }
  }

  /**
   * Parses a locale string (e.g. "en_US", "it") into a {@link Locale}. Falls back to
   * {@link Locale#ENGLISH} if the string is empty or null.
   */
  private Locale parseLocale(String localeStr) {
    if (localeStr == null || localeStr.isEmpty()) {
      return Locale.ENGLISH;
    }
    return Locale.forLanguageTag(localeStr.replace('_', '-'));
  }
}
