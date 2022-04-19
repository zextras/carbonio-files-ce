// SPDX-FileCopyrightText: 2022 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.rest.controllers;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.inject.Inject;
import com.zextras.carbonio.files.Files.API.ContextAttribute;
import com.zextras.carbonio.files.Files.API.Endpoints;
import com.zextras.carbonio.files.dal.dao.User;
import com.zextras.carbonio.files.dal.dao.ebean.Node;
import com.zextras.carbonio.files.netty.utilities.NettyBufferWriter;
import com.zextras.carbonio.files.rest.services.PreviewService;
import com.zextras.carbonio.files.rest.types.BlobResponse;
import com.zextras.carbonio.files.rest.types.PreviewQueryParameters;
import com.zextras.carbonio.usermanagement.exceptions.BadRequest;
import com.zextras.carbonio.usermanagement.exceptions.InternalServerError;
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
import java.util.Set;
import java.util.regex.Matcher;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@ChannelHandler.Sharable
public class PreviewController extends SimpleChannelInboundHandler<HttpRequest> {

  private static final Logger logger = LoggerFactory.getLogger(PreviewController.class);

  private final PreviewService previewService;
  private       Matcher        previewImage;
  private       Matcher        thumbnailImage;
  private       Matcher        previewPdf;
  private       Matcher        thumbnailPdf;
  private       Matcher        previewDocument;
  private       Matcher        thumbnailDocument;

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
  public PreviewController(PreviewService previewService) {
    super(true);
    this.previewService = previewService;
  }


