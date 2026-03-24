// SPDX-FileCopyrightText: 2024 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.rest.repositories;

import com.zextras.carbonio.files.dal.dao.UserInfo;
import com.zextras.carbonio.files.dal.dao.UserMyself;
import com.zextras.carbonio.files.dal.repositories.impl.ebean.UserRepositoryRest;
import com.zextras.carbonio.user_management.sdk.grpc.GetUserByIdRequest;
import com.zextras.carbonio.user_management.sdk.grpc.GetUserMyselfRequest;
import com.zextras.carbonio.user_management.sdk.grpc.UserInfoProto;
import com.zextras.carbonio.user_management.sdk.grpc.UserInfoResponse;
import com.zextras.carbonio.user_management.sdk.grpc.UserManagementServiceGrpc;
import com.zextras.carbonio.user_management.sdk.grpc.UserManagementServiceGrpc.UserManagementServiceBlockingStub;
import com.zextras.carbonio.user_management.sdk.grpc.UserManagementServiceGrpc.UserManagementServiceImplBase;
import com.zextras.carbonio.user_management.sdk.grpc.UserMyselfProto;
import com.zextras.carbonio.user_management.sdk.grpc.UserMyselfResponse;
import com.zextras.carbonio.user_management.sdk.grpc.UserTypeProto;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.Status;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Optional;

class UserRepositoryRestTest {

    private static final String SERVER_NAME = "um-repo-test";

    private UserRepositoryRest userRepositoryRest;
    private Server grpcServer;
    private ManagedChannel channel;

    @BeforeEach
    void setup() throws IOException {
        // Start an in-process gRPC server with a fake UM service
        grpcServer = InProcessServerBuilder.forName(SERVER_NAME)
            .directExecutor()
            .addService(new FakeUserManagementService())
            .build()
            .start();

        channel = InProcessChannelBuilder.forName(SERVER_NAME)
            .directExecutor()
            .build();

        UserManagementServiceBlockingStub stub =
            UserManagementServiceGrpc.newBlockingStub(channel);

        userRepositoryRest = new UserRepositoryRest(stub);
    }

    @AfterEach
    void tearDown() {
        if (channel != null) {
            channel.shutdownNow();
        }
        if (grpcServer != null) {
            grpcServer.shutdownNow();
        }
    }

    @Test
    void givenValidCookieGetUserMyselfByCookieNotCachedShouldContainUserMyself() {
        // When
        Optional<UserMyself> returnedUserMyselfOpt =
            userRepositoryRest.getUserMyselfByCookieNotCached("ZM_AUTH_TOKEN=valid-token");

        // Then
        Assertions.assertThat(returnedUserMyselfOpt).isPresent();
        UserMyself user = returnedUserMyselfOpt.get();
        Assertions.assertThat(user.getId().getUserId()).isEqualTo("fake-user-id");
        Assertions.assertThat(user.getEmail()).isEqualTo("fake@example.com");
        Assertions.assertThat(user.getFullName()).isEqualTo("Fake User");
        Assertions.assertThat(user.getDomain()).isEqualTo("example.com");
    }

    @Test
    void givenInvalidCookieGetUserMyselfByCookieNotCachedShouldReturnEmpty() {
        // When
        Optional<UserMyself> returnedUserMyselfOpt =
            userRepositoryRest.getUserMyselfByCookieNotCached("ZM_AUTH_TOKEN=invalid-token");

        // Then
        Assertions.assertThat(returnedUserMyselfOpt).isEmpty();
    }

    @Test
    void givenValidUserIdGetUserByIdShouldReturnUserInfo() {
        // When
        Optional<UserInfo> returnedUserInfoOpt =
            userRepositoryRest.getUserById("ZM_AUTH_TOKEN=valid-token", "fake-user-id");

        // Then
        Assertions.assertThat(returnedUserInfoOpt).isPresent();
        UserInfo userInfo = returnedUserInfoOpt.get();
        Assertions.assertThat(userInfo.getId().getUserId()).isEqualTo("fake-user-id");
        Assertions.assertThat(userInfo.getEmail()).isEqualTo("fake@example.com");
    }

    /**
     * Fake gRPC service implementation for testing.
     */
    private static class FakeUserManagementService extends UserManagementServiceImplBase {

        @Override
        public void getUserMyself(GetUserMyselfRequest request,
            StreamObserver<UserMyselfResponse> responseObserver) {
            if ("valid-token".equals(request.getToken())) {
                UserInfoProto info = UserInfoProto.newBuilder()
                    .setUserId("fake-user-id")
                    .setEmail("fake@example.com")
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
                responseObserver.onNext(
                    UserMyselfResponse.newBuilder().setUser(myself).build());
                responseObserver.onCompleted();
            } else {
                responseObserver.onError(
                    Status.UNAUTHENTICATED.withDescription("Invalid token")
                        .asRuntimeException());
            }
        }

        @Override
        public void getUserById(GetUserByIdRequest request,
            StreamObserver<UserInfoResponse> responseObserver) {
            if ("fake-user-id".equals(request.getUserId())) {
                UserInfoProto info = UserInfoProto.newBuilder()
                    .setUserId("fake-user-id")
                    .setEmail("fake@example.com")
                    .setFullName("Fake User")
                    .setDomain("example.com")
                    .setStatus("active")
                    .setType(UserTypeProto.INTERNAL)
                    .build();
                responseObserver.onNext(
                    UserInfoResponse.newBuilder().setUser(info).build());
                responseObserver.onCompleted();
            } else {
                responseObserver.onError(
                    Status.NOT_FOUND.withDescription("User not found")
                        .asRuntimeException());
            }
        }
    }
}
