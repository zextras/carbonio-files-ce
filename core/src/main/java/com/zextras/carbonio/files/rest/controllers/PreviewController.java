// SPDX-FileCopyrightText: 2022 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.rest.controllers;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.inject.Inject;
import com.zextras.carbonio.files.Files.API.ContextAttribute;
import com.zextras.carbonio.files.Files.API.Endpoints;
import com.zextras.carbonio.files.dal.dao.User;
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
import io.netty.handler.codec.http.DefaultFullHttpResponse;
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
import java.text.MessageFormat;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@ChannelHandler.Sharable
public class PreviewController extends SimpleChannelInboundHandler<HttpRequest> {

  private static final Logger logger = LoggerFactory.getLogger(PreviewController.class);

  private final PreviewService        previewService;
  private final PermissionsChecker    permissionsChecker;
  private final FileVersionRepository fileVersionRepository;
  private final MimeTypeUtils         mimeTypeUtils;
  private final NodeRepository        nodeRepository;

  private final Set<String> documentAllowedTypes = Stream.of(
    "application/vnd.ms-excel",
    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
    "application/vnd.oasis.opendocument.spreadsheet",
    "application/vnd.ms-powerpoint",
    "application/vnd.openxmlformats-officedocument.presentationml.presentation",
    "application/vnd.oasis.opendocument.presentation",
    "application/msword",
    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
    "application/vnd.oasis.opendocument.text"
  ).collect(Collectors.toUnmodifiableSet());

  @Inject
  public PreviewController(
    PreviewService previewService,
    PermissionsChecker permissionsChecker,
    NodeRepository nodeRepository,
    FileVersionRepository fileVersionRepository,
    MimeTypeUtils mimeTypeUtils
  ) {
    super(true);
    this.previewService = previewService;
    this.permissionsChecker = permissionsChecker;
    this.nodeRepository = nodeRepository;
    this.fileVersionRepository = fileVersionRepository;
    this.mimeTypeUtils = mimeTypeUtils;
  }

  @Override
  protected void channelRead0(
    ChannelHandlerContext context,
    HttpRequest httpRequest
  ) {
    try {
      String uriRequest = httpRequest.uri();
      Matcher previewImageMatcher = Endpoints.PREVIEW_IMAGE.matcher(uriRequest);
      Matcher thumbnailImageMatcher = Endpoints.THUMBNAIL_IMAGE.matcher((uriRequest));
      Matcher previewPdfMatcher = Endpoints.PREVIEW_PDF.matcher((uriRequest));
      Matcher thumbnailPdfMatcher = Endpoints.THUMBNAIL_PDF.matcher((uriRequest));
      Matcher previewDocumentMatcher = Endpoints.PREVIEW_DOCUMENT.matcher((uriRequest));
      Matcher thumbnailDocumentMatcher = Endpoints.THUMBNAIL_DOCUMENT.matcher((uriRequest));

      User requester = (User) context
        .channel()
        .attr(AttributeKey.valueOf(ContextAttribute.REQUESTER))
        .get();

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

      logger.warn(MessageFormat.format(
        "Request {0} {1}: bad request",
        httpRequest.method(),
        httpRequest.uri()
      ));

      context.fireExceptionCaught(new BadRequestException());

    } catch (IllegalArgumentException exception) {
      logger.warn(MessageFormat.format(
        "Request {0}: Illegal arguments in the request:\n{1}",
        httpRequest.uri(),
        exception.getMessage()
      ));
      context.fireExceptionCaught(new BadRequestException());

    } catch (Exception exception) {
      logger.error(MessageFormat.format(
        "Request {0}: Unexpected exception of type: {1} with message: {2}",
        httpRequest.uri(),
        exception.getClass(),
        exception.getMessage()
      ));
      context.fireExceptionCaught(
        new InternalServerErrorException(exception.getMessage())
      );
    }
  }

