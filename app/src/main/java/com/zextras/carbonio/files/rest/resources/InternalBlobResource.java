// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.rest.resources;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.zextras.carbonio.files.Constants.API.Headers;
import com.zextras.carbonio.files.Constants.Db.RootId;
import com.zextras.carbonio.files.config.TransferPool;
import com.zextras.carbonio.files.dal.dao.UserId;
import com.zextras.carbonio.files.dal.dao.UserMyself;
import com.zextras.carbonio.files.rest.services.BlobService;
import com.zextras.carbonio.files.rest.types.BlobResponse;
import com.zextras.carbonio.files.rest.types.UploadVersionResponse;
import io.smallrye.common.annotation.Blocking;
import io.smallrye.mutiny.Uni;
import io.vertx.core.http.HttpServerResponse;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.NoSuchElementException;
import java.util.Optional;
import org.eclipse.microprofile.openapi.annotations.enums.SchemaType;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponses;

/**
 * Trusted, UNAUTHENTICATED blob REST endpoints reached only over the mesh (mTLS is the trust
 * boundary — there is NO auth filter/cookie here at all): {@code POST
 * /internal/accounts/{userId}/upload}, {@code POST /internal/accounts/{userId}/upload-version} and
 * {@code GET /internal/accounts/{userId}/download/{nodeId}[/{version}]}. The {@code userId} PATH
 * segment is the ONLY thing that determines whose ACLs are checked / who owns a newly-created node
 * — the same trusted-caller contract the retired WIP gRPC surface (formerly {@code
 * FilesGrpcService}) used for its {@code UploadFile}/{@code UploadFileVersion}/{@code DownloadFile}
 * RPCs, now reached here over REST instead.
 *
 * <p>Reuses the EXACT same proven streaming machinery as the authenticated {@link BlobResource}:
 * raw body {@link InputStream} in, a Vert.x {@link HttpServerResponse} out via {@link
 * TransferStreaming#streamBlob}, and the dedicated {@link TransferPool} for the heavy byte-transfer
 * work (real backpressure, no whole-file buffering). No new streaming approach is introduced.
 *
 * <p>Replaces the former header-based {@code POST /internal/upload} (which read the acting user id
 * from an {@code AccountId} header): same no-auth, no-upload-size-cap behavior — mirrored here
 * verbatim — reshaped so the acting user id is a path segment, per the locked decision, and
 * extended with the upload-version and download counterparts.
 */
@Path("/internal")
@ApplicationScoped
public class InternalBlobResource {

  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  private final BlobService blobService;
  private final TransferPool transferPool;

  @Inject
  public InternalBlobResource(BlobService blobService, TransferPool transferPool) {
    this.blobService = blobService;
    this.transferPool = transferPool;
  }

  // -------------------------------------------------------------------------------------- uploads

  /**
   * No auth, no upload size cap (mirrors the retired {@code POST /internal/upload} exactly): the
   * {@code userId} PATH segment alone drives ownership of the new node.
   */
  @POST
  @Path("/accounts/{userId}/upload")
  @Consumes(MediaType.WILDCARD)
  @Produces(MediaType.APPLICATION_JSON)
  public Uni<Response> upload(
      @PathParam("userId") String userId,
      @HeaderParam(Headers.UPLOAD_FILENAME) String encodedFilename,
      @HeaderParam(Headers.UPLOAD_DESCRIPTION) String description,
      @HeaderParam(Headers.UPLOAD_PARENT_ID) String parentId,
      @HeaderParam(HttpHeaders.CONTENT_LENGTH) Long contentLength,
      InputStream body) {

    // Offloaded to the transfer pool exactly like BlobResource's uploads: the whole upload
    // (streamed
    // body consume + JDBC/JTA) runs on a transfer thread, off the event loop.
    return Uni.createFrom()
        .item(
            () -> doUploadFile(userId, body, contentLength, parentId, encodedFilename, description))
        .runSubscriptionOn(transferPool.get());
  }

  @POST
  @Path("/accounts/{userId}/upload-version")
  @Consumes(MediaType.WILDCARD)
  @Produces(MediaType.APPLICATION_JSON)
  public Uni<Response> uploadVersion(
      @PathParam("userId") String userId,
      @HeaderParam(Headers.UPLOAD_NODE_ID) String nodeId,
      @HeaderParam(Headers.UPLOAD_FILENAME) String encodedFilename,
      @HeaderParam(Headers.UPLOAD_OVERWRITE_VERSION) String overwriteHeader,
      @HeaderParam(HttpHeaders.CONTENT_LENGTH) Long contentLength,
      InputStream body) {

    return Uni.createFrom()
        .item(
            () -> {
              String decodedFilename = decodeFilename(encodedFilename);
              if (nodeId == null
                  || decodedFilename == null
                  || decodedFilename.trim().isEmpty()
                  || decodedFilename.trim().length() > 1024) {
                throw new IllegalArgumentException("Missing or invalid nodeId/filename headers");
              }

              boolean overwrite = Boolean.parseBoolean(overwriteHeader);

              Optional<Integer> version =
                  blobService.uploadFileVersion(
                      trustedRequester(userId),
                      body,
                      contentLength == null ? -1L : contentLength,
                      nodeId,
                      decodedFilename,
                      overwrite);
              if (version.isEmpty()) {
                throw notFoundOrForbidden(nodeId);
              }
              return uploadResponse(nodeId, version.get());
            })
        .runSubscriptionOn(transferPool.get());
  }

