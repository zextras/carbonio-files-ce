// SPDX-FileCopyrightText: 2022 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.rest.controllers;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.inject.Inject;
import com.zextras.carbonio.files.Files.API.ContextAttribute;
import com.zextras.carbonio.files.Files.API.Endpoints;
import com.zextras.carbonio.files.dal.dao.UserMyself;
import com.zextras.carbonio.files.dal.dao.ebean.ACL.SharePermission;
import com.zextras.carbonio.files.dal.dao.ebean.FileVersion;
import com.zextras.carbonio.files.dal.dao.ebean.Node;
import com.zextras.carbonio.files.dal.repositories.interfaces.FileVersionRepository;
import com.zextras.carbonio.files.dal.repositories.interfaces.NodeRepository;
import com.zextras.carbonio.files.exceptions.BadRequestException;
import com.zextras.carbonio.files.exceptions.InternalServerErrorException;
import com.zextras.carbonio.files.exceptions.NodeNotFoundException;
import com.zextras.carbonio.files.netty.utilities.NettyBufferWriter;
import com.zextras.carbonio.files.rest.services.PreviewService;
import com.zextras.carbonio.files.rest.types.BlobResponse;
import com.zextras.carbonio.files.rest.types.PreviewQueryParameters;
import com.zextras.carbonio.files.utilities.MimeTypeUtils;
import com.zextras.carbonio.files.utilities.PermissionsChecker;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.util.AttributeKey;
import io.vavr.control.Try;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.apache.commons.codec.binary.Base64;
import org.apache.commons.lang3.tuple.Pair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@ChannelHandler.Sharable
public class PreviewController extends SimpleChannelInboundHandler<HttpRequest> {

  private static final Logger logger = LoggerFactory.getLogger(PreviewController.class);

  private final PreviewService previewService;
  private final PermissionsChecker permissionsChecker;
  private final FileVersionRepository fileVersionRepository;
  private final MimeTypeUtils mimeTypeUtils;
  private final NodeRepository nodeRepository;

  private final Set<String> documentAllowedTypes =
      Stream.of(
              "application/vnd.ms-excel",
              "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
              "application/vnd.oasis.opendocument.spreadsheet",
              "application/vnd.ms-powerpoint",
              "application/vnd.openxmlformats-officedocument.presentationml.presentation",
              "application/vnd.oasis.opendocument.presentation",
              "application/msword",
              "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
              "application/vnd.oasis.opendocument.text")
          .collect(Collectors.toUnmodifiableSet());

  @Inject
  public PreviewController(
      PreviewService previewService,
      PermissionsChecker permissionsChecker,
      NodeRepository nodeRepository,
      FileVersionRepository fileVersionRepository,
      MimeTypeUtils mimeTypeUtils) {
    super(true);
    this.previewService = previewService;
    this.permissionsChecker = permissionsChecker;
    this.nodeRepository = nodeRepository;
    this.fileVersionRepository = fileVersionRepository;
    this.mimeTypeUtils = mimeTypeUtils;
  }