  /**
   * <p>This method handles extraction of metadata from uri and fetching of image's preview.
   * The result is sent through the netty channel
   *
   * @param context is a {@link ChannelHandlerContext} object in which to write the results.
   * @param httpRequest is a {@link HttpRequest}.
   * @param requester is a {@link User}, to check if the requester has the permission to view file.
   */
  private void previewImage(
    ChannelHandlerContext context,
    HttpRequest httpRequest,
    Matcher uriMatched,
    User requester
  ) {

    String nodeId = uriMatched.group(1);
    String nodeVersion = uriMatched.group(2);
    String previewArea = uriMatched.group(3);

    PreviewQueryParameters queryParameters = parseQueryParameters(uriMatched.group(5));

    Try<Node> tryCheckNode = checkNodePermissionAndExistence(
      requester.getUuid(),
      nodeId,
      Integer.parseInt(nodeVersion),
      Collections.singleton("image/")
    );

    if (tryCheckNode.isSuccess()) {

      previewService
        .getPreviewOfImage(
          tryCheckNode.get().getOwnerId(),
          nodeId,
          Integer.parseInt(nodeVersion),
          previewArea,
          queryParameters
        )
        .onSuccess(blobResponse -> successResponse(context, httpRequest, blobResponse))
        .onFailure(failure -> failureResponse(context, httpRequest, failure));
    } else {
      failureResponse(context, httpRequest, tryCheckNode.failed().get());
    }
  }

  /**
   * <p>This method handles extraction of metadata from uri and fetching of image's thumbnail.
   * The result is sent through the netty channel
   *
   * @param context is a {@link ChannelHandlerContext} object in which to write the results.
   * @param httpRequest is a {@link HttpRequest}.
   * @param requester is a {@link User}, to check if the requester has the permission to view file.
   */
  private void thumbnailImage(
    ChannelHandlerContext context,
    HttpRequest httpRequest,
    Matcher uriMatched,
    User requester
  ) {
    String nodeId = uriMatched.group(1);
    String nodeVersion = uriMatched.group(2);
    String previewArea = uriMatched.group(3);

    PreviewQueryParameters queryParameters = parseQueryParameters(uriMatched.group(4));

    Try<Node> tryCheckNode = checkNodePermissionAndExistence(
      requester.getUuid(),
      nodeId,
      Integer.parseInt(nodeVersion),
      Collections.singleton("image/")
    );

    if (tryCheckNode.isSuccess()) {

      previewService
        .getThumbnailOfImage(
          tryCheckNode.get().getOwnerId(),
          nodeId,
          Integer.parseInt(nodeVersion),
          previewArea,
          queryParameters
        )
        .onSuccess(blobResponse -> successResponse(context, httpRequest, blobResponse))
        .onFailure(failure -> failureResponse(context, httpRequest, failure));
    } else {
      failureResponse(context, httpRequest, tryCheckNode.failed().get());
    }
  }

  /**
   * <p>This method handles extraction of metadata from uri and fetching of pdf's preview.
   * The result is sent through the netty channel
   *
   * @param context is a {@link ChannelHandlerContext} object in which to write the results.
   * @param httpRequest is a {@link HttpRequest}.
   * @param requester is a {@link User}, to check if the requester has the permission to view file.
   */
  private void previewPdf(
    ChannelHandlerContext context,
    HttpRequest httpRequest,
    Matcher uriMatched,
    User requester
  ) {

    String nodeId = uriMatched.group(1);
    String nodeVersion = uriMatched.group(2);

    PreviewQueryParameters queryParameters = parseQueryParameters(uriMatched.group(4));

    Try<Node> tryCheckNode = checkNodePermissionAndExistence(
      requester.getUuid(),
      nodeId,
      Integer.parseInt(nodeVersion),
      Collections.singleton("application/pdf")
    );

    if (tryCheckNode.isSuccess()) {

      previewService
        .getPreviewOfPdf(
          tryCheckNode.get().getOwnerId(),
          nodeId,
          Integer.parseInt(nodeVersion),
          queryParameters
        )
        .onSuccess(blobResponse -> successResponse(context, httpRequest, blobResponse))
        .onFailure(failure -> failureResponse(context, httpRequest, failure));
    } else {
      failureResponse(context, httpRequest, tryCheckNode.failed().get());
    }
  }

