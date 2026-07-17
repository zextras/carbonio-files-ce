// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.grpc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.google.protobuf.ByteString;
import com.zextras.carbonio.files.FilesStackTestResource;
import com.zextras.carbonio.files.dal.dao.ebean.NodeType;
import com.zextras.carbonio.files.dal.repositories.interfaces.NodeRepository;
import com.zextras.carbonio.files.grpc.sdk.CreatePublicLinkRequest;
import com.zextras.carbonio.files.grpc.sdk.CreatePublicLinkResponse;
import com.zextras.carbonio.files.grpc.sdk.DeleteAllNodesAndBlobsRequest;
import com.zextras.carbonio.files.grpc.sdk.DeleteAllNodesAndBlobsResponse;
import com.zextras.carbonio.files.grpc.sdk.DownloadFileRequest;
import com.zextras.carbonio.files.grpc.sdk.DownloadFileResponse;
import com.zextras.carbonio.files.grpc.sdk.FilesServiceGrpc;
import com.zextras.carbonio.files.grpc.sdk.UploadFileMetadata;
import com.zextras.carbonio.files.grpc.sdk.UploadFileRequest;
import com.zextras.carbonio.files.grpc.sdk.UploadFileResponse;
import com.zextras.carbonio.files.grpc.sdk.UploadFileVersionMetadata;
import com.zextras.carbonio.files.grpc.sdk.UploadFileVersionRequest;
import com.zextras.carbonio.files.grpc.sdk.UploadFileVersionResponse;
import com.zextras.carbonio.files.rest.InMemoryFilestore;
import com.zextras.filestore.api.Filestore;
import io.grpc.Grpc;
import io.grpc.InsecureChannelCredentials;
import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/**
 * Integration test for the P6b trusted-caller gRPC server ({@link FilesGrpcService}), exercising all
 * five RPCs against the real DAL (Postgres testcontainer) + the in-memory {@link InMemoryFilestore},
 * over an actual gRPC channel to the same HTTP port the REST/GraphQL stack listens on
 * ({@code use-separate-server=false}).
 *
 * <p>Uses {@code @QuarkusTest} + {@link FilesStackTestResource}. The channel targets the
 * {@code @QuarkusTest} HTTP test port (plaintext h2c). Seed data is created in-JVM via the injected
 * repositories; each test uses fresh UUIDs so rows don't collide across the shared Postgres.
 */
