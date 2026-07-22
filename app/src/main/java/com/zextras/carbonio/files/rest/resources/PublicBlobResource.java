// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.rest.resources;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zextras.carbonio.files.Constants.API.BodyAttributes;
import com.zextras.carbonio.files.config.TransferPool;
import com.zextras.carbonio.files.dal.dao.ebean.Node;
import com.zextras.carbonio.files.exceptions.AccessCodeRequiredException;
import com.zextras.carbonio.files.exceptions.BadRequestException;
import com.zextras.carbonio.files.rest.services.BlobService;
import com.zextras.carbonio.files.rest.services.BlobService.ZipDownload;
import com.zextras.carbonio.files.rest.types.BlobResponse;
import io.smallrye.common.annotation.Blocking;
import io.smallrye.mutiny.Uni;
import io.vertx.core.http.HttpServerResponse;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.FormParam;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;

/**
 * Public (unauthenticated) blob download endpoints. Quarkus/RESTEasy Reactive port of the legacy
 * Netty {@code PublicBlobController}. These endpoints require NO auth cookie: access is granted
 * solely by a valid, non-expired public link (plus an optional access code); permission enforcement
 * is delegated to {@link BlobService}, which validates the link against each node.
 *
 * <p>Served at ROOT (no {@code quarkus.rest.path}). The download endpoints are {@code @Blocking} and
 * return a Mutiny {@code Uni}: link/permission resolution and opening the storages stream / ZIP plan
 * run in the method body on a worker thread (so a 404, or the access-code redirect, is produced
 * before any byte is written), then only the blob/ZIP byte pump is offloaded to the dedicated {@link
 * TransferPool} (see {@link TransferStreaming}). The {@code .../check} endpoints stay plain {@code
 * @Blocking} on the default worker pool.
 */
@Path("/")
@ApplicationScoped
public class PublicBlobResource {

  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  private final BlobService blobService;
  private final TransferPool transferPool;

  @Inject
  public PublicBlobResource(BlobService blobService, TransferPool transferPool) {
    this.blobService = blobService;
    this.transferPool = transferPool;
  }

  // --------------------------------------------------------------------- download via public link

  @GET
  @Path("/link/{publicLinkId}")
  @Blocking
  public Uni<Void> downloadByPublicLink(
      @PathParam("publicLinkId") String publicLinkId, @Context HttpServerResponse resp) {
    return doDownloadByPublicLink(publicLinkId, resp);
  }

  @GET
  @Path("/public/link/download/{publicLinkId}")
  @Blocking
  public Uni<Void> downloadViaPublicLink(
      @PathParam("publicLinkId") String publicLinkId, @Context HttpServerResponse resp) {
    return doDownloadByPublicLink(publicLinkId, resp);
  }

  private Uni<Void> doDownloadByPublicLink(String publicLinkId, HttpServerResponse resp) {
    Optional<BlobResponse> blobResponse;
    try {
      blobResponse = blobService.downloadFileByLink(publicLinkId);
    } catch (AccessCodeRequiredException e) {
      // Link is protected by an access code: redirect to the access page (same as legacy).
      // Legacy parity: the legacy HttpResponseBuilder#createRedirectHttpResponse writes the
      // Location header value VERBATIM (a relative path). Response.temporaryRedirect(URI)/
      // .location(URI) instead resolve a non-absolute URI against the request's base URI,
      // producing an absolute Location -- use a raw header() to keep it relative. Thrown as a
      // WebApplicationException so BlobExceptionMapper passes the redirect response through verbatim
      // (this method now returns Uni<Void>, streaming directly to the Vert.x response otherwise).
      throw new WebApplicationException(
          Response.status(Response.Status.TEMPORARY_REDIRECT)
              .header(HttpHeaders.LOCATION, "/files/public/link/access/" + publicLinkId)
              .build());
    }

    BlobResponse blob =
        blobResponse.orElseThrow(
            () ->
                new NoSuchElementException(
                    "The link and/or the node associated to it does not exist: " + publicLinkId));
    return TransferStreaming.streamBlob(blob, resp, transferPool.get());
  }

  // ----------------------------------------------------------------- download public file by node

  @GET
  @Path("/public/download/{nodeId}")
  @Blocking
  public Uni<Void> downloadPublicFile(
      @PathParam("nodeId") String nodeId,
      @QueryParam("node_link_id") String nodeLinkId,
      @QueryParam("access_code") String accessCode,
      @Context HttpServerResponse resp) {

    BlobResponse blob =
        blobService
            .downloadPublicFileById(nodeId, nodeLinkId, accessCode)
            .orElseThrow(
                () ->
                    new NoSuchElementException(
                        "The file does not exist or it is not contained on a public folder: "
                            + nodeId));
    return TransferStreaming.streamBlob(blob, resp, transferPool.get());
  }

