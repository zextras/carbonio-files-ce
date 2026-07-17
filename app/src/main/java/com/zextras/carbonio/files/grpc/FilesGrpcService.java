// SPDX-FileCopyrightText: 2022 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.grpc;

import com.google.protobuf.ByteString;
import com.zextras.carbonio.files.Constants.Db.RootId;
import com.zextras.carbonio.files.dal.dao.UserId;
import com.zextras.carbonio.files.dal.dao.UserInfo;
import com.zextras.carbonio.files.dal.dao.UserMyself;
import com.zextras.carbonio.files.dal.dao.ebean.Link;
import com.zextras.carbonio.files.dal.dao.ebean.Node;
import com.zextras.carbonio.files.dal.dao.ebean.NodeType;
import com.zextras.carbonio.files.dal.repositories.interfaces.NodeRepository;
import com.zextras.carbonio.files.dal.repositories.interfaces.UserRepository;
import com.zextras.carbonio.files.exceptions.DependencyException;
import com.zextras.carbonio.files.exceptions.FileSizeException;
import com.zextras.carbonio.files.exceptions.FileTypeMismatchException;
import com.zextras.carbonio.files.exceptions.MaxNumberOfFileVersionsException;
import com.zextras.carbonio.files.graphql.datafetchers.LinkDataFetcher;
import com.zextras.carbonio.files.graphql.datafetchers.NodeDataFetcher;
import com.zextras.carbonio.files.grpc.sdk.CreatePublicLinkRequest;
import com.zextras.carbonio.files.grpc.sdk.CreatePublicLinkResponse;
import com.zextras.carbonio.files.grpc.sdk.DeleteAllNodesAndBlobsRequest;
import com.zextras.carbonio.files.grpc.sdk.DeleteAllNodesAndBlobsResponse;
import com.zextras.carbonio.files.grpc.sdk.DownloadFileMetadata;
import com.zextras.carbonio.files.grpc.sdk.DownloadFileRequest;
import com.zextras.carbonio.files.grpc.sdk.DownloadFileResponse;
import com.zextras.carbonio.files.grpc.sdk.FilesServiceGrpc;
import com.zextras.carbonio.files.grpc.sdk.NodeIdProto;
import com.zextras.carbonio.files.grpc.sdk.NodeIdVersionProto;
import com.zextras.carbonio.files.grpc.sdk.PublicLinkProto;
import com.zextras.carbonio.files.grpc.sdk.UploadFileMetadata;
import com.zextras.carbonio.files.grpc.sdk.UploadFileRequest;
import com.zextras.carbonio.files.grpc.sdk.UploadFileResponse;
import com.zextras.carbonio.files.grpc.sdk.UploadFileVersionMetadata;
import com.zextras.carbonio.files.grpc.sdk.UploadFileVersionRequest;
import com.zextras.carbonio.files.grpc.sdk.UploadFileVersionResponse;
import com.zextras.carbonio.files.rest.services.BlobService;
import com.zextras.carbonio.files.rest.types.BlobResponse;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import io.quarkus.grpc.GrpcService;
import io.smallrye.common.annotation.Blocking;
import jakarta.inject.Inject;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.NoSuchElementException;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Trusted-caller gRPC surface of carbonio-files, replacing the old cookie-based
 * carbonio-files-sdk HTTP client. Each RPC carries the acting {@code user_id} directly: the caller
 * is assumed already authenticated (mesh intentions restrict who may reach the
 * {@code /files.FilesService/} prefix), so NO cookie/token auth is performed here — but the given
 * user's node ACLs are still enforced (via {@code PermissionsChecker}, reached through the reused
 * business beans), exactly as the REST/GraphQL paths do.
 *
 * <p>Every RPC delegates to the same beans the REST resources and GraphQL DataFetchers use — no
 * business logic is duplicated:
 *
 * <ul>
 *   <li>{@code UploadFile} / {@code UploadFileVersion} → {@link BlobService#uploadFile}/{@link
 *       BlobService#uploadFileVersion} (same upload/verification/version-cap path, own JTA
 *       transactions).
 *   <li>{@code DownloadFile} → {@link BlobService#downloadFileById} (same permission check +
 *       storages fetch).
 *   <li>{@code CreatePublicLink} → {@link LinkDataFetcher#createPublicLink} (the extracted core of
 *       the GraphQL {@code createLink} mutation) + {@link LinkDataFetcher#buildPublicLinkUrl}.
 *   <li>{@code DeleteAllNodesAndBlobs} → {@link NodeDataFetcher#deleteAllNodesAndBlobsForUser} (the
 *       extracted core of the GraphQL mutation, DB-before-blob ordering preserved).
 * </ul>
 *
 * <p>{@code @Blocking} runs every RPC on a worker thread (all delegates do blocking JDBC/storages
 * I/O), and Quarkus keeps the request context active for the reused CDI/Hibernate beans.
 */
@GrpcService
@Blocking
public class FilesGrpcService extends FilesServiceGrpc.FilesServiceImplBase {

  private static final Logger logger = LoggerFactory.getLogger(FilesGrpcService.class);
  private static final int DOWNLOAD_CHUNK_SIZE = 64 * 1024;

  private final BlobService blobService;
  private final LinkDataFetcher linkDataFetcher;
  private final NodeDataFetcher nodeDataFetcher;
  private final NodeRepository nodeRepository;
  private final UserRepository userRepository;

  @Inject
  public FilesGrpcService(
      BlobService blobService,
      LinkDataFetcher linkDataFetcher,
      NodeDataFetcher nodeDataFetcher,
      NodeRepository nodeRepository,
      UserRepository userRepository) {
    this.blobService = blobService;
    this.linkDataFetcher = linkDataFetcher;
    this.nodeDataFetcher = nodeDataFetcher;
    this.nodeRepository = nodeRepository;
    this.userRepository = userRepository;
  }

  // ----------------------------------------------------------------------------------- UploadFile

  @Override
  public StreamObserver<UploadFileRequest> uploadFile(
      StreamObserver<UploadFileResponse> responseObserver) {
    return new StreamObserver<>() {
      private UploadFileMetadata metadata;
      private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();

      @Override
      public void onNext(UploadFileRequest request) {
        if (request.hasMetadata()) {
          metadata = request.getMetadata();
        } else {
          writeChunk(buffer, request.getChunkData());
        }
      }

      @Override
      public void onError(Throwable t) {
        logger.warn("UploadFile client stream aborted: {}", t.getMessage());
      }

      @Override
      public void onCompleted() {
        try {
          if (metadata == null) {
            responseObserver.onError(
                Status.INVALID_ARGUMENT
                    .withDescription("Missing leading UploadFileMetadata message")
                    .asRuntimeException());
            return;
          }

          String userId = metadata.getUserId();
          if (userId.isBlank()) {
            responseObserver.onError(
                Status.INVALID_ARGUMENT.withDescription("user_id is required").asRuntimeException());
            return;
          }

          String folderId = metadata.getDestinationFolderId().isBlank()
              ? RootId.LOCAL_ROOT
              : metadata.getDestinationFolderId();
          byte[] bytes = buffer.toByteArray();

          Optional<String> nodeId = blobService.uploadFile(
              userId,
              Optional.empty(), // trusted caller: no full user entity, so no upload notifications
              new ByteArrayInputStream(bytes),
              bytes.length,
              folderId,
              metadata.getFilename(),
              "");

          if (nodeId.isEmpty()) {
            responseObserver.onError(
                Status.PERMISSION_DENIED
                    .withDescription(
                        "User " + userId + " cannot upload to folder " + folderId)
                    .asRuntimeException());
            return;
          }

          responseObserver.onNext(
              UploadFileResponse.newBuilder()
                  .setNode(NodeIdProto.newBuilder().setNodeId(nodeId.get()))
                  .build());
          responseObserver.onCompleted();
        } catch (RuntimeException e) {
          responseObserver.onError(mapException(e).asRuntimeException());
        }
      }
    };
  }

  // ---------------------------------------------------------------------------- UploadFileVersion

  @Override
  public StreamObserver<UploadFileVersionRequest> uploadFileVersion(
      StreamObserver<UploadFileVersionResponse> responseObserver) {
    return new StreamObserver<>() {
      private UploadFileVersionMetadata metadata;
      private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();

      @Override
      public void onNext(UploadFileVersionRequest request) {
        if (request.hasMetadata()) {
          metadata = request.getMetadata();
        } else {
          writeChunk(buffer, request.getChunkData());
        }
      }

      @Override
      public void onError(Throwable t) {
        logger.warn("UploadFileVersion client stream aborted: {}", t.getMessage());
      }

      @Override
      public void onCompleted() {
        try {
          if (metadata == null) {
            responseObserver.onError(
                Status.INVALID_ARGUMENT
                    .withDescription("Missing leading UploadFileVersionMetadata message")
                    .asRuntimeException());
            return;
          }

          String userId = metadata.getUserId();
          if (userId.isBlank()) {
            responseObserver.onError(
                Status.INVALID_ARGUMENT.withDescription("user_id is required").asRuntimeException());
            return;
          }

          byte[] bytes = buffer.toByteArray();

          Optional<Integer> version = blobService.uploadFileVersion(
              trustedRequester(userId),
              new ByteArrayInputStream(bytes),
              bytes.length,
              metadata.getNodeId(),
              metadata.getFilename(),
              metadata.getOverwrite());

          if (version.isEmpty()) {
            responseObserver.onError(
                Status.PERMISSION_DENIED
                    .withDescription(
                        "User " + userId + " cannot upload a new version of node "
                            + metadata.getNodeId())
                    .asRuntimeException());
            return;
          }

          responseObserver.onNext(
              UploadFileVersionResponse.newBuilder()
                  .setNode(
                      NodeIdVersionProto.newBuilder()
                          .setNodeId(metadata.getNodeId())
                          .setVersion(version.get()))
                  .build());
          responseObserver.onCompleted();
        } catch (RuntimeException e) {
          responseObserver.onError(mapException(e).asRuntimeException());
        }
      }
    };
  }

  // --------------------------------------------------------------------------------- DownloadFile

  @Override
  public void downloadFile(
      DownloadFileRequest request, StreamObserver<DownloadFileResponse> responseObserver) {
    String userId = request.getUserId();
    String nodeId = request.getNodeId();
    Integer version = request.hasVersion() ? request.getVersion() : null;

    if (userId.isBlank() || nodeId.isBlank()) {
      responseObserver.onError(
          Status.INVALID_ARGUMENT
              .withDescription("user_id and node_id are required")
              .asRuntimeException());
      return;
    }

    try {
      // Split the REST layer's "not found OR forbidden -> 404" collapse into the two precise gRPC
      // codes: a missing node is NOT_FOUND, an existing node the user can't read is PERMISSION_DENIED.
      if (nodeRepository.getNode(nodeId).isEmpty()) {
        responseObserver.onError(
            Status.NOT_FOUND.withDescription("Node not found: " + nodeId).asRuntimeException());
        return;
      }

      Optional<BlobResponse> blob =
          blobService.downloadFileById(nodeId, version, trustedRequester(userId));
      if (blob.isEmpty()) {
        responseObserver.onError(
            Status.PERMISSION_DENIED
                .withDescription("User " + userId + " cannot download node " + nodeId)
                .asRuntimeException());
        return;
      }

      streamBlob(blob.get(), responseObserver);
      responseObserver.onCompleted();
    } catch (RuntimeException e) {
      responseObserver.onError(mapException(e).asRuntimeException());
    }
  }

  private void streamBlob(BlobResponse blob, StreamObserver<DownloadFileResponse> responseObserver) {
    // Leading metadata message (the blob size, replacing the old Content-Length header read).
    responseObserver.onNext(
        DownloadFileResponse.newBuilder()
            .setMetadata(DownloadFileMetadata.newBuilder().setSize(blob.getSize()))
            .build());

    try (InputStream in = blob.getBlobStream()) {
      byte[] chunk = new byte[DOWNLOAD_CHUNK_SIZE];
      int read;
      while ((read = in.read(chunk)) != -1) {
        responseObserver.onNext(
            DownloadFileResponse.newBuilder()
                .setChunkData(ByteString.copyFrom(chunk, 0, read))
                .build());
      }
    } catch (IOException e) {
      throw new DependencyException("Failed streaming blob for node download", e);
    }
  }

  // ----------------------------------------------------------------------------- CreatePublicLink

  @Override
  public void createPublicLink(
      CreatePublicLinkRequest request, StreamObserver<CreatePublicLinkResponse> responseObserver) {
    String userId = request.getUserId();
    String nodeId = request.getNodeId();

    if (userId.isBlank() || nodeId.isBlank()) {
      responseObserver.onError(
          Status.INVALID_ARGUMENT
              .withDescription("user_id and node_id are required")
              .asRuntimeException());
      return;
    }

    try {
      // Trusted-caller: resolve the requester's domain the same way the GraphQL path reads it off
      // the authenticated UserMyself. getUserById is itself a trusted UM gRPC call (cookie ignored).
      Optional<UserInfo> requester = userRepository.getUserById(null, userId);
      if (requester.isEmpty()) {
        responseObserver.onError(
            Status.NOT_FOUND.withDescription("User not found: " + userId).asRuntimeException());
        return;
      }

      Optional<Node> node = nodeRepository.getNode(nodeId);
      if (node.isEmpty()) {
        responseObserver.onError(
            Status.NOT_FOUND.withDescription("Node not found: " + nodeId).asRuntimeException());
        return;
      }

      Link link = linkDataFetcher.createPublicLink(
          userId, nodeId, Optional.empty(), Optional.empty(), Optional.empty());

      String url = linkDataFetcher.buildPublicLinkUrl(
          link, requester.get().getDomain(), node.get().getNodeType().equals(NodeType.FOLDER));

      responseObserver.onNext(
          CreatePublicLinkResponse.newBuilder()
              .setLink(PublicLinkProto.newBuilder().setUrl(url))
              .build());
      responseObserver.onCompleted();
    } catch (LinkDataFetcher.LinkLimitReachedException e) {
      responseObserver.onError(
          Status.RESOURCE_EXHAUSTED.withDescription(e.getMessage()).asRuntimeException());
    } catch (LinkDataFetcher.NodeAccessException e) {
      responseObserver.onError(
          Status.PERMISSION_DENIED.withDescription(e.getMessage()).asRuntimeException());
    } catch (RuntimeException e) {
      responseObserver.onError(mapException(e).asRuntimeException());
    }
  }

  // ----------------------------------------------------------------------- DeleteAllNodesAndBlobs

  @Override
  public void deleteAllNodesAndBlobs(
      DeleteAllNodesAndBlobsRequest request,
      StreamObserver<DeleteAllNodesAndBlobsResponse> responseObserver) {
    String userId = request.getUserId();
    if (userId.isBlank()) {
      responseObserver.onError(
          Status.INVALID_ARGUMENT.withDescription("user_id is required").asRuntimeException());
      return;
    }

    try {
      nodeDataFetcher.deleteAllNodesAndBlobsForUser(userId);
      responseObserver.onNext(
          DeleteAllNodesAndBlobsResponse.newBuilder().setDeleted(true).build());
      responseObserver.onCompleted();
    } catch (RuntimeException e) {
      logger.error("DB error during gRPC deleteAllNodesAndBlobs for {}: {}", userId, e.getMessage());
      responseObserver.onError(mapException(e).asRuntimeException());
    }
  }

  // --------------------------------------------------------------------------------------- helpers

  /**
   * Builds a minimal {@link UserMyself} carrying only the acting user's id. The reused blob paths
   * only read {@code requester.getId().getUserId()} (for permission checks, last-editor stamping and
   * version creation); no email/domain/features are consulted, so nothing else needs resolving.
   */
  private static UserMyself trustedRequester(String userId) {
    UserMyself user = new UserMyself();
    user.setId(new UserId(userId));
    return user;
  }

  private static void writeChunk(ByteArrayOutputStream buffer, ByteString chunk) {
    try {
      chunk.writeTo(buffer);
    } catch (IOException e) {
      // ByteArrayOutputStream#write never actually throws; wrap defensively.
      throw new DependencyException("Failed buffering uploaded chunk", e);
    }
  }

  /** Maps the exceptions the reused business beans throw to the closest gRPC {@link Status}. */
  private static Status mapException(Throwable t) {
    if (t instanceof NoSuchElementException) {
      return Status.NOT_FOUND.withDescription(t.getMessage()).withCause(t);
    }
    if (t instanceof FileSizeException || t instanceof MaxNumberOfFileVersionsException) {
      return Status.RESOURCE_EXHAUSTED.withDescription(t.getMessage()).withCause(t);
    }
    if (t instanceof FileTypeMismatchException) {
      return Status.FAILED_PRECONDITION.withDescription(t.getMessage()).withCause(t);
    }
    if (t instanceof DependencyException) {
      return Status.INTERNAL.withDescription(t.getMessage()).withCause(t);
    }
    return Status.INTERNAL.withDescription(String.valueOf(t.getMessage())).withCause(t);
  }
}
