// SPDX-FileCopyrightText: 2022 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.rest.controllers;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.google.inject.Inject;
import com.zextras.carbonio.files.Constants;
import com.zextras.carbonio.files.Constants.API.Endpoints;
import com.zextras.carbonio.files.Constants.API.Headers;
import com.zextras.carbonio.files.config.FilesConfig;
import com.zextras.carbonio.files.dal.dao.User;
import com.zextras.carbonio.files.dal.dao.ebean.Node;
import com.zextras.carbonio.files.exceptions.FileSizeException;
import com.zextras.carbonio.files.netty.utilities.BufferInputStream;
import com.zextras.carbonio.files.netty.utilities.HttpResponseBuilder;
import com.zextras.carbonio.files.netty.utilities.NettyBufferWriter;
import com.zextras.carbonio.files.rest.services.BlobService;
import com.zextras.carbonio.files.rest.types.BlobResponse;
import com.zextras.carbonio.files.rest.types.UploadVersionResponse;
import com.zextras.carbonio.files.tasks.PrometheusService;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.handler.codec.http.*;
import io.netty.handler.stream.ChunkedStream;
import io.netty.util.AttributeKey;
import org.apache.commons.codec.binary.Base64;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;

@ChannelHandler.Sharable
public class BlobController extends SimpleChannelInboundHandler<HttpObject> {

  private static final Logger logger = LoggerFactory.getLogger(BlobController.class);

  private static final AttributeKey<BufferInputStream> fileStreamReader =
      AttributeKey.valueOf("FileStreamReader");

  private final FilesConfig filesConfig;
  private final BlobService blobService;
  private final PrometheusService prometheusService;

  @Inject
  public BlobController(FilesConfig filesConfig, BlobService blobService, PrometheusService prometheusService) {
    super(true);
    this.filesConfig = filesConfig;
    this.blobService = blobService;
    this.prometheusService = prometheusService;
  }

  @Override
  protected void channelRead0(ChannelHandlerContext context, HttpObject httpObject) {
    try {
      if (httpObject instanceof HttpRequest) {
        HttpRequest httpRequest = (HttpRequest) httpObject;
        String uriRequest = httpRequest.uri();

        Matcher downloadMatcher = Endpoints.DOWNLOAD_FILE.matcher(uriRequest);
        Matcher downloadMultipleMatcher = Endpoints.DOWNLOAD_MULTIPLE.matcher(uriRequest);
        Matcher downloadCheckMatcher = Endpoints.DOWNLOAD_FILE_CHECK.matcher(uriRequest);
        Matcher downloadMultipleCheckMatcher = Endpoints.DOWNLOAD_MULTIPLE_CHECK.matcher(uriRequest);
        Matcher uploadMatcher = Endpoints.UPLOAD_FILE.matcher(uriRequest);
        Matcher uploadInternalMatcher = Endpoints.UPLOAD_FILE_INTERNAL.matcher(uriRequest);
        Matcher uploadVersionMatcher = Endpoints.UPLOAD_FILE_VERSION.matcher(uriRequest);

        if (downloadMultipleCheckMatcher.find()) {
          checkDownloadMultiple(context, httpRequest);
        } else if (downloadMultipleMatcher.find()) {
          downloadMultiple(context, httpRequest);
        }

        if (downloadCheckMatcher.find()) {
          checkDownload(context, httpRequest, downloadCheckMatcher);
        } else if (downloadMatcher.find()) {
          download(context, httpRequest, downloadMatcher);
        }

        if (uploadInternalMatcher.find()) {
          uploadFileInternal(context, httpRequest);
        } else if (uploadMatcher.find()) {
          uploadFile(context, httpRequest);
        }

        if (uploadVersionMatcher.find()) {
          uploadFileVersion(context, httpRequest);
        }

      } else if (httpObject instanceof HttpContent) {

        HttpContent httpContent = (HttpContent) httpObject;
        Optional.ofNullable(context.channel().attr(fileStreamReader).get())
            .ifPresent((buffer -> buffer.addContent(httpContent.content())));

        httpContent.content().clear();

        if (httpObject instanceof LastHttpContent) {
          Optional.ofNullable(context.channel().attr(fileStreamReader).get())
              .ifPresent(BufferInputStream::finishWrite);
        }
        return;
      }

      context.fireChannelRead(new NoSuchElementException());

    } catch (Exception exception) {
      // Catching the RuntimeException and the JsonProcessingException
      context.fireExceptionCaught(exception);
    }
  }