@QuarkusTest
@QuarkusTestResource(FilesStackTestResource.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FilesGrpcServiceIT {

  @Inject NodeRepository nodeRepository;
  @Inject Filestore filestore;

  private ManagedChannel channel;
  private FilesServiceGrpc.FilesServiceBlockingStub blockingStub;
  private FilesServiceGrpc.FilesServiceStub asyncStub;

  private String authFolderId;

  @BeforeAll
  void setUpChannel() {
    int grpcPort =
        io.restassured.RestAssured.port > 0
            ? io.restassured.RestAssured.port
            : Integer.getInteger("quarkus.http.test-port", 8081);
    channel =
        Grpc.newChannelBuilderForAddress("localhost", grpcPort, InsecureChannelCredentials.create())
            .build();
    blockingStub = FilesServiceGrpc.newBlockingStub(channel);
    asyncStub = FilesServiceGrpc.newStub(channel);
  }

  @AfterAll
  void tearDownChannel() throws Exception {
    if (channel != null) {
      channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
    }
  }

  @BeforeEach
  void seed() {
    // A folder OWNED by the fixed test user; uploads target it (OWNER => READ_AND_WRITE).
    authFolderId = UUID.randomUUID().toString();
    nodeRepository.createNewNode(
        authFolderId,
        FilesStackTestResource.TEST_USER_ID,
        FilesStackTestResource.TEST_USER_ID,
        "LOCAL_ROOT",
        "grpc-auth-folder-" + authFolderId,
        "desc",
        NodeType.FOLDER,
        "LOCAL_ROOT",
        0L);
  }

  // ----------------------------------------------------------------- UploadFile + DownloadFile

  @Test
  void uploadThenDownloadRoundTrips() throws Exception {
    byte[] content = "hello trusted gRPC world".getBytes(StandardCharsets.UTF_8);
    String nodeId =
        uploadFile(FilesStackTestResource.TEST_USER_ID, authFolderId, "hello.txt", content);

    // DB row + physical blob assertions.
    assertThat(getNodeSize(nodeId)).isEqualTo((long) content.length);
    assertThat(((InMemoryFilestore) filestore).has(nodeId, 1)).isTrue();

    // Server-streaming download: leading metadata (size) then chunk_data.
    Downloaded downloaded = downloadFile(FilesStackTestResource.TEST_USER_ID, nodeId, null);
    assertThat(downloaded.size).isEqualTo((long) content.length);
    assertThat(downloaded.bytes).isEqualTo(content);
  }

  // -------------------------------------------------------------------------- UploadFileVersion

  @Test
  void uploadFileVersionAddsSecondVersion() throws Exception {
    byte[] v1 = "version one".getBytes(StandardCharsets.UTF_8);
    byte[] v2 = "version two is a bit longer".getBytes(StandardCharsets.UTF_8);
    String nodeId = uploadFile(FilesStackTestResource.TEST_USER_ID, authFolderId, "doc.txt", v1);

    int version =
        uploadFileVersion(FilesStackTestResource.TEST_USER_ID, nodeId, "doc.txt", v2, false);
    assertThat(version).isEqualTo(2);
    assertThat(((InMemoryFilestore) filestore).has(nodeId, 2)).isTrue();

    // Latest download returns v2; explicit version 1 still returns v1.
    assertThat(downloadFile(FilesStackTestResource.TEST_USER_ID, nodeId, null).bytes).isEqualTo(v2);
    assertThat(downloadFile(FilesStackTestResource.TEST_USER_ID, nodeId, 1).bytes).isEqualTo(v1);
  }

  // ------------------------------------------------------------------------------ CreatePublicLink

  @Test
  void createPublicLinkReturnsFormattedUrl() throws Exception {
    byte[] content = "linkable".getBytes(StandardCharsets.UTF_8);
    String nodeId = uploadFile(FilesStackTestResource.TEST_USER_ID, authFolderId, "share.txt", content);

    CreatePublicLinkResponse response =
        blockingStub.createPublicLink(
            CreatePublicLinkRequest.newBuilder()
                .setUserId(FilesStackTestResource.TEST_USER_ID)
                .setNodeId(nodeId)
                .build());

    // Domain "example.com" comes from the MockUserManagementService getUserById fixture; a file
    // node uses the download endpoint; the 50-char publicId is appended.
    String url = response.getLink().getUrl();
    assertThat(url).startsWith("example.com/services/files/public/link/download/");
    assertThat(url).hasSizeGreaterThan("example.com/services/files/public/link/download/".length());
  }

  // ----------------------------------------------------------------------- DeleteAllNodesAndBlobs

  @Test
  void deleteAllNodesAndBlobsPurgesUsersNodesAndBlobs() throws Exception {
    // A dedicated owner + folder so this test only affects its own rows in the shared Postgres.
    String owner = UUID.randomUUID().toString();
    String folderId = UUID.randomUUID().toString();
    nodeRepository.createNewNode(
        folderId, owner, owner, "LOCAL_ROOT", "del-folder-" + folderId, "desc",
        NodeType.FOLDER, "LOCAL_ROOT", 0L);

    String fileA = uploadFile(owner, folderId, "a.txt", "AAAA".getBytes(StandardCharsets.UTF_8));
    String fileB = uploadFile(owner, folderId, "b.txt", "BBBBBB".getBytes(StandardCharsets.UTF_8));
    assertThat(((InMemoryFilestore) filestore).has(fileA, 1)).isTrue();
    assertThat(((InMemoryFilestore) filestore).has(fileB, 1)).isTrue();

    DeleteAllNodesAndBlobsResponse response =
        blockingStub.deleteAllNodesAndBlobs(
            DeleteAllNodesAndBlobsRequest.newBuilder().setUserId(owner).build());
    assertThat(response.getDeleted()).isTrue();

    // Nodes gone from DB and blobs gone from storages.
    assertThat(nodeExists(fileA)).isFalse();
    assertThat(nodeExists(fileB)).isFalse();
    assertThat(nodeExists(folderId)).isFalse();
    assertThat(((InMemoryFilestore) filestore).has(fileA, 1)).isFalse();
    assertThat(((InMemoryFilestore) filestore).has(fileB, 1)).isFalse();
  }

  // ---------------------------------------------------------------------------- permission / errors

  @Test
  void downloadOfForeignNodeIsPermissionDenied() throws Exception {
    byte[] content = "owner-only".getBytes(StandardCharsets.UTF_8);
    String nodeId = uploadFile(FilesStackTestResource.TEST_USER_ID, authFolderId, "secret.txt", content);

    // A different (unshared) user has no ACL on the node: the node EXISTS, so this must be
    // PERMISSION_DENIED, not NOT_FOUND.
    StatusRuntimeException error =
        assertThatDownloadFails("some-other-stranger-id", nodeId, null);
    assertThat(error.getStatus().getCode()).isEqualTo(Status.Code.PERMISSION_DENIED);
  }

  @Test
  void downloadOfMissingNodeIsNotFound() {
    StatusRuntimeException error =
        assertThatDownloadFails(
            FilesStackTestResource.TEST_USER_ID, UUID.randomUUID().toString(), null);
    assertThat(error.getStatus().getCode()).isEqualTo(Status.Code.NOT_FOUND);
  }

  // --------------------------------------------------------------------------------------- helpers

  private String uploadFile(String userId, String folderId, String filename, byte[] content)
      throws Exception {
    AtomicReference<UploadFileResponse> responseRef = new AtomicReference<>();
    AtomicReference<Throwable> errorRef = new AtomicReference<>();
    CountDownLatch latch = new CountDownLatch(1);

    StreamObserver<UploadFileRequest> requestObserver =
        asyncStub.uploadFile(
            new StreamObserver<>() {
              @Override
              public void onNext(UploadFileResponse value) {
                responseRef.set(value);
              }

              @Override
              public void onError(Throwable t) {
                errorRef.set(t);
                latch.countDown();
              }

              @Override
              public void onCompleted() {
                latch.countDown();
              }
            });

    requestObserver.onNext(
        UploadFileRequest.newBuilder()
            .setMetadata(
                UploadFileMetadata.newBuilder()
                    .setUserId(userId)
                    .setDestinationFolderId(folderId)
                    .setFilename(filename)
                    .setMimeType("text/plain"))
            .build());
    requestObserver.onNext(
        UploadFileRequest.newBuilder().setChunkData(ByteString.copyFrom(content)).build());
    requestObserver.onCompleted();

    awaitOrThrow(latch, errorRef);
    return responseRef.get().getNode().getNodeId();
  }

  private int uploadFileVersion(
      String userId, String nodeId, String filename, byte[] content, boolean overwrite)
      throws Exception {
    AtomicReference<UploadFileVersionResponse> responseRef = new AtomicReference<>();
    AtomicReference<Throwable> errorRef = new AtomicReference<>();
    CountDownLatch latch = new CountDownLatch(1);

    StreamObserver<UploadFileVersionRequest> requestObserver =
        asyncStub.uploadFileVersion(
            new StreamObserver<>() {
              @Override
              public void onNext(UploadFileVersionResponse value) {
                responseRef.set(value);
              }

              @Override
              public void onError(Throwable t) {
                errorRef.set(t);
                latch.countDown();
              }

              @Override
              public void onCompleted() {
                latch.countDown();
              }
            });

    requestObserver.onNext(
        UploadFileVersionRequest.newBuilder()
            .setMetadata(
                UploadFileVersionMetadata.newBuilder()
                    .setUserId(userId)
                    .setNodeId(nodeId)
                    .setFilename(filename)
                    .setMimeType("text/plain")
                    .setOverwrite(overwrite))
            .build());
    requestObserver.onNext(
        UploadFileVersionRequest.newBuilder().setChunkData(ByteString.copyFrom(content)).build());
    requestObserver.onCompleted();

    awaitOrThrow(latch, errorRef);
    return responseRef.get().getNode().getVersion();
  }

  private Downloaded downloadFile(String userId, String nodeId, Integer version) throws Exception {
    DownloadFileRequest.Builder request =
        DownloadFileRequest.newBuilder().setUserId(userId).setNodeId(nodeId);
    if (version != null) {
      request.setVersion(version);
    }

    Iterator<DownloadFileResponse> responses = blockingStub.downloadFile(request.build());
    long size = -1L;
    ByteArrayOutputStream body = new ByteArrayOutputStream();
    while (responses.hasNext()) {
      DownloadFileResponse response = responses.next();
      if (response.hasMetadata()) {
        size = response.getMetadata().getSize();
      } else {
        response.getChunkData().writeTo(body);
      }
    }
    return new Downloaded(size, body.toByteArray());
  }

  private StatusRuntimeException assertThatDownloadFails(
      String userId, String nodeId, Integer version) {
    Throwable thrown = catchThrowable(() -> downloadFile(userId, nodeId, version));
    assertThat(thrown).isInstanceOf(StatusRuntimeException.class);
    return (StatusRuntimeException) thrown;
  }

  private static void awaitOrThrow(CountDownLatch latch, AtomicReference<Throwable> errorRef)
      throws Exception {
    if (!latch.await(20, TimeUnit.SECONDS)) {
      throw new IllegalStateException("gRPC call did not complete in time");
    }
    Throwable error = errorRef.get();
    if (error != null) {
      if (error instanceof RuntimeException runtimeException) {
        throw runtimeException;
      }
      throw new RuntimeException(error);
    }
  }

  private long getNodeSize(String nodeId) {
    return QuarkusTransaction.requiringNew()
        .call(() -> nodeRepository.getNode(nodeId).orElseThrow().getSize());
  }

  private boolean nodeExists(String nodeId) {
    return QuarkusTransaction.requiringNew()
        .call(() -> nodeRepository.getNode(nodeId).isPresent());
  }

  private record Downloaded(long size, byte[] bytes) {}
}