  @Override
  protected void channelRead0(
    ChannelHandlerContext context,
    HttpRequest httpRequest
  ) {
    try {
      String uriRequest = httpRequest.uri();
      previewImage = Endpoints.PREVIEW_IMAGE.matcher(uriRequest);
      thumbnailImage = Endpoints.THUMBNAIL_IMAGE.matcher((uriRequest));
      previewPdf = Endpoints.PREVIEW_PDF.matcher((uriRequest));
      thumbnailPdf = Endpoints.THUMBNAIL_PDF.matcher((uriRequest));
      previewDocument = Endpoints.PREVIEW_DOCUMENT.matcher((uriRequest));
      thumbnailDocument = Endpoints.THUMBNAIL_DOCUMENT.matcher((uriRequest));

      User requester = (User) context
        .channel()
        .attr(AttributeKey.valueOf(ContextAttribute.REQUESTER))
        .get();

      if (thumbnailImage.find() && httpRequest.method().equals(HttpMethod.GET)) {
        thumbnailImage(context, httpRequest, requester);
        return;
      }

      if (thumbnailPdf.find() && httpRequest.method().equals(HttpMethod.GET)) {
        thumbnailPdf(context, httpRequest, requester);
        return;
      }

      if (thumbnailDocument.find() && httpRequest.method().equals(HttpMethod.GET)) {
        thumbnailDocument(context, httpRequest, requester);
        return;
      }

      if (previewImage.find() && httpRequest.method().equals(HttpMethod.GET)) {
        previewImage(context, httpRequest, requester);
        return;
      }

      if (previewPdf.find() && httpRequest.method().equals(HttpMethod.GET)) {
        previewPdf(context, httpRequest, requester);
        return;
      }

      if (previewDocument.find() && httpRequest.method().equals(HttpMethod.GET)) {
        previewDocument(context, httpRequest, requester);
        return;
      }

      logger.warn(MessageFormat.format(
        "Request {0} {1}: bad request",
        httpRequest.method(),
        httpRequest.uri()
      ));

      context.fireExceptionCaught(new BadRequest());

    } catch (IllegalArgumentException exception) {
      logger.warn(MessageFormat.format(
        "Request {0}: Illegal arguments in the request:\n{1}",
        httpRequest.uri(),
        exception.getMessage()
      ));
      context.fireExceptionCaught(new BadRequest());

    } catch (Exception exception) {
      logger.error(MessageFormat.format(
        "Unexpected exception of type: {0} with message: {1}",
        exception.getClass(),
        exception.getMessage()
      ));
      context.fireExceptionCaught(
        new com.zextras.carbonio.preview.exceptions.InternalServerError());
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
    User requester
  ) {

    String nodeId = previewImage.group(1);
    String nodeVersion = previewImage.group(2);
    String previewArea = previewImage.group(3);

    PreviewQueryParameters queryParameters = parseQueryParameters(previewImage.group(5));

    Try<Node> tryCheckNode = previewService.checkNodePermissionAndExistence(
      requester.getUuid(),
      nodeId,
      Integer.parseInt(nodeVersion),
      Collections.singleton("image/")
    );

    if (tryCheckNode.isSuccess()) {
      previewService
        .getPreviewOfImage(
          tryCheckNode.get().getId(),
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
    User requester
  ) {

    String nodeId = thumbnailImage.group(1);
    String nodeVersion = thumbnailImage.group(2);
    String previewArea = thumbnailImage.group(3);

    PreviewQueryParameters queryParameters = parseQueryParameters(thumbnailImage.group(4));

    Try<Node> tryCheckNode = previewService.checkNodePermissionAndExistence(
      requester.getUuid(),
      nodeId,
      Integer.parseInt(nodeVersion),
      Collections.singleton("image/")
    );

    if (tryCheckNode.isSuccess()) {

      previewService
        .getThumbnailOfImage(
          tryCheckNode.get().getId(),
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
    User requester
  ) {

    String nodeId = previewPdf.group(1);
    String nodeVersion = previewPdf.group(2);

    PreviewQueryParameters queryParameters = parseQueryParameters(previewPdf.group(4));

    Try<Node> tryCheckNode = previewService.checkNodePermissionAndExistence(
      requester.getUuid(),
      nodeId,
      Integer.parseInt(nodeVersion),
      Collections.singleton("application/pdf")
    );

    if (tryCheckNode.isSuccess()) {

      previewService
        .getPreviewOfPdf(tryCheckNode.get().getId(), nodeId, Integer.parseInt(nodeVersion),
          queryParameters)
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
    User requester
  ) {

    String nodeId = thumbnailPdf.group(1);
    String nodeVersion = thumbnailPdf.group(2);
    String area = thumbnailPdf.group(3);

    PreviewQueryParameters queryParameters = parseQueryParameters(thumbnailPdf.group(4));

    Try<Node> tryCheckNode = previewService.checkNodePermissionAndExistence(
      requester.getUuid(),
      nodeId,
      Integer.parseInt(nodeVersion),
      Collections.singleton("application/pdf")
    );

    if (tryCheckNode.isSuccess()) {

      previewService
        .getThumbnailOfPdf(
          tryCheckNode.get().getId(),
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
    User requester
  ) {

    String nodeId = previewDocument.group(1);
    String nodeVersion = previewDocument.group(2);

    PreviewQueryParameters queryParameters = parseQueryParameters(previewDocument.group(4));

    Try<Node> tryCheckNode = previewService.checkNodePermissionAndExistence(
      requester.getUuid(),
      nodeId,
      Integer.parseInt(nodeVersion),
      documentAllowedTypes
    );

    if (tryCheckNode.isSuccess()) {

      previewService
        .getPreviewOfDocument(tryCheckNode.get().getId(), nodeId, Integer.parseInt(nodeVersion),
          queryParameters)
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
    User requester
  ) {

    String nodeId = thumbnailDocument.group(1);
    String nodeVersion = thumbnailDocument.group(2);
    String area = thumbnailDocument.group(3);

    PreviewQueryParameters queryParameters = parseQueryParameters(thumbnailDocument.group(4));

    Try<Node> tryCheckNode = previewService.checkNodePermissionAndExistence(
      requester.getUuid(),
      nodeId,
      Integer.parseInt(nodeVersion),
      documentAllowedTypes
    );

    if (tryCheckNode.isSuccess()) {

      previewService
        .getThumbnailOfDocument(
          tryCheckNode.get().getId(),
          nodeId,
          Integer.parseInt(nodeVersion),
          area,
          queryParameters
        )
        .onSuccess(blobResponse -> successResponse(context, httpRequest, blobResponse))
        .onFailure(failure -> failureResponse(context, httpRequest, failure));
    } else {
      logger.warn(MessageFormat.format(
        "Check node permission and existence operation failed, {0}",
        tryCheckNode.failed().get()
      ));
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
        "Exception {0} encountered while sending success response: {1}",
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
    logger.error(failure.getMessage());

    HttpResponseStatus statusCode = (failure instanceof InternalServerError)
      ? HttpResponseStatus.INTERNAL_SERVER_ERROR
      : HttpResponseStatus.BAD_REQUEST;

    context
      .writeAndFlush(new DefaultFullHttpResponse(httpRequest.protocolVersion(), statusCode))
      .addListener(ChannelFutureListener.CLOSE);
  }

}
