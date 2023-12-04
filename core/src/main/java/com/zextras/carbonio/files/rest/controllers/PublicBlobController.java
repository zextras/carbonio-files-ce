// SPDX-FileCopyrightText: 2023 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.rest.controllers;

import com.google.inject.Inject;
import com.zextras.carbonio.files.Files.API.Endpoints;
import com.zextras.carbonio.files.exceptions.BadRequestException;
import com.zextras.carbonio.files.netty.utilities.HttpResponseBuilder;
import com.zextras.carbonio.files.netty.utilities.NettyBufferWriter;
import com.zextras.carbonio.files.rest.services.BlobService;
import com.zextras.carbonio.files.rest.types.BlobResponse;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.HttpRequest;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.regex.Matcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@ChannelHandler.Sharable
public class PublicBlobController extends SimpleChannelInboundHandler<HttpRequest> {

  private static final Logger logger = LoggerFactory.getLogger(PublicBlobController.class);

  private final BlobService blobService;

  @Inject
  public PublicBlobController(BlobService blobService) {
    this.blobService = blobService;
  }

  @Override
  protected void channelRead0(ChannelHandlerContext context, HttpRequest httpRequest) {
    try {
      final Matcher publicLinkMatcher = Endpoints.PUBLIC_LINK.matcher(httpRequest.uri());
      final Matcher downloadViaPublicLinkMatcher =
          Endpoints.DOWNLOAD_VIA_PUBLIC_LINK.matcher(httpRequest.uri());
      final Matcher downloadPublicFileMatcher =
          Endpoints.DOWNLOAD_PUBLIC_FILE.matcher(httpRequest.uri());

      if (publicLinkMatcher.find()) {
        downloadByPublicLink(context, httpRequest, publicLinkMatcher);
        return;
      }

      if (downloadViaPublicLinkMatcher.find()) {
        downloadByPublicLink(context, httpRequest, downloadViaPublicLinkMatcher);
        return;
      }

      if (downloadPublicFileMatcher.find()) {
        downloadByNodeId(context, httpRequest, downloadPublicFileMatcher);
        return;
      }

      context.fireExceptionCaught(new BadRequestException());

    } catch (Exception exception) {
      logger.error("PublicBlobController catches an exception", exception);
      context.fireExceptionCaught(exception);
    }
  }

  void downloadByPublicLink(
      ChannelHandlerContext context, HttpRequest httpRequest, Matcher uriMatched) {

    final String publicLinkId = uriMatched.group(1);
    final Optional<BlobResponse> blobResponse = blobService.downloadFileByLink(publicLinkId);

    if (blobResponse.isPresent()) {
      context.write(HttpResponseBuilder.createSuccessDownloadHttpResponse(blobResponse.get()));
      new NettyBufferWriter(context)
          .writeStream(blobResponse.get().getBlobStream(), context.newPromise());
      return;
    }

    final String errorMessage =
        String.format(
            "Request %s: the link and/or the node associated to it does not exist",
            httpRequest.uri());

    context.fireExceptionCaught(new NoSuchElementException(errorMessage));
  }

  void downloadByNodeId(
      ChannelHandlerContext context, HttpRequest httpRequest, Matcher uriMatched) {

    final String nodeId = uriMatched.group(1);
    final Optional<BlobResponse> blobResponse = blobService.downloadPublicFileById(nodeId);

    if (blobResponse.isPresent()) {
      context.write(HttpResponseBuilder.createSuccessDownloadHttpResponse(blobResponse.get()));
      new NettyBufferWriter(context)
          .writeStream(blobResponse.get().getBlobStream(), context.newPromise());
      return;
    }

    final String errorMessage =
        String.format(
            "Request %s: the file does not exist or it is not contained on a public folder",
            httpRequest.uri());

    context.fireExceptionCaught(new NoSuchElementException(errorMessage));
  }
}
