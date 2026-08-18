// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.rest.resources;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.zextras.carbonio.files.Constants.API.Headers;
import com.zextras.carbonio.files.Constants.Db.RootId;
import com.zextras.carbonio.files.config.FilesConfig;
import com.zextras.carbonio.files.config.TransferPool;
import com.zextras.carbonio.files.dal.dao.UserMyself;
import com.zextras.carbonio.files.dal.dao.ebean.Node;
import com.zextras.carbonio.files.dal.repositories.interfaces.NodeRepository;
import com.zextras.carbonio.files.exceptions.FileSizeException;
import com.zextras.carbonio.files.rest.services.BlobService;
import com.zextras.carbonio.files.rest.services.BlobService.ZipDownload;
import com.zextras.carbonio.files.rest.types.BlobResponse;
import com.zextras.carbonio.files.rest.types.UploadVersionResponse;
import io.smallrye.common.annotation.Blocking;
import io.smallrye.mutiny.Uni;
import io.vertx.core.http.HttpServerResponse;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.CookieParam;
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
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import org.eclipse.microprofile.openapi.annotations.enums.SchemaType;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponses;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Authenticated blob REST endpoints (upload / upload-version / single download + check / multi ZIP
 * download + check). Quarkus/RESTEasy Reactive port of the legacy Netty {@code BlobController}:
 * same endpoint set, same headers, same permission enforcement.
 *
 * <p>The trusted, UNAUTHENTICATED counterpart (no cookie, acting user id passed explicitly) lives
 * in {@link InternalBlobResource} under {@code /internal/accounts/{userId}/...} — it replaced the
 * former header-based {@code POST /internal/upload} that used to live here.
 *
 * <p>Resources are served at ROOT (carbonio-proxy strips its {@code /services/files} prefix), so no
 * {@code quarkus.rest.path} is set. The heavy byte-transfer endpoints return a Mutiny {@code Uni}
 * and offload the byte transfer to the dedicated {@link TransferPool}, keeping it off the shared
 * worker pool.
 *
 * <p>Streaming:
 *
 * <ul>
 *   <li><b>upload</b> (non-{@code @Blocking}) — the method body only builds the {@code Uni}; auth,
 *       the streamed request-body consume ({@link InputStream}, no whole-file buffering) and the
 *       JDBC/JTA write all run on the transfer pool via {@code runSubscriptionOn} (request context
 *       propagated by SmallRye context propagation).
 *   <li><b>download</b> ({@code @Blocking}) — auth + node metadata / ZIP plan resolution and
 *       opening the storages {@link InputStream} run in the method body on a WORKER thread (request
 *       context + {@code EntityManager} active), so 404/403 is thrown before any header/byte is
 *       written; the worker is then released and only the byte pump (already-open storages stream →
 *       Vert.x {@code HttpServerResponse}, with real backpressure, no {@code EntityManager} access)
 *       runs on the transfer pool. See {@link TransferStreaming}.
 * </ul>
 *
 * <p>The lightweight {@code .../check} endpoints stay plain {@code @Blocking} on the default worker
 * pool.
 */
@Path("/")
@ApplicationScoped
public class BlobResource {

  private static final Logger logger = LoggerFactory.getLogger(BlobResource.class);
  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
  private static final String NODE_IDS = "nodeIds";

  private final BlobService blobService;
  private final FilesConfig filesConfig;
  private final BlobAuthenticator authenticator;
  private final NodeRepository nodeRepository;
  private final TransferPool transferPool;

  @Inject
  public BlobResource(
      BlobService blobService,
      FilesConfig filesConfig,
      BlobAuthenticator authenticator,
      NodeRepository nodeRepository,
      TransferPool transferPool) {
    this.blobService = blobService;
    this.filesConfig = filesConfig;
    this.authenticator = authenticator;
    this.nodeRepository = nodeRepository;
    this.transferPool = transferPool;
  }

  // -------------------------------------------------------------------------------------- uploads