  @Override
  protected void channelRead0(ChannelHandlerContext context, HttpRequest httpRequest) {
    try {
      String uriRequest = httpRequest.uri();
      Matcher previewImageMatcher = Endpoints.PREVIEW_IMAGE.matcher(uriRequest);
      Matcher thumbnailImageMatcher = Endpoints.THUMBNAIL_IMAGE.matcher((uriRequest));
      Matcher previewPdfMatcher = Endpoints.PREVIEW_PDF.matcher((uriRequest));
      Matcher thumbnailPdfMatcher = Endpoints.THUMBNAIL_PDF.matcher((uriRequest));
      Matcher previewDocumentMatcher = Endpoints.PREVIEW_DOCUMENT.matcher((uriRequest));
      Matcher thumbnailDocumentMatcher = Endpoints.THUMBNAIL_DOCUMENT.matcher((uriRequest));

      UserMyself requester =
          (UserMyself)
              context.channel().attr(AttributeKey.valueOf(ContextAttribute.REQUESTER)).get();

      if (thumbnailImageMatcher.find() && httpRequest.method().equals(HttpMethod.GET)) {
        thumbnailImage(context, httpRequest, thumbnailImageMatcher, requester);
        return;
      }

      if (thumbnailPdfMatcher.find() && httpRequest.method().equals(HttpMethod.GET)) {
        thumbnailPdf(context, httpRequest, thumbnailPdfMatcher, requester);
        return;
      }

      if (thumbnailDocumentMatcher.find() && httpRequest.method().equals(HttpMethod.GET)) {
        thumbnailDocument(context, httpRequest, thumbnailDocumentMatcher, requester);
        return;
      }

      if (previewImageMatcher.find() && httpRequest.method().equals(HttpMethod.GET)) {
        previewImage(context, httpRequest, previewImageMatcher, requester);
        return;
      }

      if (previewPdfMatcher.find() && httpRequest.method().equals(HttpMethod.GET)) {
        previewPdf(context, httpRequest, previewPdfMatcher, requester);
        return;
      }

      if (previewDocumentMatcher.find() && httpRequest.method().equals(HttpMethod.GET)) {
        previewDocument(context, httpRequest, previewDocumentMatcher, requester);
        return;
      }

      logger.warn(
          String.format("Request %s %s: bad request", httpRequest.method(), httpRequest.uri()));

      context.fireExceptionCaught(new BadRequestException());

    } catch (IllegalArgumentException exception) {
      logger.warn(
          String.format(
              "Request %s: Illegal arguments in the request:\n%s",
              httpRequest.uri(), exception.getMessage()));
      context.fireExceptionCaught(new BadRequestException());

    } catch (Exception exception) {
      logger.error(
          String.format(
              "Request %s: Unexpected exception of type: %s with message: %s",
              httpRequest.uri(), exception.getClass(), exception.getMessage()));
      context.fireExceptionCaught(new InternalServerErrorException(exception.getMessage()));
    }
  }

  /**
   * This method handles extraction of metadata from uri and fetching of image's preview. The result
   * is sent through the netty channel
   *
   * @param context is a {@link ChannelHandlerContext} object in which to write the results.
   * @param httpRequest is a {@link HttpRequest}.
   * @param requester is a {@link UserMyself}, to check if the requester has the permission to view
   *     file.
   */
  private void previewImage(
      ChannelHandlerContext context,
      HttpRequest httpRequest,
      Matcher uriMatched,
      UserMyself requester) {

    String nodeId = uriMatched.group(1);
    String nodeVersion = uriMatched.group(2);
    String previewArea = uriMatched.group(3);

    PreviewQueryParameters queryParameters = parseQueryParameters(uriMatched.group(5));
    queryParameters.setLocale(
        requester.getLocale().getLanguage()
            + "-"
            + requester.getLocale().toLanguageTag().toUpperCase());

    Try<Pair<Node, FileVersion>> tryCheckNode =
        checkNodePermissionAndExistence(
            requester.getId(),
            nodeId,
            Integer.parseInt(nodeVersion),
            Collections.singleton("image/"));

    if (tryCheckNode.isSuccess()) {
      String fileDigest = tryCheckNode.get().getRight().getDigest();

      if (isPreviewChanged(httpRequest, fileDigest)) {
        previewService
            .getPreviewOfImage(
                tryCheckNode.get().getLeft().getOwnerId(),
                nodeId,
                Integer.parseInt(nodeVersion),
                previewArea,
                queryParameters)
            .onSuccess(blob -> successResponse(context, httpRequest, fileDigest, blob))
            .onFailure(failure -> failureResponse(context, httpRequest, failure));
      } else {
        unchangedPreviewResponse(context, httpRequest, fileDigest);
      }
    } else {
      failureResponse(context, httpRequest, tryCheckNode.failed().get());
    }
  }

