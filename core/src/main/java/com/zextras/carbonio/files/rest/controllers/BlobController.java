// SPDX-FileCopyrightText: 2022 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.rest.controllers;

import static io.netty.buffer.Unpooled.EMPTY_BUFFER;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.google.inject.Inject;
import com.zextras.carbonio.files.Files;
import com.zextras.carbonio.files.Files.API.Endpoints;
import com.zextras.carbonio.files.Files.API.Headers;
import com.zextras.carbonio.files.Files.Config.Log;
import com.zextras.carbonio.files.Files.ServiceDiscover;
import com.zextras.carbonio.files.Files.ServiceDiscover.Config;
import com.zextras.carbonio.files.clients.ServiceDiscoverHttpClient;
import com.zextras.carbonio.files.dal.EbeanDatabaseManager;
import com.zextras.carbonio.files.dal.dao.User;
import com.zextras.carbonio.files.dal.dao.ebean.ACL.SharePermission;
import com.zextras.carbonio.files.dal.repositories.interfaces.FileVersionRepository;
import com.zextras.carbonio.files.netty.utilities.BufferInputStream;
import com.zextras.carbonio.files.rest.services.BlobService;
import com.zextras.carbonio.files.rest.types.UploadNodeResponse;
import com.zextras.carbonio.files.rest.types.UploadVersionResponse;
import com.zextras.carbonio.files.utilities.PermissionsChecker;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
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
import io.netty.util.ReferenceCountUtil;
import io.vavr.control.Try;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLEncoder;
import java.text.MessageFormat;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import org.apache.commons.codec.binary.Base64;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@ChannelHandler.Sharable
public class BlobController extends SimpleChannelInboundHandler<HttpObject> {

  private static final Logger logger = LoggerFactory.getLogger(Log.LOGGER_NAME);

  private static final AttributeKey<BufferInputStream> fileStreamReader =
    AttributeKey.valueOf("FileStreamReader");

  /**
   * This ChannelFutureListener aims to close correctly the channel only after the promise has
   * finished successfully! This listener must be used in every netty response.
   */
  private static final ChannelFutureListener nettyChannelFutureClose =
    (promise) -> {
      if (!promise.isSuccess()) {
        logger.error("Failed to send the HTTP response, cause by: " + promise.cause().toString());
      }
      promise.channel().close();
    };

  private final BlobService          blobService;
  private final PermissionsChecker   permissionsChecker;
  private final EbeanDatabaseManager ebeanDatabaseManager;
  private final int                  maxNumberOfVersions;
  private final int                  maxNumberOfKeepVersions;

  @Inject
  public BlobController(
    BlobService blobService,
    PermissionsChecker permissionsChecker,
    EbeanDatabaseManager ebeanDatabaseManager
  ) {
    super(true);
    this.blobService = blobService;
    this.permissionsChecker = permissionsChecker;
    this.ebeanDatabaseManager = ebeanDatabaseManager;
    this.maxNumberOfVersions = Integer.parseInt(ServiceDiscoverHttpClient
      .defaultURL(ServiceDiscover.SERVICE_NAME)
      .getConfig(ServiceDiscover.Config.MAX_VERSIONS)
      .getOrElse(String.valueOf(ServiceDiscover.Config.DEFAULT_MAX_VERSIONS)));
    this.maxNumberOfKeepVersions =
      this.maxNumberOfVersions <= Config.DIFF_MAX_VERSION_AND_MAX_KEEP_VERSION
        ? 0
        : this.maxNumberOfVersions - Config.DIFF_MAX_VERSION_AND_MAX_KEEP_VERSION;
  }