  @POST
  @Path("/upload")
  @Consumes(MediaType.WILDCARD)
  @Produces(MediaType.APPLICATION_JSON)
  public Uni<Response> upload(
      @HeaderParam("Cookie") String cookieHeader,
      @CookieParam(Headers.COOKIE_ZM_AUTH_TOKEN) String zmToken,
      @HeaderParam(Headers.UPLOAD_FILENAME) String encodedFilename,
      @HeaderParam(Headers.UPLOAD_DESCRIPTION) String description,
      @HeaderParam(Headers.UPLOAD_PARENT_ID) String parentId,
      @HeaderParam(HttpHeaders.CONTENT_LENGTH) Long contentLength,
      InputStream body) {

    // Offloaded to the transfer pool: the whole upload (auth + size check + streamed body consume +
    // JDBC/JTA) runs on a transfer thread, off the event loop. A thrown FileSizeException /
    // IllegalArgumentException / NoSuchElementException surfaces as a Uni failure and is mapped by
    // BlobExceptionMapper exactly as before.
    return Uni.createFrom()
        .item(
            () -> {
              UserMyself requester = authenticator.requireUser(cookieHeader, zmToken);
              if (isRequestSizeOverLimit(contentLength)) {
                throw new FileSizeException("File size exceeds the maximum allowed");
              }
              return doUploadFile(
                  requester.getId().getUserId(),
                  Optional.of(requester),
                  body,
                  contentLength,
                  parentId,
                  encodedFilename,
                  description);
            })
        .runSubscriptionOn(transferPool.get());
  }