  /**
   * <p>This method handles extraction of metadata from uri and fetching of pdf's thumbnail.
   * The result is sent through the netty channel
   *
   * @param context is a {@link ChannelHandlerContext} object in which to write the results.
   * @param httpRequest is a {@link HttpRequest}.
   * @param requester is a {@link User}, to check if the requester has the permission to view file.
   */
  private void thumbnailPdf(
    ChannelHandlerContext context,
    HttpRequest httpRequest,
    Matcher uriMatched,
    User requester
  ) {

    String nodeId = uriMatched.group(1);
    String nodeVersion = uriMatched.group(2);
    String area = uriMatched.group(3);

    PreviewQueryParameters queryParameters = parseQueryParameters(uriMatched.group(4));

    Try<Node> tryCheckNode = checkNodePermissionAndExistence(
      requester.getUuid(),
      nodeId,
      Integer.parseInt(nodeVersion),
      Collections.singleton("application/pdf")
    );

    if (tryCheckNode.isSuccess()) {

      previewService
        .getThumbnailOfPdf(
          tryCheckNode.get().getOwnerId(),
          nodeId,
          Integer.parseInt(nodeVersion),
          area,
          queryParameters
        )
        .onSuccess(blobResponse -> successResponse(context, httpRequest, blobResponse))
        .onFailure(failure -> failureResponse(context, httpRequest, failure));
    } else {
      failureResponse(context, httpRequest, tryCheckNode.failed().get());
    }
  }


  /**
   * <p>This method handles extraction of metadata from uri and fetching of document's preview.
   * The result is sent through the netty channel
   *
   * @param context is a {@link ChannelHandlerContext} object in which to write the results.
   * @param httpRequest is a {@link HttpRequest}.
   * @param requester is a {@link User}, to check if the requester has the permission to view file.
   */
  private void previewDocument(
    ChannelHandlerContext context,
    HttpRequest httpRequest,
    Matcher uriMatched,
    User requester
  ) {

    String nodeId = uriMatched.group(1);
    String nodeVersion = uriMatched.group(2);

    PreviewQueryParameters queryParameters = parseQueryParameters(uriMatched.group(4));

    Try<Node> tryCheckNode = checkNodePermissionAndExistence(
      requester.getUuid(),
      nodeId,
      Integer.parseInt(nodeVersion),
      documentAllowedTypes
    );

    if (tryCheckNode.isSuccess()) {

      previewService
        .getPreviewOfDocument(
          tryCheckNode.get().getOwnerId(),
          nodeId,
          Integer.parseInt(nodeVersion),
          queryParameters
        )
        .onSuccess(blobResponse -> successResponse(context, httpRequest, blobResponse))
        .onFailure(failure -> failureResponse(context, httpRequest, failure));
    } else {
      failureResponse(context, httpRequest, tryCheckNode.failed().get());
    }
  }


  /**
   * <p>This method handles extraction of metadata from uri and fetching of document's thumbnail.
   * The result is sent through the netty channel
   *
   * @param context is a {@link ChannelHandlerContext} object in which to write the results.
   * @param httpRequest is a {@link HttpRequest}.
   * @param requester is a {@link User}, to check if the requester has the permission to view file.
   */
  private void thumbnailDocument(
    ChannelHandlerContext context,
    HttpRequest httpRequest,
    Matcher uriMatched,
    User requester
  ) {

    String nodeId = uriMatched.group(1);
    String nodeVersion = uriMatched.group(2);
    String area = uriMatched.group(3);

    PreviewQueryParameters queryParameters = parseQueryParameters(uriMatched.group(4));

    Try<Node> tryCheckNode = checkNodePermissionAndExistence(
      requester.getUuid(),
      nodeId,
      Integer.parseInt(nodeVersion),
      documentAllowedTypes
    );

    if (tryCheckNode.isSuccess()) {

      previewService
        .getThumbnailOfDocument(
          tryCheckNode.get().getOwnerId(),
          nodeId,
          Integer.parseInt(nodeVersion),
          area,
          queryParameters
        )
        .onSuccess(blobResponse -> successResponse(context, httpRequest, blobResponse))
        .onFailure(failure -> failureResponse(context, httpRequest, failure));
    } else {
      failureResponse(context, httpRequest, tryCheckNode.failed().get());
    }
  }