  /**
   * This method handles extraction of metadata from uri and fetching of image's thumbnail. The
   * result is sent through the netty channel
   *
   * @param context is a {@link ChannelHandlerContext} object in which to write the results.
   * @param httpRequest is a {@link HttpRequest}.
   * @param requester is a {@link UserMyself}, to check if the requester has the permission to view
   *     file.
   */
  private void thumbnailImage(
      ChannelHandlerContext context,
      HttpRequest httpRequest,
      Matcher uriMatched,
      UserMyself requester) {
    String nodeId = uriMatched.group(1);
    String nodeVersion = uriMatched.group(2);
    String previewArea = uriMatched.group(3);

    PreviewQueryParameters queryParameters = parseQueryParameters(uriMatched.group(4));
    queryParameters.setLocale(
        requester.getLocale().getLanguage()
            + "-"
            + requester.getLocale().toLanguageTag().toUpperCase());

    Try<Pair<Node, FileVersion>> tryCheckNode =
        checkNodePermissionAndExistence(
            requester.getId(),
            nodeId,
            Integer.parseInt(nodeVersion),
            Collections.singleton("image/"));

    if (tryCheckNode.isSuccess()) {
      String fileDigest = tryCheckNode.get().getRight().getDigest();

      if (isPreviewChanged(httpRequest, fileDigest)) {
        previewService
            .getThumbnailOfImage(
                tryCheckNode.get().getLeft().getOwnerId(),
                nodeId,
                Integer.parseInt(nodeVersion),
                previewArea,
                queryParameters)
            .onSuccess(blob -> successResponse(context, httpRequest, fileDigest, blob))
            .onFailure(failure -> failureResponse(context, httpRequest, failure));
      } else {
        unchangedPreviewResponse(context, httpRequest, fileDigest);
      }
    } else {
      failureResponse(context, httpRequest, tryCheckNode.failed().get());
    }
  }

  /**
   * This method handles extraction of metadata from uri and fetching of pdf's preview. The result
   * is sent through the netty channel
   *
   * @param context is a {@link ChannelHandlerContext} object in which to write the results.
   * @param httpRequest is a {@link HttpRequest}.
   * @param requester is a {@link UserMyself}, to check if the requester has the permission to view
   *     file.
   */
  private void previewPdf(
      ChannelHandlerContext context,
      HttpRequest httpRequest,
      Matcher uriMatched,
      UserMyself requester) {

    String nodeId = uriMatched.group(1);
    String nodeVersion = uriMatched.group(2);

    PreviewQueryParameters queryParameters = parseQueryParameters(uriMatched.group(4));
    queryParameters.setLocale(
        requester.getLocale().getLanguage()
            + "-"
            + requester.getLocale().toLanguageTag().toUpperCase());

    Try<Pair<Node, FileVersion>> tryCheckNode =
        checkNodePermissionAndExistence(
            requester.getId(),
            nodeId,
            Integer.parseInt(nodeVersion),
            Collections.singleton("application/pdf"));

    if (tryCheckNode.isSuccess()) {
      String fileDigest = tryCheckNode.get().getRight().getDigest();

      if (isPreviewChanged(httpRequest, fileDigest)) {
        previewService
            .getPreviewOfPdf(
                tryCheckNode.get().getLeft().getOwnerId(),
                nodeId,
                Integer.parseInt(nodeVersion),
                queryParameters)
            .onSuccess(blob -> successResponse(context, httpRequest, fileDigest, blob))
            .onFailure(failure -> failureResponse(context, httpRequest, failure));
      } else {
        unchangedPreviewResponse(context, httpRequest, fileDigest);
      }
    } else {
      failureResponse(context, httpRequest, tryCheckNode.failed().get());
    }
  }