  @POST
  @Path("/upload-version")
  @Consumes(MediaType.WILDCARD)
  @Produces(MediaType.APPLICATION_JSON)
  public Uni<Response> uploadVersion(
      @HeaderParam("Cookie") String cookieHeader,
      @CookieParam(Headers.COOKIE_ZM_AUTH_TOKEN) String zmToken,
      @HeaderParam(Headers.UPLOAD_NODE_ID) String nodeId,
      @HeaderParam(Headers.UPLOAD_FILENAME) String encodedFilename,
      @HeaderParam(Headers.UPLOAD_OVERWRITE_VERSION) String overwriteHeader,
      @HeaderParam(HttpHeaders.CONTENT_LENGTH) Long contentLength,
      InputStream body) {

    return Uni.createFrom()
        .item(
            () -> {
              UserMyself requester = authenticator.requireUser(cookieHeader, zmToken);

              String decodedFilename = decodeFilename(encodedFilename);
              if (nodeId == null
                  || decodedFilename == null
                  || decodedFilename.trim().isEmpty()
                  || decodedFilename.trim().length() > 1024) {
                throw new IllegalArgumentException("Missing or invalid nodeId/filename headers");
              }

              boolean overwrite = Boolean.parseBoolean(overwriteHeader);
              if (isRequestSizeOverLimit(contentLength)) {
                throw new FileSizeException("File size exceeds the maximum allowed");
              }

              logger.debug(
                  "Uploading new version of node with id: {}, overwrite: {}", nodeId, overwrite);

              Optional<Integer> version =
                  blobService.uploadFileVersion(
                      requester,
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
      String requesterId,
      Optional<UserMyself> requesterEntity,
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
            requesterId,
            requesterEntity,
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
  @Path("/download/{nodeId}")
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
      @HeaderParam("Cookie") String cookieHeader,
      @CookieParam(Headers.COOKIE_ZM_AUTH_TOKEN) String zmToken,
      @PathParam("nodeId") String nodeId,
      @Context HttpServerResponse resp) {
    return doDownload(cookieHeader, zmToken, nodeId, null, resp);
  }

  @GET
  @Path("/download/{nodeId}/{version:\\d+}")
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
      @HeaderParam("Cookie") String cookieHeader,
      @CookieParam(Headers.COOKIE_ZM_AUTH_TOKEN) String zmToken,
      @PathParam("nodeId") String nodeId,
      @PathParam("version") int version,
      @Context HttpServerResponse resp) {
    return doDownload(cookieHeader, zmToken, nodeId, version, resp);
  }

  private Uni<Void> doDownload(
      String cookieHeader,
      String zmToken,
      String nodeId,
      Integer version,
      HttpServerResponse resp) {
    // @Blocking: this runs on a worker thread (request context + EntityManager active). Auth +
    // metadata resolution + opening the storages InputStream happen here, throwing 404/403 BEFORE
    // any byte or header is written (so BlobExceptionMapper sets the status). Only the byte pump of
    // the already-open storages stream is offloaded to the transfer pool (no EntityManager there).
    UserMyself requester = authenticator.requireUser(cookieHeader, zmToken);
    BlobResponse blob =
        blobService
            .downloadFileById(nodeId, version, requester)
            .orElseThrow(() -> notFoundOrForbidden(nodeId));
    return TransferStreaming.streamBlob(blob, resp, transferPool.get());
  }

  @GET
  @Path("/download/{nodeId}/check")
  @Blocking
  public Response checkDownload(
      @HeaderParam("Cookie") String cookieHeader,
      @CookieParam(Headers.COOKIE_ZM_AUTH_TOKEN) String zmToken,
      @PathParam("nodeId") String nodeId) {
    UserMyself requester = authenticator.requireUser(cookieHeader, zmToken);
    Optional<Node> node = blobService.checkDownloadFileById(nodeId, requester);
    if (node.isEmpty()) {
      throw notFoundOrForbidden(nodeId);
    }
    return Response.noContent().build();
  }

  @POST
  @Path("/download-multiple")
  @Blocking
  @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
  @APIResponses({
    @APIResponse(
        responseCode = "200",
        description = "ZIP archive of the requested nodes",
        content =
            @Content(
                mediaType = "application/zip",
                schema = @Schema(type = SchemaType.STRING, format = "binary"))),
    @APIResponse(responseCode = "400", description = "Missing or malformed nodeIds"),
    @APIResponse(
        responseCode = "404",
        description = "Some nodes do not exist or are not accessible")
  })
  public Uni<Void> downloadMultiple(
      @HeaderParam("Cookie") String cookieHeader,
      @CookieParam(Headers.COOKIE_ZM_AUTH_TOKEN) String zmToken,
      InputStream requestBody,
      @Context HttpServerResponse resp) {
    // @Blocking (worker thread): auth + the ZIP plan (buildZipPlan tree walk, EntityManager active)
    // are resolved here so a 404 for missing/forbidden nodes is thrown before any archive byte is
    // written. Only the ZIP byte streaming is offloaded to the transfer pool, where each entry's
    // blob is opened via storages (openZipEntryStream) — no EntityManager access on that thread.
    //
    // Legacy parity: restores the 1MB HttpObjectAggregator cap (see RequestBodyLimits); the entity
    // is read as a raw InputStream (not @FormParam) precisely so the size bound is enforced against
    // actual bytes read, not a trusted Content-Length header -- and, matching the legacy pipeline
    // order (aggregator before auth-handler), BEFORE authentication.
    String rawFormBody =
        RequestBodyLimits.readBoundedUtf8(
            requestBody, RequestBodyLimits.DOWNLOAD_MULTIPLE_MAX_BODY_BYTES);
    UserMyself requester = authenticator.requireUser(cookieHeader, zmToken);
    String nodeIdsJson = RequestBodyLimits.parseFormUrlEncoded(rawFormBody).get(NODE_IDS);
    List<String> nodeIds = parseNodeIdsArray(nodeIdsJson);
    ZipDownload zip =
        blobService
            .downloadMultiple(nodeIds, requester)
            .orElseThrow(
                () ->
                    new NoSuchElementException(
                        "Some nodes do not exist or the user lacks permission: " + nodeIds));
    return TransferStreaming.streamZip(zip, blobService, resp, transferPool.get());
  }

  @POST
  @Path("/download-multiple/check")
  @Blocking
  @Consumes(MediaType.APPLICATION_JSON)
  public Response checkDownloadMultiple(
      @HeaderParam("Cookie") String cookieHeader,
      @CookieParam(Headers.COOKIE_ZM_AUTH_TOKEN) String zmToken,
      InputStream requestBody) {
    // See downloadMultiple's javadoc comment: bounded raw-InputStream read (legacy 1MB cap),
    // before authentication, in place of trusting Content-Length.
    String jsonBody =
        RequestBodyLimits.readBoundedUtf8(
            requestBody, RequestBodyLimits.DOWNLOAD_MULTIPLE_MAX_BODY_BYTES);
    UserMyself requester = authenticator.requireUser(cookieHeader, zmToken);
    List<String> nodeIds = parseNodeIdsFromJsonBody(jsonBody);
    blobService
        .checkDownloadMultiple(nodeIds, requester)
        .orElseThrow(
            () ->
                new NoSuchElementException(
                    "Some nodes do not exist or the user lacks permission: " + nodeIds));
    return Response.noContent().build();
  }

  // --------------------------------------------------------------------------------------- shared

  /**
   * Legacy parity: {@code core/.../BlobController#isRequestSizeOverLimit} did {@code
   * Long.parseLong(httpRequest.headers().get(CONTENT_LENGTH))} as its very first act, so a request
   * with no {@code Content-Length} header at all (e.g. chunked transfer-encoding) threw a {@code
   * NumberFormatException} (an {@link IllegalArgumentException} subtype) -&gt; 400, independent of
   * whether {@code max-uploadable-size-in-mb} was even configured. A missing/unparseable {@code
   * Content-Length} must be REFUSED, not treated as "no cap applies" (which is what returning
   * {@code false} here used to do).
   */
  private boolean isRequestSizeOverLimit(Long contentLength) {
    if (contentLength == null) {
      throw new IllegalArgumentException("Missing or invalid Content-Length header");
    }
    double blobLengthInMB = contentLength / (1024.0 * 1024.0);
    Optional<Integer> maxFileSize = filesConfig.getMaxUploadableFileSizeInMb();
    return maxFileSize.isPresent() && blobLengthInMB > maxFileSize.get();
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

  private List<String> parseNodeIdsArray(String nodeIdsJson) {
    if (nodeIdsJson == null || nodeIdsJson.isBlank()) {
      throw new IllegalArgumentException("Missing nodeIds parameter in form data");
    }
    try {
      List<String> nodeIds = OBJECT_MAPPER.readValue(nodeIdsJson, new TypeReference<>() {});
      if (nodeIds == null || nodeIds.isEmpty()) {
        throw new IllegalArgumentException("nodeIds list cannot be empty");
      }
      return nodeIds;
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      // Legacy parity: BlobController#downloadMultiple's own catch fires
      // `new IllegalArgumentException(message)` with NO cause. BlobExceptionMapper unwraps
      // exactly one getCause() level (matching the legacy ExceptionsHandler), so passing `e` here
      // would make it switch on the raw JsonProcessingException instead -> misrouted to 500.
      throw new IllegalArgumentException("Can't parse form data. Expected 'nodeIds' JSON array.");
    }
  }

  private List<String> parseNodeIdsFromJsonBody(String jsonBody) {
    if (jsonBody == null || jsonBody.isBlank()) {
      throw new IllegalArgumentException("Request body is empty");
    }
    try {
      var map =
          OBJECT_MAPPER.readValue(jsonBody, new TypeReference<java.util.Map<String, Object>>() {});
      Object nodeIdsObj = map.get(NODE_IDS);
      if (nodeIdsObj == null) {
        throw new IllegalArgumentException("Missing nodeIds parameter in JSON body");
      }
      List<String> nodeIds = OBJECT_MAPPER.convertValue(nodeIdsObj, new TypeReference<>() {});
      if (nodeIds == null || nodeIds.isEmpty()) {
        throw new IllegalArgumentException("nodeIds list cannot be empty");
      }
      return nodeIds;
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new IllegalArgumentException("Can't parse JSON body. Expected 'nodeIds' field.", e);
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
   * Legacy parity (pinned by the acceptance suite): the legacy Netty stack collapsed BOTH "node
   * does not exist" and "node exists but requester lacks permission" into the same 404 (both
   * surfaced as {@link NoSuchElementException} → {@code "404 Not Found"}). The acceptance tests
   * assert exactly that indistinguishable 404 shape for both cases (e.g. {@code
   * AuthenticatedDownloadApiIT}, {@code InternalUploadApiIT}), so a permission failure must NOT
   * surface as a "more precise" 403.
   */
  private RuntimeException notFoundOrForbidden(String nodeId) {
    return new NoSuchElementException("Node " + nodeId + " does not exist or is not accessible");
  }
}
