// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.rest.resources;

import com.zextras.carbonio.files.Constants.API.Headers;
import com.zextras.carbonio.files.config.TransferPool;
import com.zextras.carbonio.files.dal.dao.UserMyself;
import com.zextras.carbonio.files.dal.dao.ebean.ACL.SharePermission;
import com.zextras.carbonio.files.dal.dao.ebean.FileVersion;
import com.zextras.carbonio.files.dal.dao.ebean.Node;
import com.zextras.carbonio.files.dal.repositories.interfaces.FileVersionRepository;
import com.zextras.carbonio.files.dal.repositories.interfaces.NodeRepository;
import com.zextras.carbonio.files.exceptions.BadRequestException;
import com.zextras.carbonio.files.rest.services.PreviewService;
import com.zextras.carbonio.files.rest.types.BlobResponse;
import com.zextras.carbonio.files.rest.types.PreviewQueryParameters;
import com.zextras.carbonio.files.utilities.MimeTypeUtils;
import com.zextras.carbonio.files.utilities.PermissionsChecker;
import io.smallrye.common.annotation.Blocking;
import io.smallrye.mutiny.Uni;
import io.vertx.core.http.HttpServerResponse;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.CookieParam;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Response;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import org.apache.commons.lang3.tuple.Pair;
import org.eclipse.microprofile.openapi.annotations.enums.SchemaType;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponses;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Preview passthrough endpoints: {@code /preview/{image,pdf,document}/...}. Quarkus/JAX-RS port of
 * the legacy Netty {@code PreviewController}: same URL family, permission/mimetype checks and
 * ETag/{@code If-None-Match} caching, delegating the actual preview fetch to {@link
 * PreviewService}. Served at ROOT (see {@link BlobResource}).
 *
 * <p><b>Status-code parity with the legacy stack (intentional, pinned by the acceptance suite):</b>
 * the legacy {@code PreviewController#failureResponse} collapsed EVERY failure that wasn't a {@code
 * BadRequestException} into a 404 — this includes "node not found", "no permission", AND a genuine
 * carbonio-preview-side error surfaced by {@link PreviewService}. This resource replicates that
 * exact collapse (see {@link #collapseToNotFound}): only an unsupported mimetype for the requested
 * preview type surfaces as 400 ({@link IllegalArgumentException}); everything else, including a
 * failed preview fetch, surfaces as 404 ({@link NoSuchElementException}), both mapped by {@link
 * BlobExceptionMapper}.
 */
@Path("/preview")
@ApplicationScoped
public class PreviewResource {

  private static final Logger logger = LoggerFactory.getLogger(PreviewResource.class);

  private static final Set<String> IMAGE_MIME_TYPES = Set.of("image/");
  private static final Set<String> PDF_MIME_TYPES = Set.of("application/pdf");
  private static final Set<String> DOCUMENT_MIME_TYPES =
      Set.of(
          "application/vnd.ms-excel",
          "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
          "application/vnd.oasis.opendocument.spreadsheet",
          "application/vnd.ms-powerpoint",
          "application/vnd.openxmlformats-officedocument.presentationml.presentation",
          "application/vnd.oasis.opendocument.presentation",
          "application/msword",
          "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
          "application/vnd.oasis.opendocument.text");

  private final PreviewService previewService;
  private final PermissionsChecker permissionsChecker;
  private final NodeRepository nodeRepository;
  private final FileVersionRepository fileVersionRepository;
  private final MimeTypeUtils mimeTypeUtils;
  private final BlobAuthenticator authenticator;
  private final TransferPool transferPool;

  @Inject
  public PreviewResource(
      PreviewService previewService,
      PermissionsChecker permissionsChecker,
      NodeRepository nodeRepository,
      FileVersionRepository fileVersionRepository,
      MimeTypeUtils mimeTypeUtils,
      BlobAuthenticator authenticator,
      TransferPool transferPool) {
    this.previewService = previewService;
    this.permissionsChecker = permissionsChecker;
    this.nodeRepository = nodeRepository;
    this.fileVersionRepository = fileVersionRepository;
    this.mimeTypeUtils = mimeTypeUtils;
    this.authenticator = authenticator;
    this.transferPool = transferPool;
  }

  // ------------------------------------------------------------------------------------- image

  @GET
  @Path("/image/{nodeId}/{area: [\\d]*x[\\d]*}")
  @Blocking
  @APIResponses({
    @APIResponse(
        responseCode = "200",
        description = "Preview bytes",
        content =
            @Content(
                mediaType = "application/octet-stream",
                schema = @Schema(type = SchemaType.STRING, format = "binary"))),
    @APIResponse(responseCode = "304", description = "Not modified (ETag matched)"),
    @APIResponse(responseCode = "400", description = "Unsupported mimetype for this preview type"),
    @APIResponse(responseCode = "404", description = "Node not found or not accessible")
  })
  public Uni<Void> previewImage(
      @HeaderParam("Cookie") String cookieHeader,
      @CookieParam(Headers.COOKIE_ZM_AUTH_TOKEN) String zmToken,
      @Context HttpHeaders httpHeaders,
      @PathParam("nodeId") String nodeId,
      @PathParam("area") String area,
      @QueryParam("quality") String quality,
      @QueryParam("output_format") String outputFormat,
      @QueryParam("crop") Boolean crop,
      @QueryParam("shape") String shape,
      @QueryParam("first_page") Integer firstPage,
      @QueryParam("last_page") Integer lastPage,
      @QueryParam("version") String version,
      @Context HttpServerResponse resp) {
    String ifNoneMatch = httpHeaders.getHeaderString(HttpHeaders.IF_NONE_MATCH);
    UserMyself requester = authenticator.requireUser(cookieHeader, zmToken);
    PreviewQueryParameters queryParameters =
        buildQueryParameters(quality, outputFormat, crop, shape, firstPage, lastPage, version);

    Pair<Node, FileVersion> checked =
        checkNodePermissionAndExistence(
            requester.getId().getUserId(),
            nodeId,
            queryParameters.getNodeVersion().orElse(null),
            IMAGE_MIME_TYPES);
    String fileDigest = checked.getRight().getDigest();

    if (!isPreviewChanged(ifNoneMatch, fileDigest)) {
      return notModified(resp, fileDigest);
    }

    BlobResponse blob =
        previewService
            .getPreviewOfImage(
                checked.getLeft().getOwnerId(),
                nodeId,
                checked.getRight().getVersion(),
                area,
                queryParameters)
            .getOrElseThrow(this::collapseToNotFound);

    return streamPreview(resp, blob, fileDigest);
  }

  @GET
  @Path("/image/{nodeId}/{area: [\\d]*x[\\d]*}/thumbnail")
  @Blocking
  @APIResponses({
    @APIResponse(
        responseCode = "200",
        description = "Preview bytes",
        content =
            @Content(
                mediaType = "application/octet-stream",
                schema = @Schema(type = SchemaType.STRING, format = "binary"))),
    @APIResponse(responseCode = "304", description = "Not modified (ETag matched)"),
    @APIResponse(responseCode = "400", description = "Unsupported mimetype for this preview type"),
    @APIResponse(responseCode = "404", description = "Node not found or not accessible")
  })
  public Uni<Void> thumbnailImage(
      @HeaderParam("Cookie") String cookieHeader,
      @CookieParam(Headers.COOKIE_ZM_AUTH_TOKEN) String zmToken,
      @Context HttpHeaders httpHeaders,
      @PathParam("nodeId") String nodeId,
      @PathParam("area") String area,
      @QueryParam("quality") String quality,
      @QueryParam("output_format") String outputFormat,
      @QueryParam("crop") Boolean crop,
      @QueryParam("shape") String shape,
      @QueryParam("first_page") Integer firstPage,
      @QueryParam("last_page") Integer lastPage,
      @QueryParam("version") String version,
      @Context HttpServerResponse resp) {
    String ifNoneMatch = httpHeaders.getHeaderString(HttpHeaders.IF_NONE_MATCH);
    UserMyself requester = authenticator.requireUser(cookieHeader, zmToken);
    PreviewQueryParameters queryParameters =
        buildQueryParameters(quality, outputFormat, crop, shape, firstPage, lastPage, version);

    Pair<Node, FileVersion> checked =
        checkNodePermissionAndExistence(
            requester.getId().getUserId(),
            nodeId,
            queryParameters.getNodeVersion().orElse(null),
            IMAGE_MIME_TYPES);
    String fileDigest = checked.getRight().getDigest();

    if (!isPreviewChanged(ifNoneMatch, fileDigest)) {
      return notModified(resp, fileDigest);
    }

    BlobResponse blob =
        previewService
            .getThumbnailOfImage(
                checked.getLeft().getOwnerId(),
                nodeId,
                checked.getRight().getVersion(),
                area,
                queryParameters)
            .getOrElseThrow(this::collapseToNotFound);

    return streamPreview(resp, blob, fileDigest);
  }

  // --------------------------------------------------------------------------------------- pdf

  @GET
  @Path("/pdf/{nodeId}")
  @Blocking
  @APIResponses({
    @APIResponse(
        responseCode = "200",
        description = "Preview bytes",
        content =
            @Content(
                mediaType = "application/octet-stream",
                schema = @Schema(type = SchemaType.STRING, format = "binary"))),
    @APIResponse(responseCode = "304", description = "Not modified (ETag matched)"),
    @APIResponse(responseCode = "400", description = "Unsupported mimetype for this preview type"),
    @APIResponse(responseCode = "404", description = "Node not found or not accessible")
  })
  public Uni<Void> previewPdf(
      @HeaderParam("Cookie") String cookieHeader,
      @CookieParam(Headers.COOKIE_ZM_AUTH_TOKEN) String zmToken,
      @Context HttpHeaders httpHeaders,
      @PathParam("nodeId") String nodeId,
      @QueryParam("quality") String quality,
      @QueryParam("output_format") String outputFormat,
      @QueryParam("crop") Boolean crop,
      @QueryParam("shape") String shape,
      @QueryParam("first_page") Integer firstPage,
      @QueryParam("last_page") Integer lastPage,
      @QueryParam("version") String version,
      @Context HttpServerResponse resp) {
    String ifNoneMatch = httpHeaders.getHeaderString(HttpHeaders.IF_NONE_MATCH);
    UserMyself requester = authenticator.requireUser(cookieHeader, zmToken);
    PreviewQueryParameters queryParameters =
        buildQueryParameters(quality, outputFormat, crop, shape, firstPage, lastPage, version);

    Pair<Node, FileVersion> checked =
        checkNodePermissionAndExistence(
            requester.getId().getUserId(),
            nodeId,
            queryParameters.getNodeVersion().orElse(null),
            PDF_MIME_TYPES);
    String fileDigest = checked.getRight().getDigest();

    if (!isPreviewChanged(ifNoneMatch, fileDigest)) {
      return notModified(resp, fileDigest);
    }

    BlobResponse blob =
        previewService
            .getPreviewOfPdf(
                checked.getLeft().getOwnerId(),
                nodeId,
                checked.getRight().getVersion(),
                queryParameters)
            .getOrElseThrow(this::collapseToNotFound);

    return streamPreview(resp, blob, fileDigest);
  }

  @GET
  @Path("/pdf/{nodeId}/{area: [\\d]*x[\\d]*}/thumbnail")
  @Blocking
  @APIResponses({
    @APIResponse(
        responseCode = "200",
        description = "Preview bytes",
        content =
            @Content(
                mediaType = "application/octet-stream",
                schema = @Schema(type = SchemaType.STRING, format = "binary"))),
    @APIResponse(responseCode = "304", description = "Not modified (ETag matched)"),
    @APIResponse(responseCode = "400", description = "Unsupported mimetype for this preview type"),
    @APIResponse(responseCode = "404", description = "Node not found or not accessible")
  })
  public Uni<Void> thumbnailPdf(
      @HeaderParam("Cookie") String cookieHeader,
      @CookieParam(Headers.COOKIE_ZM_AUTH_TOKEN) String zmToken,
      @Context HttpHeaders httpHeaders,
      @PathParam("nodeId") String nodeId,
      @PathParam("area") String area,
      @QueryParam("quality") String quality,
      @QueryParam("output_format") String outputFormat,
      @QueryParam("crop") Boolean crop,
      @QueryParam("shape") String shape,
      @QueryParam("first_page") Integer firstPage,
      @QueryParam("last_page") Integer lastPage,
      @QueryParam("version") String version,
      @Context HttpServerResponse resp) {
    String ifNoneMatch = httpHeaders.getHeaderString(HttpHeaders.IF_NONE_MATCH);
    UserMyself requester = authenticator.requireUser(cookieHeader, zmToken);
    PreviewQueryParameters queryParameters =
        buildQueryParameters(quality, outputFormat, crop, shape, firstPage, lastPage, version);

    Pair<Node, FileVersion> checked =
        checkNodePermissionAndExistence(
            requester.getId().getUserId(),
            nodeId,
            queryParameters.getNodeVersion().orElse(null),
            PDF_MIME_TYPES);
    String fileDigest = checked.getRight().getDigest();

    if (!isPreviewChanged(ifNoneMatch, fileDigest)) {
      return notModified(resp, fileDigest);
    }

    BlobResponse blob =
        previewService
            .getThumbnailOfPdf(
                checked.getLeft().getOwnerId(),
                nodeId,
                checked.getRight().getVersion(),
                area,
                queryParameters)
            .getOrElseThrow(this::collapseToNotFound);

    return streamPreview(resp, blob, fileDigest);
  }

  // ---------------------------------------------------------------------------------- document

  @GET
  @Path("/document/{nodeId}")
  @Blocking
  @APIResponses({
    @APIResponse(
        responseCode = "200",
        description = "Preview bytes",
        content =
            @Content(
                mediaType = "application/octet-stream",
                schema = @Schema(type = SchemaType.STRING, format = "binary"))),
    @APIResponse(responseCode = "304", description = "Not modified (ETag matched)"),
    @APIResponse(responseCode = "400", description = "Unsupported mimetype for this preview type"),
    @APIResponse(responseCode = "404", description = "Node not found or not accessible")
  })
  public Uni<Void> previewDocument(
      @HeaderParam("Cookie") String cookieHeader,
      @CookieParam(Headers.COOKIE_ZM_AUTH_TOKEN) String zmToken,
      @Context HttpHeaders httpHeaders,
      @PathParam("nodeId") String nodeId,
      @QueryParam("quality") String quality,
      @QueryParam("output_format") String outputFormat,
      @QueryParam("crop") Boolean crop,
      @QueryParam("shape") String shape,
      @QueryParam("first_page") Integer firstPage,
      @QueryParam("last_page") Integer lastPage,
      @QueryParam("version") String version,
      @Context HttpServerResponse resp) {
    String ifNoneMatch = httpHeaders.getHeaderString(HttpHeaders.IF_NONE_MATCH);
    UserMyself requester = authenticator.requireUser(cookieHeader, zmToken);
    PreviewQueryParameters queryParameters =
        buildQueryParameters(quality, outputFormat, crop, shape, firstPage, lastPage, version);
    String langTag = requester.getLocale().toLanguageTag();
    queryParameters.setLangTag(langTag);
    logger.debug("Set language tag for preview to {}", langTag);

    Pair<Node, FileVersion> checked =
        checkNodePermissionAndExistence(
            requester.getId().getUserId(),
            nodeId,
            queryParameters.getNodeVersion().orElse(null),
            DOCUMENT_MIME_TYPES);
    String fileDigestWithLanguage = checked.getRight().getDigest() + langTag;

    if (!isPreviewChanged(ifNoneMatch, fileDigestWithLanguage)) {
      return notModified(resp, fileDigestWithLanguage);
    }

    BlobResponse blob =
        previewService
            .getPreviewOfDocument(
                checked.getLeft().getOwnerId(),
                nodeId,
                checked.getRight().getVersion(),
                queryParameters)
            .getOrElseThrow(this::collapseToNotFound);

    return streamPreview(resp, blob, fileDigestWithLanguage);
  }

  @GET
  @Path("/document/{nodeId}/{area: [\\d]*x[\\d]*}/thumbnail")
  @Blocking
  @APIResponses({
    @APIResponse(
        responseCode = "200",
        description = "Preview bytes",
        content =
            @Content(
                mediaType = "application/octet-stream",
                schema = @Schema(type = SchemaType.STRING, format = "binary"))),
    @APIResponse(responseCode = "304", description = "Not modified (ETag matched)"),
    @APIResponse(responseCode = "400", description = "Unsupported mimetype for this preview type"),
    @APIResponse(responseCode = "404", description = "Node not found or not accessible")
  })
  public Uni<Void> thumbnailDocument(
      @HeaderParam("Cookie") String cookieHeader,
      @CookieParam(Headers.COOKIE_ZM_AUTH_TOKEN) String zmToken,
      @Context HttpHeaders httpHeaders,
      @PathParam("nodeId") String nodeId,
      @PathParam("area") String area,
      @QueryParam("quality") String quality,
      @QueryParam("output_format") String outputFormat,
      @QueryParam("crop") Boolean crop,
      @QueryParam("shape") String shape,
      @QueryParam("first_page") Integer firstPage,
      @QueryParam("last_page") Integer lastPage,
      @QueryParam("version") String version,
      @Context HttpServerResponse resp) {
    String ifNoneMatch = httpHeaders.getHeaderString(HttpHeaders.IF_NONE_MATCH);
    UserMyself requester = authenticator.requireUser(cookieHeader, zmToken);
    PreviewQueryParameters queryParameters =
        buildQueryParameters(quality, outputFormat, crop, shape, firstPage, lastPage, version);
    String langTag = requester.getLocale().toLanguageTag();
    queryParameters.setLangTag(langTag);
    logger.debug("Set language tag for preview to {}", langTag);

    Pair<Node, FileVersion> checked =
        checkNodePermissionAndExistence(
            requester.getId().getUserId(),
            nodeId,
            queryParameters.getNodeVersion().orElse(null),
            DOCUMENT_MIME_TYPES);
    String fileDigestWithLanguage = checked.getRight().getDigest() + langTag;

    if (!isPreviewChanged(ifNoneMatch, fileDigestWithLanguage)) {
      return notModified(resp, fileDigestWithLanguage);
    }

    BlobResponse blob =
        previewService
            .getThumbnailOfDocument(
                checked.getLeft().getOwnerId(),
                nodeId,
                checked.getRight().getVersion(),
                area,
                queryParameters)
            .getOrElseThrow(this::collapseToNotFound);

    return streamPreview(resp, blob, fileDigestWithLanguage);
  }

  // ---------------------------------------------------------------------------- unmatched (400)

  /**
   * Legacy parity: the legacy Netty routing captured ANY sub-path under {@code /preview/} (see
   * {@code Constants.API.Endpoints#PREVIEW}, {@code SERVICE + "preview/(.*)"}) and handed it to
   * {@code PreviewController}, which fell back to a generic {@code BadRequestException} (→ 400)
   * whenever none of the 6 image/pdf/document sub-patterns matched it. JAX-RS routing has no such
   * catch-all: an unmatched {@code /preview/**} sub-path would otherwise 404 straight out of the
   * router, never reaching {@link BlobExceptionMapper}. This wildcard template is strictly LESS
   * specific (fewer literal characters) than every {@code image/pdf/document} template above, so
   * per the JAX-RS matching algorithm it is only ever selected once none of them match the path.
   *
   * <p>Legacy parity (restored): {@code HttpRoutingHandler#channelRead0} placed {@code
   * auth-handler} before {@code preview-handler} for the WHOLE {@code /preview/**} family (lines
   * ~179-186), so even a request that falls through to this generic 400 was authenticated FIRST.
   * This was the only method in the class that never called {@link BlobAuthenticator#requireUser},
   * so an unauthenticated request reached the 400 without ever being challenged — restored below.
   */
  @GET
  @Path("/{unmatched: .*}")
  public Response unmatchedPreviewPath(
      @HeaderParam("Cookie") String cookieHeader,
      @CookieParam(Headers.COOKIE_ZM_AUTH_TOKEN) String zmToken,
      @PathParam("unmatched") String unmatched)
      throws BadRequestException {
    authenticator.requireUser(cookieHeader, zmToken);
    throw new BadRequestException();
  }

  // --------------------------------------------------------------------------------------- shared

  private PreviewQueryParameters buildQueryParameters(
      String quality,
      String outputFormat,
      Boolean crop,
      String shape,
      Integer firstPage,
      Integer lastPage,
      String version) {
    PreviewQueryParameters queryParameters = new PreviewQueryParameters();
    if (quality != null) {
      queryParameters.setQuality(quality);
    }
    if (outputFormat != null) {
      queryParameters.setOutputFormat(outputFormat);
    }
    if (crop != null) {
      queryParameters.setCrop(crop);
    }
    if (shape != null) {
      queryParameters.setShape(shape);
    }
    if (firstPage != null) {
      queryParameters.setFirstPage(firstPage);
    }
    if (lastPage != null) {
      queryParameters.setLastPage(lastPage);
    }
    if (version != null) {
      queryParameters.setNodeVersion(parseVersion(version));
    }
    return queryParameters;
  }

  /**
   * Legacy parity: the legacy {@code PreviewController} parsed {@code ?version=} itself and let an
   * unparsable value fall into its generic {@code IllegalArgumentException} catch branch (→ 400).
   * RESTEasy Reactive's own {@code @QueryParam Integer} conversion instead surfaces a param
   * conversion failure as a 404 (see {@code ParameterHandler}), so {@code version} is bound as a
   * raw {@code String} and parsed here. NOTE: the {@link IllegalArgumentException} below must NOT
   * carry {@code e} as its cause -- {@link BlobExceptionMapper} unwraps exactly one {@code
   * getCause()} level (matching the legacy {@code ExceptionsHandler}), so a non-null cause here
   * would make the mapper switch on the raw {@link NumberFormatException} instead and misroute this
   * to 500.
   */
  private static Integer parseVersion(String rawVersion) {
    try {
      return Integer.valueOf(rawVersion);
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException("Invalid version query parameter: " + rawVersion);
    }
  }

  /**
   * Checks that {@code requesterId} can read {@code nodeId} (or its explicit {@code version}) and
   * that the resolved file version's mimetype belongs to {@code allowedMimeTypes}.
   *
   * @throws IllegalArgumentException if the mimetype is not supported by the requested preview type
   *     (→ 400)
   * @throws NoSuchElementException if the node/version does not exist OR the requester lacks READ
   *     permission — collapsed to the same outcome, matching the legacy behaviour (→ 404)
   */
  private Pair<Node, FileVersion> checkNodePermissionAndExistence(
      String requesterId, String nodeId, Integer version, Set<String> allowedMimeTypes) {
    Optional<FileVersion> optFileVersion =
        (version == null)
            ? fileVersionRepository.getLastFileVersion(nodeId)
            : fileVersionRepository.getFileVersion(nodeId, version);

    if (permissionsChecker.getPermissions(nodeId, requesterId).has(SharePermission.READ_ONLY)
        && optFileVersion.isPresent()) {
      FileVersion fileVersion = optFileVersion.get();
      if (!mimeTypeUtils.isMimeTypeAllowed(fileVersion.getMimeType(), allowedMimeTypes)) {
        throw new IllegalArgumentException(
            "Unsupported mimetype for this preview type: " + fileVersion.getMimeType());
      }
      Node node =
          nodeRepository
              .getNode(nodeId)
              .orElseThrow(() -> new NoSuchElementException("Node not found: " + nodeId));
      return Pair.of(node, fileVersion);
    }

    throw new NoSuchElementException(
        "Node "
            + nodeId
            + " requested by "
            + requesterId
            + " does not exist or requester lacks permission to read it");
  }

  /**
   * Legacy parity (see class javadoc): any failure surfaced by {@link PreviewService} — including a
   * genuine carbonio-preview-side error — becomes a 404, never a 500.
   */
  private RuntimeException collapseToNotFound(Throwable failure) {
    logger.warn(
        "Preview fetch failed, surfacing as not-found (legacy parity): {}", failure.getMessage());
    return new NoSuchElementException("Preview not available");
  }

  /**
   * Legacy parity gotcha: an empty (but present) {@code If-None-Match} header — which is exactly
   * what a zero-length file digest round-trips to (see {@link #base64}) — must still compare equal,
   * not be treated as "no header sent". {@code @HeaderParam} binds an empty header value to {@code
   * null} in RESTEasy Reactive (indistinguishable from an absent header), so callers read this
   * value via {@code HttpHeaders#getHeaderString} instead, which preserves the distinction.
   */
  private boolean isPreviewChanged(String ifNoneMatch, String fileDigest) {
    String base64Digest = base64(fileDigest);
    return ifNoneMatch == null || !ifNoneMatch.equals(base64Digest);
  }

  /**
   * Streams the preview to the client over the raw Vert.x response (via {@link TransferStreaming}),
   * with {@code Content-Type}/{@code Content-Disposition}/{@code Content-Length} set by the
   * transfer and the preview-specific {@code ETag}/{@code Cache-Control} passed as extra headers.
   * No whole-preview buffering: the carbonio-preview SDK stream is pumped chunk-by-chunk.
   */
  private Uni<Void> streamPreview(HttpServerResponse resp, BlobResponse blob, String fileDigest) {
    return TransferStreaming.streamBlob(
        blob,
        resp,
        transferPool.get(),
        Map.of(HttpHeaders.CACHE_CONTROL, "no-cache", HttpHeaders.ETAG, base64(fileDigest)));
  }

  private Uni<Void> notModified(HttpServerResponse resp, String fileDigest) {
    return TransferStreaming.notModified(
        resp, Map.of(HttpHeaders.CACHE_CONTROL, "no-cache", HttpHeaders.ETAG, base64(fileDigest)));
  }

  private static String base64(String digest) {
    return Base64.getEncoder().encodeToString(digest.getBytes(StandardCharsets.UTF_8));
  }
}