  private Response doUploadFile(
      String userId,
      InputStream body,
      Long contentLength,
      String parentIdHeader,
      String encodedFilename,
      String description) {

    String parentId = parentIdHeader != null ? parentIdHeader : RootId.LOCAL_ROOT;
    String desc = description != null ? description : "";

    String decodedFilename = decodeFilename(encodedFilename);
    if (decodedFilename == null
        || decodedFilename.trim().isEmpty()
        || decodedFilename.trim().length() > 1024) {
      throw new IllegalArgumentException("Missing or invalid Filename header");
    }

    Optional<String> nodeId =
        blobService.uploadFile(
            userId,
            // trusted caller: no full user entity, so no upload notifications (same as the retired
            // header-based /internal/upload).
            Optional.empty(),
            body,
            contentLength == null ? -1L : contentLength,
            parentId,
            decodedFilename,
            desc);
    if (nodeId.isEmpty()) {
      throw notFoundOrForbidden(parentId);
    }
    return uploadResponse(nodeId.get(), 1);
  }

  // ------------------------------------------------------------------------------------ downloads

  @GET
  @Path("/accounts/{userId}/download/{nodeId}")
  @Blocking
  @APIResponses({
    @APIResponse(
        responseCode = "200",
        description = "File bytes",
        content =
            @Content(
                mediaType = "application/octet-stream",
                schema = @Schema(type = SchemaType.STRING, format = "binary"))),
    @APIResponse(responseCode = "404", description = "Node not found or not accessible")
  })
  public Uni<Void> download(
      @PathParam("userId") String userId,
      @PathParam("nodeId") String nodeId,
      @Context HttpServerResponse resp) {
    return doDownload(userId, nodeId, null, resp);
  }

  @GET
  @Path("/accounts/{userId}/download/{nodeId}/{version:\\d+}")
  @Blocking
  @APIResponses({
    @APIResponse(
        responseCode = "200",
        description = "File bytes",
        content =
            @Content(
                mediaType = "application/octet-stream",
                schema = @Schema(type = SchemaType.STRING, format = "binary"))),
    @APIResponse(responseCode = "404", description = "Node not found or not accessible")
  })
  public Uni<Void> downloadVersion(
      @PathParam("userId") String userId,
      @PathParam("nodeId") String nodeId,
      @PathParam("version") int version,
      @Context HttpServerResponse resp) {
    return doDownload(userId, nodeId, version, resp);
  }

  private Uni<Void> doDownload(
      String userId, String nodeId, Integer version, HttpServerResponse resp) {
    // @Blocking: this runs on a worker thread (request context + EntityManager active), same as
    // BlobResource#doDownload. Permission resolution + opening the storages InputStream happen here
    // (throwing 404 BEFORE any byte/header is written); only the byte pump of the already-open
    // storages stream is offloaded to the transfer pool. See TransferStreaming.
    BlobResponse blob =
        blobService
            .downloadFileById(nodeId, version, trustedRequester(userId))
            .orElseThrow(() -> notFoundOrForbidden(nodeId));
    return TransferStreaming.streamBlob(blob, resp, transferPool.get());
  }

  // --------------------------------------------------------------------------------------- shared

  /**
   * Builds a minimal {@link UserMyself} carrying only the acting user's id, mirroring {@code
   * FilesGrpcService#trustedRequester}. The reused blob paths only read {@code
   * requester.getId().getUserId()} (permission checks, last-editor stamping and version creation);
   * no email/domain/features are consulted, so nothing else needs resolving.
   */
  private static UserMyself trustedRequester(String userId) {
    UserMyself user = new UserMyself();
    user.setId(new UserId(userId));
    return user;
  }

  /**
   * Base64-decodes the {@code Filename} header (legacy encoding). Returns {@code null} if the
   * header is missing or not valid base64, which the callers translate into a 400.
   */
  private static String decodeFilename(String encodedFilename) {
    if (encodedFilename == null) {
      return null;
    }
    try {
      return new String(Base64.getDecoder().decode(encodedFilename), StandardCharsets.UTF_8);
    } catch (IllegalArgumentException e) {
      return null;
    }
  }

  private Response uploadResponse(String nodeId, int version) {
    UploadVersionResponse response = new UploadVersionResponse();
    response.setNodeId(nodeId);
    response.setVersion(version);
    try {
      String json =
          OBJECT_MAPPER
              .copy()
              .configure(SerializationFeature.FAIL_ON_EMPTY_BEANS, false)
              .writeValueAsString(response);
      return Response.ok(json).type(MediaType.APPLICATION_JSON).build();
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new RuntimeException(e);
    }
  }

  /**
   * Legacy parity (see {@link BlobResource#notFoundOrForbidden}): "node does not exist" and "node
   * exists but requester lacks permission" both collapse into the same 404.
   */
  private RuntimeException notFoundOrForbidden(String nodeId) {
    return new NoSuchElementException("Node " + nodeId + " does not exist or is not accessible");
  }
}