  /**
   * <p>This method parses the given string and maps the extracted values
   * to PreviewQueryParameters class corresponding fields
   *
   * @param queryParameters is a {@link String} containing all the query parameters.
   *
   * @return PreviewQueryParameters instance containing parsed data
   */
  private PreviewQueryParameters parseQueryParameters(String queryParameters) {

    Map<String, String> parameters = Arrays
      .stream(queryParameters.replace("?", "").split("&"))
      .map(parameter -> parameter.split("="))
      .filter(parameter -> parameter.length == 2)
      .collect(Collectors.toMap(
        parameter -> parameter[0],
        parameter -> parameter[1]
      ));

    return new ObjectMapper().convertValue(parameters, PreviewQueryParameters.class);
  }

  /**
   * <p>This method writes to the netty channel a successful response with metadata found in
   * blobResponse
   *
   * @param context is a {@link ChannelHandlerContext} object in which to write the results.
   * @param httpRequest is a {@link HttpRequest}.
   * @param blobResponse is a {@link BlobResponse} containing the metadata to write to context.
   */
  private void successResponse(
    ChannelHandlerContext context,
    HttpRequest httpRequest,
    BlobResponse blobResponse
  ) {
    DefaultHttpHeaders headers = new DefaultHttpHeaders(true);
    headers.add(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
    headers.add(HttpHeaderNames.CONTENT_LENGTH, blobResponse.getSize());
    headers.add(HttpHeaderNames.CONTENT_TYPE, blobResponse.getMimeType());

    try {
      headers.add(
        HttpHeaderNames.CONTENT_DISPOSITION,
        "attachment; filename*=UTF-8''" + URLEncoder.encode(blobResponse.getFilename(),
          StandardCharsets.UTF_8)
      );
    } catch (Exception e) {
      logger.error(MessageFormat.format(
        "Request {0}: Exception of type: {0} encountered while sending success response: {1}",
        e.getClass(),
        e.getMessage()
      ));
      e.printStackTrace();
    }

    context.write(new DefaultHttpResponse(
        httpRequest.protocolVersion(),
        HttpResponseStatus.OK,
        headers
      )
    );

    // Writing input stream into the netty channel
    new NettyBufferWriter(context).writeStream(blobResponse.getBlobStream(), context.newPromise());
  }

  /**
   * <p>This method writes to the netty channel a failure response
   *
   * @param context is a {@link ChannelHandlerContext} object in which to write the results.
   * @param httpRequest is a {@link HttpRequest}.
   * @param failure is a {@link Throwable} containing the cause of the failure.
   */
  private void failureResponse(
    ChannelHandlerContext context,
    HttpRequest httpRequest,
    Throwable failure
  ) {
    logger.warn(MessageFormat.format(
      "Request {0}: Failed with message {1}",
      httpRequest.uri(),
      failure.getMessage()
    ));

    HttpResponseStatus statusCode = (failure instanceof BadRequestException)
      ? HttpResponseStatus.BAD_REQUEST
      : HttpResponseStatus.NOT_FOUND;

    context
      .writeAndFlush(new DefaultFullHttpResponse(httpRequest.protocolVersion(), statusCode))
      .addListener(ChannelFutureListener.CLOSE);
  }


  /**
   * <p>This checks if the requested node can be accessed by the requester
   * and if the node mimetype is supported by the system.
   *
   * @param requesterId is a {@link String} representing the id of the requester.
   * @param nodeId is a {@link String } representing the nodeId of the node.
   * @param version is a <code> integer </code> representing the version of the node.
   * @param supportedMimeTypeList is a {@link Set} representing the allowed list or instance of
   * mimetype that the calling methods allow (for instance a method may want only mimetype that are
   * of "image" so "image/something" while another method "application" so "application/something"
   *
   * @return a {@link Try} containing the checked @{link Node} or, on failure, the specific error
   */
  private Try<Node> checkNodePermissionAndExistence(
    String requesterId,
    String nodeId,
    int version,
    Set<String> supportedMimeTypeList
  ) {
    Optional<FileVersion> optFileVersion = fileVersionRepository.getFileVersion(nodeId, version);
    if (permissionsChecker.getPermissions(nodeId, requesterId).has(SharePermission.READ_ONLY)
      && optFileVersion.isPresent()
    ) {
      return (mimeTypeUtils.isMimeTypeAllowed(
        optFileVersion.get().getMimeType(),
        supportedMimeTypeList)
      )
        ? Try.success(nodeRepository.getNode(nodeId).get())
        : Try.failure(new BadRequestException());
    }
    return Try.failure(new NodeNotFoundException(requesterId, nodeId));
  }
}