  /**
   * {@inheritDoc}
   *
   * <p>This method handles the exception thrown If something goes wrong during the execution of
   * the request. It returns a response containing an INTERNAL_SERVER_ERROR to the client instead of
   * forwarding the exception to the next ChannelHandler in the ChannelPipeline.
   *
   * @param ctx is a {@link ChannelHandlerContext}.
   * @param cause is a {@link Throwable} containing the cause of the exception.
   */
  @Override
  public void exceptionCaught(
    ChannelHandlerContext ctx,
    Throwable cause
  ) {
    logger.warn("BlobController exception:\n" + cause.toString());
    ctx.writeAndFlush(new DefaultFullHttpResponse(
        HttpVersion.HTTP_1_0,
        HttpResponseStatus.INTERNAL_SERVER_ERROR
      )
    ).addListener(nettyChannelFutureClose);
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

        HttpVersion protocolVersionRequest = httpRequest.protocolVersion();

        if (downloadMatcher.find()) {
          download(context, httpRequest, protocolVersionRequest, downloadMatcher);
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
      }
    } catch (Exception exception) {
      // Catching the RuntimeException and the JsonProcessingException
      exceptionCaught(context, exception.getCause());
    }
  }

  private void downloadByLink(
    ChannelHandlerContext context,
    HttpRequest httpRequest,
    Matcher uriMatched
  ) {
    String publicLinkId = uriMatched.group(1);
    blobService
      .downloadFileByLink(publicLinkId)
      .onSuccess(blobResponse -> {
        DefaultHttpHeaders headers = new DefaultHttpHeaders(true);
        headers.add(HttpHeaderNames.CONNECTION, HttpHeaders.Values.CLOSE);
        headers.add(HttpHeaderNames.CONTENT_LENGTH, blobResponse.getSize());
        headers.add(HttpHeaderNames.CONTENT_TYPE, blobResponse.getMimeType());

        try {
          headers.add(
            HttpHeaderNames.CONTENT_DISPOSITION,
            "attachment; filename*=UTF-8''" + URLEncoder.encode(blobResponse.getFilename(), "UTF-8")
          );
        } catch (Exception ignore) {
        }

        context.write(
          new DefaultHttpResponse(httpRequest.protocolVersion(), HttpResponseStatus.OK, headers)
        );
        writeStream(context, blobResponse.getBlobStream());
      })
      .onFailure(failure -> {
        logger.error(failure.getMessage());
        context
          .writeAndFlush(new DefaultFullHttpResponse(
              httpRequest.protocolVersion(),
              HttpResponseStatus.BAD_REQUEST
            )
          )
          .addListener(nettyChannelFutureClose);
      });
  }

  private void download(
    ChannelHandlerContext context,
    HttpRequest request,
    HttpVersion protocolVersionRequest,
    Matcher uriMatched
  ) {
    User requester = (User) context.channel().attr(AttributeKey.valueOf("requester")).get();

    String nodeId = uriMatched.group(1);
    Optional<Integer> optVersion = Optional
      .ofNullable(uriMatched.group(2))
      .map(Integer::parseInt);

    if (permissionsChecker
      .getPermissions(nodeId, requester.getUuid())
      .has(SharePermission.READ_ONLY)
    ) {
      blobService
        .downloadFile(nodeId, optVersion, requester)
        .onSuccess(blobResponse -> {
          DefaultHttpHeaders headers = new DefaultHttpHeaders(true);
          headers.add(HttpHeaderNames.CONNECTION, HttpHeaders.Values.CLOSE);
          headers.add(HttpHeaderNames.CONTENT_LENGTH, blobResponse.getSize());
          headers.add(HttpHeaderNames.CONTENT_TYPE, blobResponse.getMimeType());

          try {
            headers.add(
              HttpHeaderNames.CONTENT_DISPOSITION,
              "attachment; filename*=UTF-8''" + URLEncoder.encode(blobResponse.getFilename(),
                "UTF-8")
            );
          } catch (Exception ignore) {
          }

          context.write(new DefaultHttpResponse(
              protocolVersionRequest,
              HttpResponseStatus.OK,
              headers
            )
          );
          writeStream(context, blobResponse.getBlobStream());
        })
        .onFailure(failure -> {
          logger.error(failure.getMessage());
          context
            .writeAndFlush(new DefaultFullHttpResponse(
              protocolVersionRequest,
              HttpResponseStatus.BAD_REQUEST)
            )
            .addListener(nettyChannelFutureClose);
        });

    } else {
      logger.error(MessageFormat.format(
        "Request {0}: The user {1} does not have the READ permission to download the node {2}",
        request.uri(),
        requester.getUuid(),
        nodeId
      ));

      context
        .writeAndFlush(new DefaultFullHttpResponse(
          protocolVersionRequest,
          HttpResponseStatus.NOT_FOUND)
        )
        .addListener(nettyChannelFutureClose);
    }
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
      context.writeAndFlush(new DefaultFullHttpResponse(
        httpRequest.protocolVersion(),
        HttpResponseStatus.BAD_REQUEST,
        Unpooled.EMPTY_BUFFER)
      );
      context.close();
      return;
    }

    initializeFileStream(context);

    CompletableFuture.supplyAsync(
      () -> {
        Try<String> strings =
          blobService.uploadFile(
            requester,
            context.channel().attr(fileStreamReader).get(),
            blobLength,
            parentId,
            decodedFilename,
            description
          );

        UploadNodeResponse response = new UploadNodeResponse();
        response.setNodeId(UUID.fromString(strings.get()));

        HttpHeaders headers = new DefaultHttpHeaders(true);
        headers.add(HttpHeaderNames.CONNECTION, HttpHeaders.Values.CLOSE);
        headers.add(HttpHeaderNames.CONTENT_TYPE, HttpHeaderValues.APPLICATION_JSON);

        ObjectMapper mapper = new ObjectMapper()
          .configure(SerializationFeature.FAIL_ON_EMPTY_BEANS, false);

        DefaultFullHttpResponse httpResponse = null;
        try {
          httpResponse = new DefaultFullHttpResponse(
            HttpVersion.HTTP_1_0,
            HttpResponseStatus.OK,
            Unpooled.wrappedBuffer(mapper.writeValueAsBytes(response)),
            headers,
            new DefaultHttpHeaders()
          );
        } catch (JsonProcessingException e) {
          e.printStackTrace();
        }

        context.write(httpResponse);
        context.flush().close();
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
      context.writeAndFlush(new DefaultFullHttpResponse(
        httpRequest.protocolVersion(),
        HttpResponseStatus.BAD_REQUEST,
        EMPTY_BUFFER
      ));
      context.close();
      return;
    }

    if (blobService.getNumberOfVersionOfFile(nodeId) > maxNumberOfVersions) {
      logger.info(MessageFormat.format(
        "Node: {0} has reached max number of versions ({1}), cannot add more versions",
        nodeId,
        maxNumberOfVersions
      ));
      context.writeAndFlush(new DefaultFullHttpResponse(
        httpRequest.protocolVersion(),
        HttpResponseStatus.METHOD_NOT_ALLOWED
      ));

      context.close();
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

    CompletableFuture.supplyAsync(
      () -> {
        Try<Integer> uploadedVersion =
          blobService.uploadFileVersion(
            requester,
            context.channel().attr(fileStreamReader).get(),
            blobLength,
            nodeId,
            decodedFilename,
            overwrite,
            maxNumberOfVersions
          );

        UploadVersionResponse response = new UploadVersionResponse();
        response.setNodeId(nodeId);
        response.setVersion(uploadedVersion.get());

        HttpHeaders headers = new DefaultHttpHeaders(true);
        headers.add(HttpHeaderNames.CONNECTION, HttpHeaders.Values.CLOSE);
        headers.add(HttpHeaderNames.CONTENT_TYPE, HttpHeaderValues.APPLICATION_JSON);

        ObjectMapper mapper = new ObjectMapper()
          .configure(SerializationFeature.FAIL_ON_EMPTY_BEANS, false);

        DefaultFullHttpResponse httpResponse = null;
        try {
          httpResponse = new DefaultFullHttpResponse(
            HttpVersion.HTTP_1_0,
            HttpResponseStatus.OK,
            Unpooled.wrappedBuffer(mapper.writeValueAsBytes(response)),
            headers,
            new DefaultHttpHeaders()
          );
        } catch (JsonProcessingException e) {
          e.printStackTrace();
        }

        context.write(httpResponse);
        context.flush().close();
        return null;
      });

  }

  ChannelPromise writeStream(
    ChannelHandlerContext ctx,
    InputStream contentStream
  ) {
    ChannelPromise promise = ctx.newPromise();
    ByteBuf buffer = ctx.alloc().buffer(64 * 1024);
    buffer.retain();

    CompletableFuture.supplyAsync(() -> {
      writeStream(ctx, contentStream, promise, buffer);
      return null;
    });

    return promise;
  }

  void writeStream(
    ChannelHandlerContext ctx,
    InputStream contentStream,
    ChannelPromise promise,
    ByteBuf byteBuf
  ) {
    try {
      byteBuf.writeBytes(contentStream, byteBuf.capacity());
    } catch (IOException ex) {
      promise.setFailure(ex);
      byteBuf.release(2);
    }

    // writeBytes() uses a simple .read() from InputStream
    // so in worst case it could return 1 byte each time
    // but never 0 until EOF
    if (byteBuf.writerIndex() == 0) {
      ReferenceCountUtil.safeRelease(byteBuf, 2);
      ctx.flush().close();
      try {
        contentStream.close();
      } catch (IOException e) {
        e.printStackTrace();
      }
      promise.setSuccess();
      return;
    }

    ctx.writeAndFlush(byteBuf).addListener(future -> {
        if (future.isSuccess()) {
          byteBuf.retain();
          byteBuf.clear();
          writeStream(ctx, contentStream, promise, byteBuf);
        } else {
          promise.setFailure(future.cause());
        }
      }
    );
  }
}
