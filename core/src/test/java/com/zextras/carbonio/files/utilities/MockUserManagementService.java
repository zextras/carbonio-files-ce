// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.utilities;

import com.zextras.carbonio.user_management.sdk.grpc.GetUserByEmailRequest;
import com.zextras.carbonio.user_management.sdk.grpc.GetUserByIdRequest;
import com.zextras.carbonio.user_management.sdk.grpc.GetUserMyselfRequest;
import com.zextras.carbonio.user_management.sdk.grpc.UserInfoProto;
import com.zextras.carbonio.user_management.sdk.grpc.UserInfoResponse;
import com.zextras.carbonio.user_management.sdk.grpc.UserManagementServiceGrpc.UserManagementServiceImplBase;
import com.zextras.carbonio.user_management.sdk.grpc.UserMyselfProto;
import com.zextras.carbonio.user_management.sdk.grpc.UserMyselfResponse;
import com.zextras.carbonio.user_management.sdk.grpc.UserTypeProto;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory gRPC service implementation for UserManagement. Supports
 * {@code getUserMyself} (token lookup), {@code getUserById} (userId lookup),
 * and {@code getUserByEmail} (email lookup).
 */
public class MockUserManagementService extends UserManagementServiceImplBase {

  private final Map<String, UserMyselfResponse> tokenToMyself = new ConcurrentHashMap<>();
  private final Map<String, UserInfoProto> userIdToInfo = new ConcurrentHashMap<>();

  /**
   * Registers a minimal token-to-userId mapping. A full {@link UserMyselfResponse} is built
   * with default values for email, name, domain, status, locale, and the
   * "carbonioFeatureFilesEnabled" feature enabled.
   */
  public void registerToken(String token, String userId) {
    if (!tokenToMyself.containsKey(token)) {
      UserInfoProto info = UserInfoProto.newBuilder()
          .setUserId(userId)
          .setEmail("fake-email@example.com")
          .setFullName("Fake User")
          .setDomain("example.com")
          .setStatus("active")
          .setType(UserTypeProto.INTERNAL)
          .build();
      UserMyselfProto myself = UserMyselfProto.newBuilder()
          .setInfo(info)
          .setLocale("en")
          .addFeatures("carbonioFeatureFilesEnabled")
          .build();
      tokenToMyself.put(token, UserMyselfResponse.newBuilder().setUser(myself).build());
      userIdToInfo.put(userId, info);
    }
  }

  /**
   * Registers (or OVERWRITES — unlike {@link #registerToken(String, String)}, which is a no-op if
   * the token already exists) a token-to-userId mapping with explicit account status, type, and
   * feature-flag presence. Used by acceptance tests that need to drive {@code
   * AuthenticationHandler}'s non-happy-path branches: inactive user (status != ACTIVE), guest user
   * (type == GUEST), and feature-disabled user (the "carbonioFeatureFilesEnabled" feature key
   * absent from the features list, which {@code UserMyself} maps to "FALSE").
   *
   * @param status a raw UM status string (e.g. "active", "maintenance", "closed", "locked", ...),
   *     matched case-insensitively against {@link com.zextras.carbonio.files.dal.dao.UserStatus}
   *     by the production mapper ({@code UserRepositoryRest#mapStatus}).
   * @param type INTERNAL or GUEST.
   * @param filesFeatureEnabled whether "carbonioFeatureFilesEnabled" is present in the features
   *     list; its absence is mapped to "FALSE" by {@code UserMyself}.
   */
  public void registerToken(
      String token, String userId, String status, UserTypeProto type, boolean filesFeatureEnabled) {
    UserInfoProto info = UserInfoProto.newBuilder()
        .setUserId(userId)
        .setEmail("fake-email@example.com")
        .setFullName("Fake User")
        .setDomain("example.com")
        .setStatus(status)
        .setType(type)
        .build();
    UserMyselfProto.Builder myselfBuilder =
        UserMyselfProto.newBuilder().setInfo(info).setLocale("en");
    if (filesFeatureEnabled) {
      myselfBuilder.addFeatures("carbonioFeatureFilesEnabled");
    }
    tokenToMyself.put(token, UserMyselfResponse.newBuilder().setUser(myselfBuilder.build()).build());
    userIdToInfo.put(userId, info);
  }

  /**
   * Removes a userId from the {@code getUserById} lookup map so that subsequent
   * {@code getUserById} calls for this user will return NOT_FOUND.
   */
  public void unregisterUserById(String userId) {
    userIdToInfo.remove(userId);
  }

  /**
   * Registers a user profile for lookup by userId via {@code getUserById}.
   * This is used by integration tests that need to look up users other than
   * the requester (e.g. transfer ownership target user).
   */
  public void registerUserById(String userId, String email, String fullName,
      String domain, String status) {
    UserInfoProto info = UserInfoProto.newBuilder()
        .setUserId(userId)
        .setEmail(email)
        .setFullName(fullName)
        .setDomain(domain)
        .setStatus(status)
        .setType(UserTypeProto.INTERNAL)
        .build();
    userIdToInfo.put(userId, info);
  }

  public void clearAll() {
    tokenToMyself.clear();
    userIdToInfo.clear();
  }

  @Override
  public void getUserMyself(GetUserMyselfRequest request,
      StreamObserver<UserMyselfResponse> responseObserver) {
    String token = request.getToken();
    UserMyselfResponse response = tokenToMyself.get(token);
    if (response != null) {
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } else {
      responseObserver.onError(
          Status.UNAUTHENTICATED.withDescription("Invalid token").asRuntimeException());
    }
  }

  @Override
  public void getUserById(GetUserByIdRequest request,
      StreamObserver<UserInfoResponse> responseObserver) {
    String userId = request.getUserId();
    UserInfoProto info = userIdToInfo.get(userId);
    if (info != null) {
      responseObserver.onNext(UserInfoResponse.newBuilder().setUser(info).build());
      responseObserver.onCompleted();
    } else {
      responseObserver.onError(
          Status.NOT_FOUND.withDescription("User not found").asRuntimeException());
    }
  }

  @Override
  public void getUserByEmail(GetUserByEmailRequest request,
      StreamObserver<UserInfoResponse> responseObserver) {
    String email = request.getUserEmail();
    // Search by email across registered users
    for (UserInfoProto info : userIdToInfo.values()) {
      if (info.getEmail().equals(email)) {
        responseObserver.onNext(UserInfoResponse.newBuilder().setUser(info).build());
        responseObserver.onCompleted();
        return;
      }
    }
    responseObserver.onError(
        Status.NOT_FOUND.withDescription("User not found by email").asRuntimeException());
  }
}
