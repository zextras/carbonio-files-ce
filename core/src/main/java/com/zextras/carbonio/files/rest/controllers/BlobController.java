// SPDX-FileCopyrightText: 2022 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.rest.controllers;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.google.inject.Inject;
import com.zextras.carbonio.files.Files;
import com.zextras.carbonio.files.Files.API.Endpoints;
import com.zextras.carbonio.files.Files.API.Headers;
import com.zextras.carbonio.files.dal.dao.User;
import com.zextras.carbonio.files.netty.utilities.BufferInputStream;
import com.zextras.carbonio.files.netty.utilities.NettyBufferWriter;
import com.zextras.carbonio.files.rest.services.BlobService;
import com.zextras.carbonio.files.rest.types.BlobResponse;
import com.zextras.carbonio.files.rest.types.UploadVersionResponse;
import com.zextras.carbonio.files.tasks.PrometheusService;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.util.AttributeKey;
import io.vavr.control.Try;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.MessageFormat;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import org.apache.commons.codec.binary.Base64;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@ChannelHandler.Sharable
public class BlobController extends SimpleChannelInboundHandler<HttpObject> {

  private static final Logger logger = LoggerFactory.getLogger(BlobController.class);

  private static final AttributeKey<BufferInputStream> fileStreamReader =
    AttributeKey.valueOf("FileStreamReader");

  private final BlobService blobService;
  private final PrometheusService prometheusService;

  @Inject
  public BlobController(
    BlobService blobService,
    PrometheusService prometheusService
  ) {
    super(true);
    this.blobService = blobService;
    this.prometheusService = prometheusService;
  }

  @Override
  protected void channelRead0(
    ChannelHandlerContext context,
    HttpObject httpObject
  ) {
    try {
      if (httpObject instanceof HttpRequest) {
        HttpRequest httpRequest = (HttpRequest) httpObject;
        String uriRequest = httpRequest.uri();

        Matcher downloadMatcher = Endpoints.DOWNLOAD_FILE.matcher(uriRequest);
        Matcher uploadMatcher = Endpoints.UPLOAD_FILE.matcher(uriRequest);
        Matcher uploadVersionMatcher = Endpoints.UPLOAD_FILE_VERSION.matcher(uriRequest);
        Matcher publicLinkMatcher = Endpoints.PUBLIC_LINK.matcher(uriRequest);

        if (downloadMatcher.find()) {
          download(context, httpRequest, downloadMatcher);
        }

        if (publicLinkMatcher.find()) {
          downloadByLink(context, httpRequest, publicLinkMatcher);
        }

        if (uploadMatcher.find()) {
          uploadFile(context, httpRequest);
        }

        if (uploadVersionMatcher.find()) {
          uploadFileVersion(context, httpRequest);
        }

      } else if (httpObject instanceof HttpContent) {

        HttpContent httpContent = (HttpContent) httpObject;
        Optional.ofNullable(
          context
            .channel()
            .attr(fileStreamReader)
            .get()
        ).ifPresent((buffer -> buffer.addContent(httpContent.content())));

        httpContent.content().clear();

        if (httpObject instanceof LastHttpContent) {
          Optional.ofNullable(
            context
              .channel()
              .attr(fileStreamReader)
              .get()
          ).ifPresent(BufferInputStream::finishWrite);
        }
        return;
      }

      context.fireChannelRead(new NoSuchElementException());

    } catch (Exception exception) {
      // Catching the RuntimeException and the JsonProcessingException
      context.fireExceptionCaught(exception);
    }
  }

  private void downloadByLink(
    ChannelHandlerContext context,
    HttpRequest httpRequest,
    Matcher uriMatched
  ) {
    String publicLinkId = uriMatched.group(1);
    BlobResponse blobResponse = blobService
      .downloadFileByLink(publicLinkId)
      .orElseThrow(() -> new NoSuchElementException(
        String.format(
          "Request %s: the link %s and/or the node associated to it does not exist",
          httpRequest.uri(),
          publicLinkId
        ))
      );

    sendSuccessDownloadResponse(context, blobResponse);
  }

  private void download(
    ChannelHandlerContext context,
    HttpRequest request,
    Matcher uriMatched
  ) {
    User requester = (User) context.channel().attr(AttributeKey.valueOf("requester")).get();

    String nodeId = uriMatched.group(1);
    Integer version = Optional
      .ofNullable(uriMatched.group(2))
      .map(Integer::parseInt)
      .orElse(null);

    BlobResponse blobResponse = blobService
      .downloadFileById(nodeId, version, requester)
      .orElseThrow(() -> new NoSuchElementException(String.format(
        "Request %s: node %s requested by %s does not exist or it does not have the permission to read it",
        request.uri(),
        nodeId,
        requester.getId()
      )));

    sendSuccessDownloadResponse(context, blobResponse);
  }

  private void initializeFileStream(ChannelHandlerContext context) {
    context
      .channel()
      .attr(fileStreamReader)
      .set(new BufferInputStream(context.channel().config()));
  }