  private void checkDownloadMultiple(ChannelHandlerContext context, HttpRequest request) {
    User requester = (User) context.channel().attr(AttributeKey.valueOf("requester")).get();

    if (!(request instanceof FullHttpRequest fullRequest)) {
      context.fireExceptionCaught(new IllegalArgumentException("Request must be a FullHttpRequest to read body"));
      return;
    }

    ByteBuf content = fullRequest.content();
    content.retain();
    if (content.readableBytes() == 0) {
      context.fireExceptionCaught(new IllegalArgumentException("Request body is empty"));
      return;
    }

    String bodyContent = content.toString(StandardCharsets.UTF_8);
    List<String> nodeIds;

    QueryStringDecoder decoder = new QueryStringDecoder(bodyContent, false);
    Map<String, List<String>> parameters = decoder.parameters();

    List<String> nodeIdsParam = parameters.get(Constants.API.BodyAttributes.NODE_IDS);

    if (nodeIdsParam == null || nodeIdsParam.isEmpty()) {
      context.fireExceptionCaught(new IllegalArgumentException("Missing nodeIds parameter in form data"));
      return;
    }

    String nodeIdsJson = nodeIdsParam.get(0);

    try {
      nodeIds = new ObjectMapper().readValue(nodeIdsJson, new TypeReference<>() {
      });
    } catch (JsonProcessingException exception) {
      context.fireExceptionCaught(new IllegalArgumentException("Can't parse form data. Expected 'nodeIds' field with JSON array."));
      return;
    }

    if (nodeIds == null || nodeIds.isEmpty()) {
      context.fireExceptionCaught(new IllegalArgumentException("nodeIds list cannot be empty"));
      return;
    }

    Optional<List<Node>> optNodes = Optional.ofNullable(blobService
        .checkDownloadMultiple(nodeIds, requester)
        .orElseThrow(() -> new NoSuchElementException(
            String.format("Request %s: nodes %s requested by %s - some nodes do not exist or user lacks permission",
                request.uri(), nodeIds, requester.getId()))));

    ChannelFuture future = context.writeAndFlush(HttpResponseBuilder.createNoContentResponse());
    future.addListener(ChannelFutureListener.CLOSE);
  }

  private void checkDownload(ChannelHandlerContext context, HttpRequest request, Matcher uriMatched) {
    User requester = (User) context.channel().attr(AttributeKey.valueOf("requester")).get();

    String nodeId = uriMatched.group(1);
    Optional<Node> optNode =
        Optional.ofNullable(blobService
            .checkDownloadFileById(nodeId, requester)
            .orElseThrow(
                () ->
                    new NoSuchElementException(
                        String.format(
                            "Request %s: node %s requested by %s does not exist or it does not have"
                                + " the permission to read it",
                            request.uri(), nodeId, requester.getId()))));

    ChannelFuture future = context.writeAndFlush(HttpResponseBuilder.createNoContentResponse());
    future.addListener(ChannelFutureListener.CLOSE);
  }

  private void downloadMultiple(ChannelHandlerContext context, HttpRequest request) {
    User requester = (User) context.channel().attr(AttributeKey.valueOf("requester")).get();

    if (!(request instanceof FullHttpRequest fullRequest)) {
      context.fireExceptionCaught(new IllegalArgumentException("Request must be a FullHttpRequest to read body"));
      return;
    }

    ByteBuf content = fullRequest.content();
    content.retain();
    if (content.readableBytes() == 0) {
      context.fireExceptionCaught(new IllegalArgumentException("Request body is empty"));
      return;
    }

    String bodyContent = content.toString(StandardCharsets.UTF_8);
    List<String> nodeIds;

    QueryStringDecoder decoder = new QueryStringDecoder(bodyContent, false);
    Map<String, List<String>> parameters = decoder.parameters();

    List<String> nodeIdsParam = parameters.get(Constants.API.BodyAttributes.NODE_IDS);

    if (nodeIdsParam == null || nodeIdsParam.isEmpty()) {
      context.fireExceptionCaught(new IllegalArgumentException("Missing nodeIds parameter in form data"));
      return;
    }

    String nodeIdsJson = nodeIdsParam.get(0);

    try {
      nodeIds = new ObjectMapper().readValue(nodeIdsJson, new TypeReference<>() {});
    } catch (JsonProcessingException exception) {
      context.fireExceptionCaught(new IllegalArgumentException("Can't parse form data. Expected 'nodeIds' field with JSON array."));
      return;
    }

    if (nodeIds == null || nodeIds.isEmpty()) {
      context.fireExceptionCaught(new IllegalArgumentException("nodeIds list cannot be empty"));
      return;
    }

    BlobResponse blobResponse = blobService
        .downloadMultiple(nodeIds, requester)
        .orElseThrow(() -> new NoSuchElementException(
            String.format("Request %s: nodes %s requested by %s - some nodes do not exist or user lacks permission",
                request.uri(), nodeIds, requester.getId())));

    context.write(HttpResponseBuilder.createSuccessDownloadHttpResponse(blobResponse));
    writeStreamAsChunked(context, blobResponse.getBlobStream());
  }