  @GET
  @Path("/public/download/{nodeId}/check")
  @Blocking
  public Response checkDownloadPublicFile(
      @Context UriInfo uriInfo,
      @PathParam("nodeId") String nodeId,
      @QueryParam("node_link_id") String nodeLinkId,
      @QueryParam("access_code") String accessCode) {

    requireNodeLinkIdFirst(uriInfo);

    Optional<Node> node = blobService.checkDownloadPublicFileById(nodeId, nodeLinkId, accessCode);
    if (node.isEmpty()) {
      throw new NoSuchElementException("Node " + nodeId + " not accessible with provided link");
    }
    return Response.noContent().build();
  }

  // ----------------------------------------------------------------------- public multi download

  @POST
  @Path("/public/download-multiple")
  @Blocking
  @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
  public Uni<Void> downloadPublicMultiple(
      @FormParam(BodyAttributes.NODE_IDS) String nodeIdsJson,
      @FormParam(BodyAttributes.NODE_LINK_ID) String nodeLinkId,
      @FormParam(BodyAttributes.ACCESS_CODE) String accessCode,
      @Context HttpServerResponse resp) {

    if (nodeIdsJson == null || nodeLinkId == null) {
      throw new IllegalArgumentException("Missing required parameters");
    }
    List<String> nodeIds = parseNodeIdsArray(nodeIdsJson);

    ZipDownload zip =
        blobService
            .downloadPublicMultiple(nodeIds, nodeLinkId, accessCode)
            .orElseThrow(
                () -> new NoSuchElementException("Nodes not accessible with provided link"));
    return TransferStreaming.streamZip(zip, blobService, resp, transferPool.get());
  }

  @POST
  @Path("/public/download-multiple/check")
  @Blocking
  @Consumes(MediaType.APPLICATION_JSON)
  public Response checkDownloadPublicMultiple(String jsonBody) throws BadRequestException {
    if (jsonBody == null || jsonBody.isBlank()) {
      throw new BadRequestException();
    }
    List<String> nodeIds;
    String nodeLinkId;
    String accessCode;
    try {
      Map<String, Object> map =
          OBJECT_MAPPER.readValue(jsonBody, new TypeReference<Map<String, Object>>() {});
      nodeIds = OBJECT_MAPPER.convertValue(map.get(BodyAttributes.NODE_IDS), new TypeReference<>() {});
      nodeLinkId = (String) map.get(BodyAttributes.NODE_LINK_ID);
      accessCode = (String) map.get(BodyAttributes.ACCESS_CODE);
    } catch (JsonProcessingException e) {
      throw new IllegalArgumentException("Invalid JSON body", e);
    }

    Optional<List<Node>> nodes =
        blobService.checkDownloadPublicMultiple(nodeIds, nodeLinkId, accessCode);
    if (nodes.isEmpty()) {
      throw new NoSuchElementException("Some nodes not accessible with provided link");
    }
    return Response.noContent().build();
  }

  private List<String> parseNodeIdsArray(String nodeIdsJson) {
    try {
      // Legacy parity: PublicBlobController#downloadPublicMultiple never validated the parsed
      // nodeIds list for emptiness -- an empty list is passed straight through to
      // BlobService#downloadPublicMultiple, whose checkDownloadMultipleInternal treats an empty
      // list as "not found" (Optional.empty()), surfacing as the SAME 404 as any other
      // not-accessible node set, never a 400. Do not reject it upfront here.
      return OBJECT_MAPPER.readValue(nodeIdsJson, new TypeReference<>() {});
    } catch (JsonProcessingException e) {
      throw new IllegalArgumentException("Invalid nodeIds JSON", e);
    }
  }

  /**
   * Legacy parity: {@code DOWNLOAD_PUBLIC_FILE_CHECK}'s regex (see {@code
   * Constants.API.Endpoints}) hard-codes {@code node_link_id} as the FIRST query parameter
   * (immediately after {@code ?}), with {@code access_code} (if present) only allowed to follow
   * it. Any other order matches NO route in the legacy {@code HttpRoutingHandler} at all, falling
   * straight to its raw 404 fallback -- {@code PublicBlobController} is never invoked. JAX-RS binds
   * {@code @QueryParam} by name regardless of order, so this replicates the order constraint by
   * inspecting the raw query string.
   */
  private static void requireNodeLinkIdFirst(UriInfo uriInfo) {
    String rawQuery = uriInfo.getRequestUri().getRawQuery();
    if (rawQuery == null || !rawQuery.startsWith("node_link_id=")) {
      throw new NotFoundException();
    }
  }
}
