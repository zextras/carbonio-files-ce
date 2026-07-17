// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.rest.resources;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.zextras.carbonio.files.Constants.API.Headers;
import com.zextras.carbonio.files.Constants.Db.RootId;
import com.zextras.carbonio.files.dal.dao.UserMyself;
import com.zextras.carbonio.files.dal.dao.ebean.Node;
import com.zextras.carbonio.files.dal.repositories.interfaces.NodeRepository;
import com.zextras.carbonio.files.exceptions.FileSizeException;
import com.zextras.carbonio.files.rest.services.BlobService;
import com.zextras.carbonio.files.rest.services.BlobService.ZipDownload;
import com.zextras.carbonio.files.rest.types.BlobResponse;
import com.zextras.carbonio.files.rest.types.UploadVersionResponse;
import com.zextras.carbonio.files.config.FilesConfig;
import io.smallrye.common.annotation.Blocking;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.CookieParam;
import jakarta.ws.rs.FormParam;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Authenticated blob REST endpoints (upload / upload-version / internal upload / single download +
 * check / multi ZIP download + check). Quarkus/RESTEasy Reactive port of the legacy Netty {@code
 * BlobController}: same endpoint set, same headers, same permission enforcement.
 *
 * <p>Resources are served at ROOT (carbonio-proxy strips its {@code /services/files} prefix), so no
 * {@code quarkus.rest.path} is set. All methods are {@code @Blocking}: they read/stream the request
 * body, run blocking gRPC auth and blocking JDBC, so they must execute on a worker thread where the
 * CDI request context and the request-scoped {@code EntityManager} are active.
 *
 * <p>Streaming:
 *
 * <ul>
 *   <li><b>upload</b> — the raw request body is consumed as an {@link InputStream} straight from
 *       RESTEasy Reactive and passed through to {@link BlobService}/Filestore without buffering the
 *       whole file in memory.
 *   <li><b>download</b> — a single blob is returned as an {@link InputStream} entity (RESTEasy
 *       Reactive streams and closes it); the ZIP multi-download is returned as a {@link
 *       StreamingOutput} that pulls each planned blob from {@link Filestore} lazily.
 * </ul>
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

  @Inject
  public BlobResource(
      BlobService blobService,
      FilesConfig filesConfig,
      BlobAuthenticator authenticator,
      NodeRepository nodeRepository) {
    this.blobService = blobService;
    this.filesConfig = filesConfig;
    this.authenticator = authenticator;
    this.nodeRepository = nodeRepository;
  }

  // -------------------------------------------------------------------------------------- uploads

  @POST
  @Path("/upload")
  @Blocking
  @Consumes(MediaType.WILDCARD)
  @Produces(MediaType.APPLICATION_JSON)
  public Response upload(
      @HeaderParam("Cookie") String cookieHeader,
      @CookieParam(Headers.COOKIE_ZM_AUTH_TOKEN) String zmToken,
      @HeaderParam(Headers.UPLOAD_FILENAME) String encodedFilename,
      @HeaderParam(Headers.UPLOAD_DESCRIPTION) String description,
      @HeaderParam(Headers.UPLOAD_PARENT_ID) String parentId,
      @HeaderParam(HttpHeaders.CONTENT_LENGTH) Long contentLength,
      InputStream body) {

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
  }

  /** Internal upload: no auth, uses the {@code AccountId} header, and does NOT enforce a size limit. */
  @POST
  @Path("/internal/upload")
  @Blocking
  @Consumes(MediaType.WILDCARD)
  @Produces(MediaType.APPLICATION_JSON)
  public Response uploadInternal(
      @HeaderParam(Headers.UPLOAD_ACCOUNT_ID) String accountId,
      @HeaderParam(Headers.UPLOAD_FILENAME) String encodedFilename,
      @HeaderParam(Headers.UPLOAD_DESCRIPTION) String description,
      @HeaderParam(Headers.UPLOAD_PARENT_ID) String parentId,
      @HeaderParam(HttpHeaders.CONTENT_LENGTH) Long contentLength,
      InputStream body) {

    return doUploadFile(
        accountId, Optional.empty(), body, contentLength, parentId, encodedFilename, description);
  }

  @POST
  @Path("/upload-version")
  @Blocking
  @Consumes(MediaType.WILDCARD)
  @Produces(MediaType.APPLICATION_JSON)
  public Response uploadVersion(
      @HeaderParam("Cookie") String cookieHeader,
      @CookieParam(Headers.COOKIE_ZM_AUTH_TOKEN) String zmToken,
      @HeaderParam(Headers.UPLOAD_NODE_ID) String nodeId,
      @HeaderParam(Headers.UPLOAD_FILENAME) String encodedFilename,
      @HeaderParam(Headers.UPLOAD_OVERWRITE_VERSION) String overwriteHeader,
      @HeaderParam(HttpHeaders.CONTENT_LENGTH) Long contentLength,
      InputStream body) {

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

    logger.debug("Uploading new version of node with id: {}, overwrite: {}", nodeId, overwrite);

    Optional<Integer> version =
        blobService.uploadFileVersion(
            requester, body, contentLength == null ? -1L : contentLength, nodeId, decodedFilename, overwrite);
    if (version.isEmpty()) {
      throw notFoundOrForbidden(nodeId);
    }
    return uploadResponse(nodeId, version.get());
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
  public Response download(
      @HeaderParam("Cookie") String cookieHeader,
      @CookieParam(Headers.COOKIE_ZM_AUTH_TOKEN) String zmToken,
      @PathParam("nodeId") String nodeId) {
    return doDownload(cookieHeader, zmToken, nodeId, null);
  }

  @GET
  @Path("/download/{nodeId}/{version:\\d+}")
  @Blocking
  public Response downloadVersion(
      @HeaderParam("Cookie") String cookieHeader,
      @CookieParam(Headers.COOKIE_ZM_AUTH_TOKEN) String zmToken,
      @PathParam("nodeId") String nodeId,
      @PathParam("version") int version) {
    return doDownload(cookieHeader, zmToken, nodeId, version);
  }

  private Response doDownload(String cookieHeader, String zmToken, String nodeId, Integer version) {
    UserMyself requester = authenticator.requireUser(cookieHeader, zmToken);
    Optional<BlobResponse> blob = blobService.downloadFileById(nodeId, version, requester);
    if (blob.isEmpty()) {
      throw notFoundOrForbidden(nodeId);
    }
    return BlobHttpResponses.streamBlob(blob.get());
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
  public Response downloadMultiple(
      @HeaderParam("Cookie") String cookieHeader,
      @CookieParam(Headers.COOKIE_ZM_AUTH_TOKEN) String zmToken,
      @FormParam(NODE_IDS) String nodeIdsJson) {
    UserMyself requester = authenticator.requireUser(cookieHeader, zmToken);
    List<String> nodeIds = parseNodeIdsArray(nodeIdsJson);
    ZipDownload zip =
        blobService
            .downloadMultiple(nodeIds, requester)
            .orElseThrow(
                () ->
                    new NoSuchElementException(
                        "Some nodes do not exist or the user lacks permission: " + nodeIds));
    return BlobHttpResponses.streamZip(zip, blobService);
  }

  @POST
  @Path("/download-multiple/check")
  @Blocking
  @Consumes(MediaType.APPLICATION_JSON)
  public Response checkDownloadMultiple(
      @HeaderParam("Cookie") String cookieHeader,
      @CookieParam(Headers.COOKIE_ZM_AUTH_TOKEN) String zmToken,
      String jsonBody) {
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

  private boolean isRequestSizeOverLimit(Long contentLength) {
    if (contentLength == null) {
      return false;
    }
    double blobLengthInMB = contentLength / (1024.0 * 1024.0);
    Optional<Integer> maxFileSize = filesConfig.getMaxUploadableFileSizeInMb();
    return maxFileSize.isPresent() && blobLengthInMB > maxFileSize.get();
  }

  /**
   * Base64-decodes the {@code Filename} header (legacy encoding). Returns {@code null} if the header
   * is missing or not valid base64, which the callers translate into a 400.
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
   * Legacy parity (pinned by the acceptance suite): the legacy Netty stack collapsed BOTH "node does
   * not exist" and "node exists but requester lacks permission" into the same 404 (both surfaced as
   * {@link NoSuchElementException} → {@code "404 Not Found"}). The acceptance tests assert exactly
   * that indistinguishable 404 shape for both cases (e.g. {@code AuthenticatedDownloadApiIT},
   * {@code InternalUploadApiIT}), so a permission failure must NOT surface as a "more precise" 403.
   */
  private RuntimeException notFoundOrForbidden(String nodeId) {
    return new NoSuchElementException("Node " + nodeId + " does not exist or is not accessible");
  }
}
