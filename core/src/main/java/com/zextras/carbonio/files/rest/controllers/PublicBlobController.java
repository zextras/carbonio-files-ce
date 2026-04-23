// SPDX-FileCopyrightText: 2023 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.rest.controllers;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.inject.Inject;
import com.zextras.carbonio.files.Constants;
import com.zextras.carbonio.files.Constants.API.Endpoints;
import com.zextras.carbonio.files.dal.dao.ebean.Node;
import com.zextras.carbonio.files.exceptions.AccessCodeRequiredException;
import com.zextras.carbonio.files.exceptions.BadRequestException;
import com.zextras.carbonio.files.netty.utilities.HttpResponseBuilder;
import com.zextras.carbonio.files.netty.utilities.NettyBufferWriter;
import com.zextras.carbonio.files.rest.services.BlobService;
import com.zextras.carbonio.files.rest.types.BlobResponse;
import io.netty.buffer.ByteBuf;
import io.netty.channel.*;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.handler.codec.http.QueryStringDecoder;

import io.netty.util.AttributeKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.regex.Matcher;

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
      final Matcher downloadPublicFileCheckMatcher =
          Endpoints.DOWNLOAD_PUBLIC_FILE_CHECK.matcher(httpRequest.uri());
      final Matcher downloadPublicMultipleMatcher =
          Endpoints.DOWNLOAD_PUBLIC_MULTIPLE.matcher(httpRequest.uri());
      final Matcher downloadPublicMultipleCheckMatcher =
          Endpoints.DOWNLOAD_PUBLIC_MULTIPLE_CHECK.matcher(httpRequest.uri());

      if (downloadPublicFileCheckMatcher.find()) {
        checkDownloadPublicFile(context, httpRequest, downloadPublicFileCheckMatcher);
        return;
      }

      if (downloadPublicMultipleCheckMatcher.find()) {
        checkDownloadPublicMultiple(context, httpRequest);
        return;
      }

      if (downloadPublicMultipleMatcher.find()) {
        downloadPublicMultiple(context, httpRequest);
        return;
      }

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

  void checkDownloadPublicMultiple(
      ChannelHandlerContext context,
      HttpRequest request) {

    if (!(request instanceof FullHttpRequest fullRequest)) {
      context.fireExceptionCaught(
          new IllegalArgumentException("Request must be a FullHttpRequest to read body"));
      return;
    }

    ByteBuf content = fullRequest.content();

    String bodyContent = content.toString(StandardCharsets.UTF_8);
    List<String> nodeIds;
    String nodeLinkId;
    String accessCode = null;

    try {
      ObjectMapper mapper = new ObjectMapper();
      Map<String, Object> jsonBody = mapper.readValue(
          bodyContent, new TypeReference<Map<String, Object>>() {});

      nodeIds = mapper.convertValue(
          jsonBody.get(Constants.API.BodyAttributes.NODE_IDS), new TypeReference<List<String>>() {});
      nodeLinkId = (String) jsonBody.get(Constants.API.BodyAttributes.NODE_LINK_ID);
      accessCode = (String) jsonBody.get(Constants.API.BodyAttributes.ACCESS_CODE);

    } catch (JsonProcessingException e) {
      context.fireExceptionCaught(
          new IllegalArgumentException("Invalid JSON body", e));
      return;
    }

    Optional<List<Node>> optNodes = blobService.checkDownloadPublicMultiple(
        nodeIds, nodeLinkId, accessCode);

    if (optNodes.isPresent()) {
      ChannelFuture future = context.writeAndFlush(
          HttpResponseBuilder.createNoContentResponse());
      future.addListener(ChannelFutureListener.CLOSE);
    } else {
      context.fireExceptionCaught(new NoSuchElementException(
          "Some nodes not accessible with provided link"));
    }
  }

  void downloadPublicMultiple(
      ChannelHandlerContext context,
      HttpRequest request) {

    if (!(request instanceof FullHttpRequest fullRequest)) {
      context.fireExceptionCaught(
          new IllegalArgumentException("Request must be a FullHttpRequest to read body"));
      return;
    }

    ByteBuf content = fullRequest.content();

    String bodyContent = content.toString(StandardCharsets.UTF_8);
    QueryStringDecoder decoder = new QueryStringDecoder(bodyContent, false);
    Map<String, List<String>> parameters = decoder.parameters();

    List<String> nodeIdsParam = parameters.get(Constants.API.BodyAttributes.NODE_IDS);
    List<String> nodeLinkIdParam = parameters.get(Constants.API.BodyAttributes.NODE_LINK_ID);
    List<String> accessCodeParam = parameters.get(Constants.API.BodyAttributes.ACCESS_CODE);

    if (nodeIdsParam == null || nodeLinkIdParam == null) {
      context.fireExceptionCaught(
          new IllegalArgumentException("Missing required parameters"));
      return;
    }

    String nodeIdsJson = nodeIdsParam.get(0);
    String nodeLinkId = nodeLinkIdParam.get(0);
    String accessCode = accessCodeParam != null ? accessCodeParam.get(0) : null;

    List<String> nodeIds;
    try {
      nodeIds = new ObjectMapper().readValue(
          nodeIdsJson, new TypeReference<>() {
          });
    } catch (JsonProcessingException e) {
      context.fireExceptionCaught(
          new IllegalArgumentException("Invalid nodeIds JSON", e));
      return;
    }

    BlobResponse blobResponse = blobService.downloadPublicMultiple(
            nodeIds, nodeLinkId, accessCode)
        .orElseThrow(() -> new NoSuchElementException(
            "Nodes not accessible with provided link"));

    context.write(HttpResponseBuilder.createSuccessDownloadHttpResponse(blobResponse));
    new NettyBufferWriter(context).writePipedStream(
        blobResponse.getPipedStream(),
        blobResponse.getProducerDone(),
        blobResponse.getCancelled());
  }

  void downloadByPublicLink(
      ChannelHandlerContext context, HttpRequest httpRequest, Matcher uriMatched) {

    final String publicLinkId = uriMatched.group(1);

    // Redirect to access link if link is protected by access code
    final Optional<BlobResponse> blobResponse;
    try {
      blobResponse = blobService.downloadFileByLink(publicLinkId);
    } catch (AccessCodeRequiredException e) {
      String newRedirectUrl = "/files/public/link/access/" + publicLinkId;
      context.writeAndFlush(HttpResponseBuilder.createRedirectHttpResponse(newRedirectUrl)).addListener(ChannelFutureListener.CLOSE);
      return;
    }

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

  void checkDownloadPublicFile(
      ChannelHandlerContext context,
      HttpRequest request,
      Matcher uriMatched) {

    String nodeId = uriMatched.group(1);
    String nodeLinkId = uriMatched.group(2);
    String accessCode = uriMatched.group(3);

    Optional<Node> optNode = blobService.checkDownloadPublicFileById(
        nodeId, nodeLinkId, accessCode);

    if (optNode.isPresent()) {
      ChannelFuture future = context.writeAndFlush(
          HttpResponseBuilder.createNoContentResponse());
      future.addListener(ChannelFutureListener.CLOSE);
    } else {
      context.fireExceptionCaught(new NoSuchElementException(
          String.format("Node %s not accessible with provided link", nodeId)));
    }
  }

  void downloadByNodeId(
      ChannelHandlerContext context, HttpRequest httpRequest, Matcher uriMatched) {

    final String nodeId = uriMatched.group(1);
    final String nodeLinkId = uriMatched.group(2);
    final String accessCode = uriMatched.group(3);

    final Optional<BlobResponse> blobResponse = blobService.downloadPublicFileById(nodeId, nodeLinkId, accessCode);

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