  /**
   * This method handles extraction of metadata from uri and fetching of pdf's thumbnail. The result
   * is sent through the netty channel
   *
   * @param context is a {@link ChannelHandlerContext} object in which to write the results.
   * @param httpRequest is a {@link HttpRequest}.
   * @param requester is a {@link UserMyself}, to check if the requester has the permission to view
   *     file.
   */
  private void thumbnailPdf(
      ChannelHandlerContext context,
      HttpRequest httpRequest,
      Matcher uriMatched,
      UserMyself requester) {

    String nodeId = uriMatched.group(1);
    String nodeVersion = uriMatched.group(2);
    String area = uriMatched.group(3);

    PreviewQueryParameters queryParameters = parseQueryParameters(uriMatched.group(4));
    queryParameters.setLocale(
        requester.getLocale().getLanguage()
            + "-"
            + requester.getLocale().toLanguageTag().toUpperCase());

    Try<Pair<Node, FileVersion>> tryCheckNode =
        checkNodePermissionAndExistence(
            requester.getId(),
            nodeId,
            Integer.parseInt(nodeVersion),
            Collections.singleton("application/pdf"));

    if (tryCheckNode.isSuccess()) {
      String fileDigest = tryCheckNode.get().getRight().getDigest();

      if (isPreviewChanged(httpRequest, fileDigest)) {
        previewService
            .getThumbnailOfPdf(
                tryCheckNode.get().getLeft().getOwnerId(),
                nodeId,
                Integer.parseInt(nodeVersion),
                area,
                queryParameters)
            .onSuccess(blob -> successResponse(context, httpRequest, fileDigest, blob))
            .onFailure(failure -> failureResponse(context, httpRequest, failure));
      } else {
        unchangedPreviewResponse(context, httpRequest, fileDigest);
      }
    } else {
      failureResponse(context, httpRequest, tryCheckNode.failed().get());
    }
  }

  /**
   * This method handles extraction of metadata from uri and fetching of document's preview. The
   * result is sent through the netty channel
   *
   * @param context is a {@link ChannelHandlerContext} object in which to write the results.
   * @param httpRequest is a {@link HttpRequest}.
   * @param requester is a {@link UserMyself}, to check if the requester has the permission to view
   *     file.
   */
  private void previewDocument(
      ChannelHandlerContext context,
      HttpRequest httpRequest,
      Matcher uriMatched,
      UserMyself requester) {

    String nodeId = uriMatched.group(1);
    String nodeVersion = uriMatched.group(2);

    PreviewQueryParameters queryParameters = parseQueryParameters(uriMatched.group(4));
    queryParameters.setLocale(
        requester.getLocale().getLanguage()
            + "-"
            + requester.getLocale().toLanguageTag().toUpperCase());

    Try<Pair<Node, FileVersion>> tryCheckNode =
        checkNodePermissionAndExistence(
            requester.getId(), nodeId, Integer.parseInt(nodeVersion), documentAllowedTypes);

    if (tryCheckNode.isSuccess()) {
      String fileDigest = tryCheckNode.get().getRight().getDigest();

      if (isPreviewChanged(httpRequest, fileDigest)) {
        previewService
            .getPreviewOfDocument(
                tryCheckNode.get().getLeft().getOwnerId(),
                nodeId,
                Integer.parseInt(nodeVersion),
                queryParameters)
            .onSuccess(blob -> successResponse(context, httpRequest, fileDigest, blob))
            .onFailure(failure -> failureResponse(context, httpRequest, failure));
      } else {
        unchangedPreviewResponse(context, httpRequest, fileDigest);
      }
    } else {
      failureResponse(context, httpRequest, tryCheckNode.failed().get());
    }
  }

  /**
   * This method handles extraction of metadata from uri and fetching of document's thumbnail. The
   * result is sent through the netty channel
   *
   * @param context is a {@link ChannelHandlerContext} object in which to write the results.
   * @param httpRequest is a {@link HttpRequest}.
   * @param requester is a {@link UserMyself}, to check if the requester has the permission to view
   *     file.
   */
  private void thumbnailDocument(
      ChannelHandlerContext context,
      HttpRequest httpRequest,
      Matcher uriMatched,
      UserMyself requester) {

    String nodeId = uriMatched.group(1);
    String nodeVersion = uriMatched.group(2);
    String area = uriMatched.group(3);

    PreviewQueryParameters queryParameters = parseQueryParameters(uriMatched.group(4));
    queryParameters.setLocale(
        requester.getLocale().getLanguage()
            + "-"
            + requester.getLocale().toLanguageTag().toUpperCase());

    Try<Pair<Node, FileVersion>> tryCheckNode =
        checkNodePermissionAndExistence(
            requester.getId(), nodeId, Integer.parseInt(nodeVersion), documentAllowedTypes);

    if (tryCheckNode.isSuccess()) {
      String fileDigest = tryCheckNode.get().getRight().getDigest();

      if (isPreviewChanged(httpRequest, fileDigest)) {
        previewService
            .getThumbnailOfDocument(
                tryCheckNode.get().getLeft().getOwnerId(),
                nodeId,
                Integer.parseInt(nodeVersion),
                area,
                queryParameters)
            .onSuccess(blob -> successResponse(context, httpRequest, fileDigest, blob))
            .onFailure(failure -> failureResponse(context, httpRequest, failure));
      } else {
        unchangedPreviewResponse(context, httpRequest, fileDigest);
      }
    } else {
      failureResponse(context, httpRequest, tryCheckNode.failed().get());
    }
  }