  private void download(ChannelHandlerContext context, HttpRequest request, Matcher uriMatched) {
    User requester = (User) context.channel().attr(AttributeKey.valueOf("requester")).get();

    String nodeId = uriMatched.group(1);
    Integer version = Optional.ofNullable(uriMatched.group(2)).map(Integer::parseInt).orElse(null);

    BlobResponse blobResponse =
        blobService
            .downloadFileById(nodeId, version, requester)
            .orElseThrow(
                () ->
                    new NoSuchElementException(
                        String.format(
                            "Request %s: node %s requested by %s does not exist or it does not have"
                                + " the permission to read it",
                            request.uri(), nodeId, requester.getId())));

    context.write(HttpResponseBuilder.createSuccessDownloadHttpResponse(blobResponse));
    // Create netty buffer and start to fill it with the bytes arriving from the blob stream
    new NettyBufferWriter(context).writeStream(blobResponse.getBlobStream(), context.newPromise());
  }

  private void initializeFileStream(ChannelHandlerContext context) {
    context.channel().attr(fileStreamReader).set(new BufferInputStream(context.channel().config()));
  }

  private void uploadFileInternal(ChannelHandlerContext context, HttpRequest httpRequest) {
    String accountId = httpRequest.headers().get(Constants.API.Headers.UPLOAD_ACCOUNT_ID);
    doUploadFile(context, httpRequest, accountId, Optional.empty());
  }

  private void uploadFile(ChannelHandlerContext context, HttpRequest httpRequest) {
    User requester = (User) context.channel().attr(AttributeKey.valueOf("requester")).get();
    doUploadFile(context, httpRequest, requester.getId(), Optional.of(requester));
  }

  private void doUploadFile(ChannelHandlerContext context, HttpRequest httpRequest, String requestedId, Optional<User> requesterEntity) {
    String parentId =
        Optional.ofNullable(httpRequest.headers().getAsString(Constants.API.Headers.UPLOAD_PARENT_ID))
            .orElse(Constants.Db.RootId.LOCAL_ROOT);
    String description =
        Optional.ofNullable(httpRequest.headers().getAsString(Constants.API.Headers.UPLOAD_DESCRIPTION))
            .orElse("");
    long blobLength = Long.parseLong(httpRequest.headers().get(HttpHeaderNames.CONTENT_LENGTH));

    // Check if the file size is within the limit (empty optional limit means no limit)
    double blobLengthInMB = blobLength / (1024.0 * 1024.0);
    Optional<Integer> maxFileSize = filesConfig.getMaxUploadableFileSizeInMb();
    logger.info("File size: {}", blobLengthInMB);
    logger.info("Max file size: {}", maxFileSize);
    if (maxFileSize.isPresent() && blobLengthInMB > maxFileSize.get()) {
      context.fireExceptionCaught(new FileSizeException("File size exceeds the maximum allowed of " + maxFileSize.get() + "MB"));
      return;
    }

    String encodedFilename = httpRequest.headers().getAsString(Constants.API.Headers.UPLOAD_FILENAME);
    String decodedFilename =
        encodedFilename == null || !Base64.isBase64(encodedFilename)
            ? null
            : new String(Base64.decodeBase64(encodedFilename));

    if (decodedFilename == null
        || decodedFilename.trim().isEmpty()
        || decodedFilename.trim().length() > 1024) {
      context.fireExceptionCaught(new IllegalArgumentException());
      return;
    }

    initializeFileStream(context);

    CompletableFuture.runAsync(
            () -> {
              String nodeId =
                  blobService
                      .uploadFile(
                          requestedId,
                          requesterEntity,
                          context.channel().attr(fileStreamReader).get(),
                          blobLength,
                          parentId,
                          decodedFilename,
                          description)
                      .orElseThrow(NoSuchElementException::new);

              sendSuccessUploadResponse(context, nodeId, 1);

              prometheusService.getUploadCounter().increment();
              context.flush().close();
            })
        .exceptionally(
            throwable -> { // It is necessary because CompletableFuture eats exceptions
              context.fireExceptionCaught(throwable);
              return null;
            });
  }

