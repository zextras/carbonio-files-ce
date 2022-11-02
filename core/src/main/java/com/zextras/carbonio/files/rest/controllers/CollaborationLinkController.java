// SPDX-FileCopyrightText: 2022 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.rest.controllers;

import com.google.inject.Inject;
import com.zextras.carbonio.files.Files.API.Endpoints;
import com.zextras.carbonio.files.dal.dao.User;
import com.zextras.carbonio.files.exceptions.InternalServerErrorException;
import com.zextras.carbonio.files.rest.services.CollaborationLinkService;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.util.AttributeKey;
import java.text.MessageFormat;
import java.util.regex.Matcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@ChannelHandler.Sharable
public class CollaborationLinkController extends SimpleChannelInboundHandler<HttpRequest> {

  private final static Logger logger = LoggerFactory.getLogger(CollaborationLinkController.class);

  private final CollaborationLinkService collaborationLinkService;

  @Inject
  public CollaborationLinkController(CollaborationLinkService collaborationLinkService) {
    this.collaborationLinkService = collaborationLinkService;
  }

  @Override
  protected void channelRead0(
    ChannelHandlerContext context,
    HttpRequest httpRequest
  ) {
    Matcher collaborationLinkMatcher = Endpoints.COLLABORATION_LINK.matcher(httpRequest.uri());

    try {
      if (collaborationLinkMatcher.find()) {
        String invitationId = collaborationLinkMatcher.group(1);
        User requester = (User) context.channel().attr(AttributeKey.valueOf("requester")).get();

        collaborationLinkService
          .createShareByInvitationId(invitationId, requester.getUuid())
          .onSuccess(sharedNode -> {
            FullHttpResponse response = new DefaultFullHttpResponse(
              httpRequest.protocolVersion(),
              HttpResponseStatus.TEMPORARY_REDIRECT
            );

            String nodeInternalURL = MessageFormat.format(
              "{0}/carbonio/files/?file={1}&node={1}&tab=sharing",
              requester.getDomain(),
              sharedNode.getId()
            );

            response.headers()
              .add(HttpHeaderNames.LOCATION, nodeInternalURL)
              .add(HttpHeaderNames.CONTENT_LENGTH, response.content().readableBytes());

            context
              .writeAndFlush(response)
              .addListener(ChannelFutureListener.CLOSE);
          })
          .onFailure(failure -> {
            logger.error(failure.getMessage());
            context
              .writeAndFlush(new DefaultFullHttpResponse(
                httpRequest.protocolVersion(),
                HttpResponseStatus.NOT_FOUND
              ))
              .addListener(ChannelFutureListener.CLOSE);
          });
      }
    } catch (Exception exception) {
      context.fireExceptionCaught(new InternalServerErrorException(exception));
    }
  }
}
