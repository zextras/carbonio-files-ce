// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.dal.repositories.impl;

import com.zextras.carbonio.files.dal.dao.UserId;
import com.zextras.carbonio.files.dal.dao.UserInfo;
import com.zextras.carbonio.files.dal.dao.UserMyself;
import com.zextras.carbonio.files.dal.dao.UserStatus;
import com.zextras.carbonio.files.dal.dao.UserType;
import com.zextras.carbonio.files.dal.repositories.interfaces.UserRepository;
import com.zextras.carbonio.user_management.sdk.rest.ApiException;
import com.zextras.carbonio.user_management.sdk.rest.api.UserResourceApi;
import com.zextras.carbonio.user_management.sdk.rest.model.MyselfDto;
import com.zextras.carbonio.user_management.sdk.rest.model.UserInfoDto;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * P6e: fetches user data from carbonio-user-management's REST {@code /internal/users/*} endpoints
 * via the OpenAPI-generated {@link UserResourceApi} (carbonio-user-management-rest-sdk), produced
 * by {@link com.zextras.carbonio.files.config.UserManagementClientProducer}.
 *
 * <p>Replaces the P3a gRPC-based implementation (a {@code @GrpcClient("user-management")} blocking
 * stub). Behavior is ported 1:1 from it (and, before that, from the legacy {@code
 * UserRepositoryRest}): same token-extraction rule, same response-to-domain-type mapping, same
 * empty-{@link Optional}-on-failure contract — only the transport changed.
 *
 * <p>Per user-management's REST contract, only {@code GET /internal/users/myself} requires the
 * caller's token (forwarded as the {@code ZM_AUTH_TOKEN} header); {@code GET .../id/{userId}} and
 * {@code GET .../email/{email}} are trusted forwards that need no auth (mirroring the gRPC
 * contract, whose {@code GetUserByIdRequest}/{@code GetUserByEmailRequest} never carried a token
 * either).
 *
 * <p>bump-um-sdk (1.3.0-1): {@code internalUsersMyselfGet} gained a leading {@code bypassCache}
 * query parameter and now takes the raw token as a plain {@code ZM_AUTH_TOKEN} header argument
 * (the generated client sets the header itself) instead of a caller-built {@code Map} of headers.
 */
@ApplicationScoped
public class UserRepositoryImpl implements UserRepository {

  private static final Logger logger = LoggerFactory.getLogger(UserRepositoryImpl.class);
  private static final String ZM_AUTH_TOKEN_COOKIE = "ZM_AUTH_TOKEN";

  private final UserResourceApi userResourceApi;

  @Inject
  public UserRepositoryImpl(UserResourceApi userResourceApi) {
    this.userResourceApi = userResourceApi;
  }

  @Override
  public Optional<UserMyself> getUserMyselfByCookie(String cookies) {
    try {
      String token = extractToken(cookies);
      // bypassCache=null (unset): mirrors the pre-1.3.0 behavior of not sending the query param.
      MyselfDto response = userResourceApi.internalUsersMyselfGet(null, token);
      // The generated client returns null (rather than throwing) for a 2xx response with a
      // blank body, so response can be null even though no ApiException was raised.
      return Optional.ofNullable(response).flatMap(this::mapToUserMyself);
    } catch (ApiException e) {
      logger.error("Failed to get user myself via REST: {}", e.getMessage());
      return Optional.empty();
    }
  }

  @Override
  public Optional<UserInfo> getUserById(String cookies, String userId) {
    try {
      UserInfoDto response = userResourceApi.internalUsersIdUserIdGet(userId);
      return Optional.ofNullable(response).map(this::mapToUserInfo);
    } catch (ApiException e) {
      logger.error("Failed to get user by id via REST: {}", e.getMessage());
      return Optional.empty();
    }
  }

  @Override
  public Optional<UserInfo> getUserByEmail(String cookies, String userEmail) {
    try {
      UserInfoDto response = userResourceApi.internalUsersEmailEmailGet(userEmail);
      return Optional.ofNullable(response).map(this::mapToUserInfo);
    } catch (ApiException e) {
      logger.error("Failed to get user by email via REST: {}", e.getMessage());
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
   * Maps a {@link MyselfDto} to the local {@link UserMyself} domain type. Returns {@link
   * Optional#empty()} if the nested {@code info} is missing, since a myself response without
   * user info cannot be resolved to a domain user (the field is {@code @Nullable} in the
   * generated DTO).
   */
  private Optional<UserMyself> mapToUserMyself(MyselfDto response) {
    UserInfoDto info = response.getInfo();
    if (info == null) {
      logger.warn("Missing user info in myself response, treating user as unresolvable");
      return Optional.empty();
    }
    List<String> features = response.getFeatures();
    return Optional.of(
        new UserMyself(
            new UserId(info.getUserId()),
            info.getEmail(),
            info.getFullName(),
            info.getDomain(),
            mapStatus(info.getStatus()),
            parseLocale(response.getLocale()),
            mapType(info.getType()),
            features != null ? features : Collections.emptyList()));
  }

  /** Maps a {@link UserInfoDto} to the local {@link UserInfo} domain type. */
  private UserInfo mapToUserInfo(UserInfoDto info) {
    return new UserInfo(
        new UserId(info.getUserId()),
        info.getEmail(),
        info.getFullName(),
        info.getDomain(),
        mapStatus(info.getStatus()),
        mapType(info.getType()));
  }

  /**
   * Maps a status string to the local {@link UserStatus} enum. Falls back to {@link
   * UserStatus#CLOSED} if the status string is missing or not recognized.
   */
  private UserStatus mapStatus(String status) {
    if (status == null) {
      logger.warn("Missing user status, defaulting to CLOSED");
      return UserStatus.CLOSED;
    }
    try {
      return UserStatus.valueOf(status.toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      logger.warn("Unknown user status '{}', defaulting to CLOSED", status);
      return UserStatus.CLOSED;
    }
  }

  /**
   * Maps a type string ({@code "INTERNAL"}/{@code "GUEST"}, case-insensitive) to {@link UserType}.
   * Falls back to {@link UserType#GUEST} if the string is missing or not recognized.
   *
   * <p>This fails <b>closed</b>, deliberately mirroring {@link #mapStatus(String)}: {@link
   * UserType#GUEST} is the access-denying value ({@link
   * com.zextras.carbonio.files.graphql.FilesAuthenticationFilter} blocks guests), so an
   * unresolvable type must land on the deny side rather than defaulting to {@link
   * UserType#INTERNAL}. A missing field, a {@code null}, or a UM-side typo must not silently
   * grant internal access.
   */
  private UserType mapType(String type) {
    if (type == null) {
      logger.warn("Missing user type, defaulting to GUEST (deny)");
      return UserType.GUEST;
    }
    try {
      return UserType.valueOf(type.toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      logger.warn("Unknown user type '{}', defaulting to GUEST (deny)", type);
      return UserType.GUEST;
    }
  }

  /**
   * Parses a locale string (e.g. "en_US", "it") into a {@link Locale}. Falls back to {@link
   * Locale#ENGLISH} if the string is empty or null.
   */
  private Locale parseLocale(String localeStr) {
    if (localeStr == null || localeStr.isEmpty()) {
      return Locale.ENGLISH;
    }
    return Locale.forLanguageTag(localeStr.replace('_', '-'));
  }
}