  public void uploadFileVersion(ChannelHandlerContext context, HttpRequest httpRequest) {

    String nodeId = httpRequest.headers().getAsString(Headers.UPLOAD_NODE_ID);
    String encodedFilename = httpRequest.headers().getAsString(Constants.API.Headers.UPLOAD_FILENAME);
    String decodedFilename =
        encodedFilename == null || !Base64.isBase64(encodedFilename)
            ? null
            : new String(Base64.decodeBase64(encodedFilename));

    if (nodeId == null
        || decodedFilename == null
        || decodedFilename.trim().isEmpty()
        || decodedFilename.trim().length() > 1024) {
      context.fireExceptionCaught(new IllegalArgumentException());
      return;
    }

    User requester = (User) context.channel().attr(AttributeKey.valueOf("requester")).get();
    boolean overwrite =
        Boolean.parseBoolean(httpRequest.headers().getAsString(Headers.UPLOAD_OVERWRITE_VERSION));
    long blobLength = Long.parseLong(httpRequest.headers().get(HttpHeaderNames.CONTENT_LENGTH));

    // Check if the file size is within the limit (empty optional limit means no limit)
    double blobLengthInMB = blobLength / (1024.0 * 1024.0);
    Optional<Integer> maxFileSize = filesConfig.getMaxUploadableFileSizeInMb();
    logger.info("File size: {}", blobLengthInMB);
    logger.info("Max file size: {}", maxFileSize);
    if (maxFileSize.isPresent() && blobLengthInMB > maxFileSize.get()) {
      context.fireExceptionCaught(new FileSizeException("File size exceeds the maximum allowed of " + maxFileSize.get() + "MB"));
      return;
    }

    logger.debug("Uploading new version of node with id: {}, overwrite: {}", nodeId, overwrite);

    initializeFileStream(context);

    CompletableFuture.runAsync(
            () -> {
              Integer version =
                  blobService
                      .uploadFileVersion(
                          requester,
                          context.channel().attr(fileStreamReader).get(),
                          blobLength,
                          nodeId,
                          decodedFilename,
                          overwrite)
                      .orElseThrow(NoSuchElementException::new);

              sendSuccessUploadResponse(context, nodeId, version);

              prometheusService.getUploadVersionCounter().increment();
              context.flush().close();
            })
        .exceptionally(
            throwable -> { // It is necessary because CompletableFuture eats exceptions
              context.fireExceptionCaught(throwable);
              return null;
            });
  }

  private void sendSuccessUploadResponse(
      ChannelHandlerContext context, String nodeId, int version) {

    UploadVersionResponse response = new UploadVersionResponse();
    response.setNodeId(nodeId);
    response.setVersion(version);

    byte[] jsonByteArray;
    try {
      jsonByteArray =
          new ObjectMapper()
              .configure(SerializationFeature.FAIL_ON_EMPTY_BEANS, false)
              .writeValueAsBytes(response);
    } catch (JsonProcessingException exception) {
      context.fireExceptionCaught(exception);
      return;
    }

    HttpHeaders headers = new DefaultHttpHeaders(true);
    headers.add(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
    headers.add(HttpHeaderNames.CONTENT_TYPE, HttpHeaderValues.APPLICATION_JSON);

    context.write(
        new DefaultFullHttpResponse(
            HttpVersion.HTTP_1_0,
            HttpResponseStatus.OK,
            Unpooled.wrappedBuffer(jsonByteArray),
            headers,
            new DefaultHttpHeaders()));
  }

  private void writeStreamAsChunked(ChannelHandlerContext context, InputStream inputStream) {
    context.write(new ChunkedStream(inputStream));
    ChannelFuture lastContentFuture = context.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT);
    lastContentFuture.addListener(new ChannelFutureListener() {
      @Override
      public void operationComplete(ChannelFuture future) {
        if (future.isSuccess()) {
          logger.debug("ZIP stream sent successfully");
        } else {
          logger.error("Error sending ZIP stream", future.cause());
        }

        try {
          inputStream.close();
        } catch (IOException e) {
          logger.error("Error closing input stream", e);
        }

        if (!"keep-alive".equals(context.channel().attr(AttributeKey.valueOf("connection")).get())) {
          context.close();
        }
      }
    });
  }
}