  /**
   * This method parses the given string and maps the extracted values to PreviewQueryParameters
   * class corresponding fields
   *
   * @param queryParameters is a {@link String} containing all the query parameters.
   * @return PreviewQueryParameters instance containing parsed data
   */
  private PreviewQueryParameters parseQueryParameters(String queryParameters) {

    Map<String, String> parameters =
        Arrays.stream(queryParameters.replace("?", "").split("&"))
            .map(parameter -> parameter.split("="))
            .filter(parameter -> parameter.length == 2)
            .collect(Collectors.toMap(parameter -> parameter[0], parameter -> parameter[1]));

    return new ObjectMapper().convertValue(parameters, PreviewQueryParameters.class);
  }

  /**
   * This method writes to the netty channel a successful response with metadata found in
   * blobResponse
   *
   * @param context is a {@link ChannelHandlerContext} object in which to write the results.
   * @param httpRequest is a {@link HttpRequest}.
   * @param blobResponse is a {@link BlobResponse} containing the metadata to write to context.
   */
  private void successResponse(
      ChannelHandlerContext context,
      HttpRequest httpRequest,
      String fileDigest,
      BlobResponse blobResponse) {
    DefaultHttpHeaders headers = new DefaultHttpHeaders(true);
    headers.add(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
    headers.add(HttpHeaderNames.CONTENT_LENGTH, blobResponse.getSize());
    headers.add(HttpHeaderNames.CONTENT_TYPE, blobResponse.getMimeType());

    // These headers are necessary to handle the client caching mechanism
    // (see: https://developer.mozilla.org/en-US/docs/Web/HTTP/Headers/ETag)
    //
    // The Etag header does not handle the comma character. For this reason we must encode
    // the digest in a base64 string.
    headers.add(HttpHeaderNames.CACHE_CONTROL, HttpHeaderValues.NO_CACHE);
    headers.add(
        HttpHeaderNames.ETAG,
        Base64.encodeBase64String(fileDigest.getBytes(StandardCharsets.UTF_8)));

    try {
      headers.add(
          HttpHeaderNames.CONTENT_DISPOSITION,
          "attachment; filename*=UTF-8''"
              + URLEncoder.encode(blobResponse.getFilename(), StandardCharsets.UTF_8));
    } catch (Exception exception) {
      logger.error(
          String.format(
              "Request %s: Exception of type: %s encountered while sending success response: %s",
              httpRequest.uri(), exception.getClass(), exception.getMessage()),
          exception);
    }

    context.write(
        new DefaultHttpResponse(httpRequest.protocolVersion(), HttpResponseStatus.OK, headers));

    // Writing input stream into the netty channel
    new NettyBufferWriter(context).writeStream(blobResponse.getBlobStream(), context.newPromise());
  }

  /**
   * Sends a {@link HttpResponseStatus#NOT_MODIFIED} status code in the {@link
   * ChannelHandlerContext#channel()} with the following headers necessary to handle the client
   * caching mechanism:
   *
   * <ul>
   *   <li>{@link HttpHeaderNames#CACHE_CONTROL} set to {@link HttpHeaderValues#NO_CACHE}
   *   <li>{@link HttpHeaderNames#ETAG} set to {@param fileDigest}
   * </ul>
   *
   * @param context is a {@link ChannelHandlerContext} object in which to write the results.
   * @param httpRequest is a {@link HttpRequest}.
   * @param fileDigest is a {@link String} representing the digest of the blob to preview
   */
  private void unchangedPreviewResponse(
      ChannelHandlerContext context, HttpRequest httpRequest, String fileDigest) {
    logger.info(
        String.format(
            "Request %s: Etag matched: %s. Response: %s",
            httpRequest.uri(), fileDigest, HttpResponseStatus.NOT_MODIFIED));

    // These headers are necessary to handle the client caching mechanism
    // (see: https://developer.mozilla.org/en-US/docs/Web/HTTP/Headers/ETag)
    //
    // The Etag header does not handle the comma character. For this reason we must encode
    // the digest in a base64 string.
    DefaultHttpHeaders headers = new DefaultHttpHeaders(true);
    headers.add(HttpHeaderNames.CACHE_CONTROL, HttpHeaderValues.NO_CACHE);

    headers.add(
        HttpHeaderNames.ETAG,
        Base64.encodeBase64String(fileDigest.getBytes(StandardCharsets.UTF_8)));

    context
        .writeAndFlush(
            new DefaultHttpResponse(
                httpRequest.protocolVersion(), HttpResponseStatus.NOT_MODIFIED, headers))
        .addListener(ChannelFutureListener.CLOSE);
  }

  /**
   * This method writes to the netty channel a failure response.
   *
   * @param context is a {@link ChannelHandlerContext} object in which to write the results.
   * @param httpRequest is a {@link HttpRequest}.
   * @param failure is a {@link Throwable} containing the cause of the failure.
   */
  private void failureResponse(
      ChannelHandlerContext context, HttpRequest httpRequest, Throwable failure) {
    logger.warn(
        String.format(
            "Request %s: Failed with message %s", httpRequest.uri(), failure.getMessage()));

    Throwable exception =
        (failure instanceof BadRequestException) ? failure : new NoSuchElementException();

    context.fireExceptionCaught(exception);
  }

  /**
   * This checks if the requested node can be accessed by the requester and if the node mimetype is
   * supported by the system.
   *
   * @param requesterId is a {@link String} representing the id of the requester.
   * @param nodeId is a {@link String } representing the nodeId of the node.
   * @param version is a <code> integer </code> representing the version of the node.
   * @param supportedMimeTypeList is a {@link Set} representing the allowed list or instance of
   *     mimetype that the calling methods allow (for instance a method may want only mimetype that
   *     are of "image" so "image/something" while another method "application" so
   *     "application/something".
   * @return a {@link Try#success} containing a {@link Pair} of the checked @{link Node} and the
   *     {@link FileVersion} or, a {@link Try#failure} containing the specific error.
   */
  private Try<Pair<Node, FileVersion>> checkNodePermissionAndExistence(
      String requesterId, String nodeId, int version, Set<String> supportedMimeTypeList) {
    Optional<FileVersion> optFileVersion = fileVersionRepository.getFileVersion(nodeId, version);
    if (permissionsChecker.getPermissions(nodeId, requesterId).has(SharePermission.READ_ONLY)
        && optFileVersion.isPresent()) {
      return (mimeTypeUtils.isMimeTypeAllowed(
              optFileVersion.get().getMimeType(), supportedMimeTypeList))
          ? Try.success(Pair.of(nodeRepository.getNode(nodeId).get(), optFileVersion.get()))
          : Try.failure(new BadRequestException());
    }
    return Try.failure(new NodeNotFoundException(requesterId, nodeId));
  }

  /**
   * Checks if the {@param fileDigest} of the requested blob to preview has changed from the digest
   * specified in the etag {@param httpRequest} header.
   *
   * @param httpRequest is a {@link HttpRequest}.
   * @param fileDigest is a {@link String} representing the digest of the blob to preview.
   * @return true if the {@link HttpHeaderNames#IF_NONE_MATCH} header is not present (it means this
   *     is the first time the preview of this blob is requested) or if the etag specified differs
   *     from the digest of the requested blob. Otherwise, it returns false.
   */
  private boolean isPreviewChanged(HttpRequest httpRequest, String fileDigest) {
    // The Etag header does not handle the comma character. For this reason we must encode
    // the digest in a base64 string.
    String base64Digest = Base64.encodeBase64String(fileDigest.getBytes(StandardCharsets.UTF_8));
    return Optional.ofNullable(httpRequest.headers().getAsString(HttpHeaderNames.IF_NONE_MATCH))
        .filter(etag -> etag.equals(base64Digest))
        .isEmpty();
  }
}