  private void uploadFile(
    ChannelHandlerContext context,
    HttpRequest httpRequest
  ) {
    User requester = (User) context.channel().attr(AttributeKey.valueOf("requester")).get();

    String parentId = Optional
      .ofNullable(httpRequest.headers().getAsString(Files.API.Headers.UPLOAD_PARENT_ID))
      .orElse(Files.Db.RootId.LOCAL_ROOT);
    String description = Optional
      .ofNullable(httpRequest.headers().getAsString(Files.API.Headers.UPLOAD_DESCRIPTION))
      .orElse("");
    long blobLength = Long.parseLong(httpRequest.headers().get(HttpHeaderNames.CONTENT_LENGTH));
    String encodedFilename = httpRequest.headers().getAsString(Files.API.Headers.UPLOAD_FILENAME);
    String decodedFilename =
      encodedFilename == null || !Base64.isBase64(encodedFilename)
        ? null
        : new String(Base64.decodeBase64(encodedFilename));

    if (decodedFilename == null
      || decodedFilename.trim().isEmpty()
      || decodedFilename.trim().length() > 1024
    ) {
      context.fireExceptionCaught(new IllegalArgumentException());
      return;
    }

    initializeFileStream(context);

    CompletableFuture.runAsync(
      () -> {
        String nodeId = blobService.uploadFile(
          requester,
          context.channel().attr(fileStreamReader).get(),
          blobLength,
          parentId,
          decodedFilename,
          description
        ).orElseThrow(NoSuchElementException::new);

        sendSuccessUploadResponse(context, nodeId, 1);

        prometheusService.getUploadCounter().increment();
        context.flush().close();
      }
    ).exceptionally(throwable -> { // It is necessary because CompletableFuture eats exceptions
      context.fireExceptionCaught(throwable);
      return null;
    });
  }

  public void uploadFileVersion(
    ChannelHandlerContext context,
    HttpRequest httpRequest
  ) {

    String nodeId = httpRequest.headers().getAsString(Headers.UPLOAD_NODE_ID);
    String encodedFilename = httpRequest.headers().getAsString(Files.API.Headers.UPLOAD_FILENAME);
    String decodedFilename =
      encodedFilename == null || !Base64.isBase64(encodedFilename)
        ? null
        : new String(Base64.decodeBase64(encodedFilename));

    if (nodeId == null
      || decodedFilename == null
      || decodedFilename.trim().isEmpty()
      || decodedFilename.trim().length() > 1024
    ) {
      context.fireExceptionCaught(new IllegalArgumentException());
      return;
    }

    User requester = (User) context.channel().attr(AttributeKey.valueOf("requester")).get();
    boolean overwrite = Boolean.parseBoolean(
      httpRequest.headers().getAsString(Headers.UPLOAD_OVERWRITE_VERSION)
    );
    long blobLength = Long.parseLong(httpRequest.headers().get(HttpHeaderNames.CONTENT_LENGTH));

    logger.debug(MessageFormat.format(
      "Uploading new version of node with id: {0}, overwrite: {1}", nodeId, overwrite
    ));

    initializeFileStream(context);

    CompletableFuture.runAsync(() -> {
        Integer version = blobService.uploadFileVersion(
          requester,
          context.channel().attr(fileStreamReader).get(),
          blobLength,
          nodeId,
          decodedFilename,
          overwrite
        ).orElseThrow(NoSuchElementException::new);

        sendSuccessUploadResponse(context, nodeId, version);

        prometheusService.getUploadVersionCounter().increment();
        context.flush().close();
      }
    ).exceptionally(throwable -> { // It is necessary because CompletableFuture eats exceptions
      context.fireExceptionCaught(throwable);
      return null;
    });
  }

  private void sendSuccessDownloadResponse(
    ChannelHandlerContext context,
    BlobResponse blobResponse
  ) {

    String encodedFilename = Try
      .of(() -> URLEncoder.encode(blobResponse.getFilename(), StandardCharsets.UTF_8))
      .getOrElseThrow(failure -> {
        String errorMessage = String.format(
          "Unable to encode node filename %s to download",
          blobResponse.getFilename()
        );
        return new IllegalArgumentException(errorMessage, failure);
      });

    DefaultHttpHeaders headers = new DefaultHttpHeaders(true);
    headers.add(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
    headers.add(HttpHeaderNames.CONTENT_LENGTH, blobResponse.getSize());
    headers.add(HttpHeaderNames.CONTENT_TYPE, blobResponse.getMimeType());
    headers.add(
      HttpHeaderNames.CONTENT_DISPOSITION,
      String.format("attachment; filename*=UTF-8''%s", encodedFilename)
    );

    context.write(new DefaultHttpResponse(
        HttpVersion.HTTP_1_1,
        HttpResponseStatus.OK,
        headers
      )
    );

    // Create netty buffer and start to fill it with the bytes arriving from the blob stream
    new NettyBufferWriter(context).writeStream(blobResponse.getBlobStream(), context.newPromise());
  }

  private void sendSuccessUploadResponse(
    ChannelHandlerContext context,
    String nodeId,
    int version
  ) {

    UploadVersionResponse response = new UploadVersionResponse();
    response.setNodeId(nodeId);
    response.setVersion(version);

    byte[] jsonByteArray;
    try {
      jsonByteArray = new ObjectMapper()
        .configure(SerializationFeature.FAIL_ON_EMPTY_BEANS, false)
        .writeValueAsBytes(response);
    } catch (JsonProcessingException exception) {
      context.fireExceptionCaught(exception);
      return;
    }

    HttpHeaders headers = new DefaultHttpHeaders(true);
    headers.add(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
    headers.add(HttpHeaderNames.CONTENT_TYPE, HttpHeaderValues.APPLICATION_JSON);

    context.write(new DefaultFullHttpResponse(
      HttpVersion.HTTP_1_0,
      HttpResponseStatus.OK,
      Unpooled.wrappedBuffer(jsonByteArray),
      headers,
      new DefaultHttpHeaders()
    ));
  }
}
