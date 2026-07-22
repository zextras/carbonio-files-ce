// SPDX-FileCopyrightText: 2022 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.grpc;

import com.google.protobuf.ByteString;
import com.zextras.carbonio.files.Constants.Db.RootId;
import com.zextras.carbonio.files.config.TransferPool;
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
import java.io.Closeable;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import org.eclipse.microprofile.context.ThreadContext;
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
 *
 * <p><b>UploadFile / UploadFileVersion streaming:</b> the client-streaming request body is piped
 * straight to storages instead of being buffered in memory. The leading metadata message opens a
 * bounded {@link PipedOutputStream}/{@link PipedInputStream} pair and submits ONE consumer task —
 * the actual {@link BlobService#uploadFile}/{@link BlobService#uploadFileVersion} call — to the
 * dedicated {@link TransferPool}; {@code onNext} chunk messages (still on the gRPC worker) only
 * write into the pipe. Because the consumer runs off the gRPC/request worker thread, it would
 * otherwise run with NO CDI request context (no request-scoped {@code EntityManager}, no JTA
 * transaction context), so the task is captured with {@link ThreadContext#contextualCallable} on
 * the gRPC thread (where the request context IS active) before being submitted, exactly mirroring
 * why the Mutiny-based REST upload path (see {@code BlobResource}) works off the event loop.
 *
 * <p><b>DownloadFile streaming:</b> node/permission resolution and opening the storages stream
 * happen on the {@code @Blocking} worker (request context needed for the DB reads); only the
 * already-open-stream 64 KiB read/write loop is offloaded to the {@link TransferPool} — no
 * {@code EntityManager} is touched there, so no context propagation is required for it.
 */
@GrpcService
@Blocking
public class FilesGrpcService extends FilesServiceGrpc.FilesServiceImplBase {

  private static final Logger logger = LoggerFactory.getLogger(FilesGrpcService.class);
  private static final int DOWNLOAD_CHUNK_SIZE = 64 * 1024;

  /** Upload pipe buffer: large enough that a single chunk write rarely blocks on backpressure. */
  private static final int UPLOAD_PIPE_BUFFER_SIZE = 256 * 1024;

  private final BlobService blobService;
  private final LinkDataFetcher linkDataFetcher;
  private final NodeDataFetcher nodeDataFetcher;
  private final NodeRepository nodeRepository;
  private final UserRepository userRepository;
  private final TransferPool transferPool;
  private final ThreadContext threadContext;

  @Inject
  public FilesGrpcService(
      BlobService blobService,
      LinkDataFetcher linkDataFetcher,
      NodeDataFetcher nodeDataFetcher,
      NodeRepository nodeRepository,
      UserRepository userRepository,
      TransferPool transferPool,
      ThreadContext threadContext) {
    this.blobService = blobService;
    this.linkDataFetcher = linkDataFetcher;
    this.nodeDataFetcher = nodeDataFetcher;
    this.nodeRepository = nodeRepository;
    this.userRepository = userRepository;
    this.transferPool = transferPool;
    this.threadContext = threadContext;
  }

  // ----------------------------------------------------------------------------------- UploadFile

  @Override
  public StreamObserver<UploadFileRequest> uploadFile(
      StreamObserver<UploadFileResponse> responseObserver) {
    return new StreamObserver<>() {
      private UploadFileMetadata metadata;
      private PipedOutputStream pipeOut;
      private Future<Optional<String>> uploadTask;
      private final AtomicBoolean aborted = new AtomicBoolean(false);
      private volatile boolean terminated = false;

      @Override
      public void onNext(UploadFileRequest request) {
        if (terminated) {
          return;
        }
        if (request.hasMetadata()) {
          if (metadata != null) {
            // Protocol violation (a second leading metadata message): the SDK never sends this;
            // ignore rather than restart an already-running upload pipeline.
            return;
          }
          metadata = request.getMetadata();
          String userId = metadata.getUserId();
          if (userId.isBlank()) {
            fail(Status.INVALID_ARGUMENT.withDescription("user_id is required"));
            return;
          }
          startUploadPipeline(userId);
        } else {
          writeChunkToPipe(pipeOut, request.getChunkData());
        }
      }

      private void startUploadPipeline(String userId) {
        try {
          pipeOut = new PipedOutputStream();
          PipedInputStream pipeIn = new PipedInputStream(pipeOut, UPLOAD_PIPE_BUFFER_SIZE);
          InputStream blobStream = new AbortableInputStream(pipeIn, aborted);
          String folderId = destinationFolderId(metadata);
          String filename = metadata.getFilename();

          Callable<Optional<String>> upload =
              () ->
                  blobService.uploadFile(
                      userId,
                      // trusted caller: no full user entity, so no upload notifications
                      Optional.empty(),
                      blobStream,
                      -1L, // unknown up front: the client streams chunks, no leading size field
                      folderId,
                      filename,
                      "");

          // Captured HERE (on the gRPC worker, request context active) so the request-scoped
          // EntityManager + JTA transaction context are restored when this runs on the transfer
          // pool thread instead.
          uploadTask = transferPool.get().submit(threadContext.contextualCallable(upload));
        } catch (IOException e) {
          fail(Status.INTERNAL
              .withDescription("Failed to initialize the upload pipe")
              .withCause(e));
        }
      }

      @Override
      public void onError(Throwable t) {
        logger.warn("UploadFile client stream aborted: {}", t.getMessage());
        terminated = true;
        // Mark aborted BEFORE closing the pipe: closing only signals EOF to the reader, the
        // AbortableInputStream is what turns that into a genuine failure instead of a
        // silently-truncated-but-"successful" upload.
        aborted.set(true);
        closeQuietly(pipeOut);
        if (uploadTask != null) {
          uploadTask.cancel(true);
        }
      }

      @Override
      public void onCompleted() {
        if (terminated) {
          return;
        }
        if (metadata == null) {
          fail(Status.INVALID_ARGUMENT
              .withDescription("Missing leading UploadFileMetadata message"));
          return;
        }
        if (uploadTask == null) {
          // Pipeline never started (userId blank already reported via fail()).
          return;
        }

        closeQuietly(pipeOut); // signals EOF to the consumer task
        try {
          Optional<String> nodeId = uploadTask.get();
          if (nodeId.isEmpty()) {
            fail(Status.PERMISSION_DENIED.withDescription(
                "User " + metadata.getUserId() + " cannot upload to folder "
                    + destinationFolderId(metadata)));
            return;
          }

          terminated = true;
          responseObserver.onNext(
              UploadFileResponse.newBuilder()
                  .setNode(NodeIdProto.newBuilder().setNodeId(nodeId.get()))
                  .build());
          responseObserver.onCompleted();
        } catch (ExecutionException e) {
          fail(mapException(e.getCause() != null ? e.getCause() : e));
        } catch (CancellationException e) {
          fail(Status.CANCELLED.withDescription("Upload was cancelled"));
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          fail(Status.INTERNAL
              .withDescription("Interrupted while waiting for the upload to complete")
              .withCause(e));
        }
      }

      private void fail(Status status) {
        if (!terminated) {
          terminated = true;
          responseObserver.onError(status.asRuntimeException());
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
      private PipedOutputStream pipeOut;
      private Future<Optional<Integer>> uploadTask;
      private final AtomicBoolean aborted = new AtomicBoolean(false);
      private volatile boolean terminated = false;

      @Override
      public void onNext(UploadFileVersionRequest request) {
        if (terminated) {
          return;
        }
        if (request.hasMetadata()) {
          if (metadata != null) {
            return;
          }
          metadata = request.getMetadata();
          String userId = metadata.getUserId();
          if (userId.isBlank()) {
            fail(Status.INVALID_ARGUMENT.withDescription("user_id is required"));
            return;
          }
          startUploadPipeline(userId);
        } else {
          writeChunkToPipe(pipeOut, request.getChunkData());
        }
      }

      private void startUploadPipeline(String userId) {
        try {
          pipeOut = new PipedOutputStream();
          PipedInputStream pipeIn = new PipedInputStream(pipeOut, UPLOAD_PIPE_BUFFER_SIZE);
          InputStream blobStream = new AbortableInputStream(pipeIn, aborted);
          UserMyself requester = trustedRequester(userId);
          String nodeId = metadata.getNodeId();
          String filename = metadata.getFilename();
          boolean overwrite = metadata.getOverwrite();

          Callable<Optional<Integer>> upload =
              () ->
                  blobService.uploadFileVersion(
                      requester,
                      blobStream,
                      -1L, // unknown up front: the client streams chunks, no leading size field
                      nodeId,
                      filename,
                      overwrite);

          uploadTask = transferPool.get().submit(threadContext.contextualCallable(upload));
        } catch (IOException e) {
          fail(Status.INTERNAL
              .withDescription("Failed to initialize the upload pipe")
              .withCause(e));
        }
      }

      @Override
      public void onError(Throwable t) {
        logger.warn("UploadFileVersion client stream aborted: {}", t.getMessage());
        terminated = true;
        aborted.set(true);
        closeQuietly(pipeOut);
        if (uploadTask != null) {
          uploadTask.cancel(true);
        }
      }

      @Override
      public void onCompleted() {
        if (terminated) {
          return;
        }
        if (metadata == null) {
          fail(Status.INVALID_ARGUMENT
              .withDescription("Missing leading UploadFileVersionMetadata message"));
          return;
        }
        if (uploadTask == null) {
          return;
        }

        closeQuietly(pipeOut);
        try {
          Optional<Integer> version = uploadTask.get();
          if (version.isEmpty()) {
            fail(Status.PERMISSION_DENIED.withDescription(
                "User " + metadata.getUserId() + " cannot upload a new version of node "
                    + metadata.getNodeId()));
            return;
          }

          terminated = true;
          responseObserver.onNext(
              UploadFileVersionResponse.newBuilder()
                  .setNode(
                      NodeIdVersionProto.newBuilder()
                          .setNodeId(metadata.getNodeId())
                          .setVersion(version.get()))
                  .build());
          responseObserver.onCompleted();
        } catch (ExecutionException e) {
          fail(mapException(e.getCause() != null ? e.getCause() : e));
        } catch (CancellationException e) {
          fail(Status.CANCELLED.withDescription("Upload was cancelled"));
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          fail(Status.INTERNAL
              .withDescription("Interrupted while waiting for the upload to complete")
              .withCause(e));
        }
      }

      private void fail(Status status) {
        if (!terminated) {
          terminated = true;
          responseObserver.onError(status.asRuntimeException());
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

  /**
   * Sends the leading size metadata (no {@code EntityManager} access, trivial) then offloads the
   * 64 KiB read/write loop of the ALREADY-OPEN storages stream to the {@link TransferPool}, waiting
   * for it to finish. No context propagation is needed for the offloaded loop: it only copies bytes
   * from the storages {@link InputStream} to the gRPC {@link StreamObserver}, touching no
   * request-scoped bean.
   */
  private void streamBlob(BlobResponse blob, StreamObserver<DownloadFileResponse> responseObserver) {
    responseObserver.onNext(
        DownloadFileResponse.newBuilder()
            .setMetadata(DownloadFileMetadata.newBuilder().setSize(blob.getSize()))
            .build());

    Future<?> transfer = transferPool.get().submit(() -> pumpBlobChunks(blob, responseObserver));
    try {
      transfer.get();
    } catch (ExecutionException e) {
      Throwable cause = e.getCause() != null ? e.getCause() : e;
      if (cause instanceof RuntimeException runtimeException) {
        throw runtimeException;
      }
      throw new DependencyException("Failed streaming blob for node download", cause);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new DependencyException("Interrupted while streaming blob for node download", e);
    }
  }

  private static void pumpBlobChunks(
      BlobResponse blob, StreamObserver<DownloadFileResponse> responseObserver) {
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

  private static String destinationFolderId(UploadFileMetadata metadata) {
    return metadata.getDestinationFolderId().isBlank()
        ? RootId.LOCAL_ROOT
        : metadata.getDestinationFolderId();
  }

  /**
   * Writes one client-streamed chunk straight into the upload pipe (no whole-file buffering). If
   * the pipe is {@code null} (no metadata received yet — a protocol violation from a well-behaved
   * client) the chunk is dropped. If the write fails because the consumer end already closed the
   * pipe (the consumer task finished — successfully, on a business error, or aborted) the chunk is
   * likewise dropped: the actual outcome/exception is always picked up from the consumer {@code
   * Future} in {@code onCompleted}, so nothing is lost by ignoring it here.
   */
  private static void writeChunkToPipe(PipedOutputStream pipeOut, ByteString chunk) {
    if (pipeOut == null) {
      return;
    }
    try {
      chunk.newInput().transferTo(pipeOut);
    } catch (IOException e) {
      logger.debug("Upload chunk dropped after the pipe was closed: {}", e.getMessage());
    }
  }

  private static void closeQuietly(Closeable closeable) {
    if (closeable == null) {
      return;
    }
    try {
      closeable.close();
    } catch (IOException ignored) {
      // best-effort close of the upload pipe
    }
  }

  /**
   * Wraps the read side of an upload pipe so a client-initiated abort is surfaced to the storages
   * upload as a genuine {@link IOException} rather than a silent, truncated-but-clean end-of-stream.
   * {@link PipedOutputStream#close()} alone only signals EOF ({@code -1}) to the connected {@link
   * PipedInputStream} — which {@link BlobService#uploadFile}/{@code uploadFileVersion} would
   * otherwise treat as a successfully, fully-read (but truncated) upload. Checking the shared {@code
   * aborted} flag AFTER every delegate read call closes that race: whichever read notices the flag
   * (or the EOF caused by the writer closing) converts it into a thrown exception instead of a
   * quiet -1.
   */
  private static final class AbortableInputStream extends FilterInputStream {

    private final AtomicBoolean aborted;

    private AbortableInputStream(InputStream in, AtomicBoolean aborted) {
      super(in);
      this.aborted = aborted;
    }

    @Override
    public int read() throws IOException {
      int b = super.read();
      failIfAborted();
      return b;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
      int n = super.read(b, off, len);
      failIfAborted();
      return n;
    }

    private void failIfAborted() throws IOException {
      if (aborted.get()) {
        throw new IOException("Upload aborted: client stream terminated before completion");
      }
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
