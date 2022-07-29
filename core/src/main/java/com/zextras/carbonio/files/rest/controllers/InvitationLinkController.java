// SPDX-FileCopyrightText: 2022 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.rest.controllers;

import com.google.inject.Inject;
import com.zextras.carbonio.files.Files.API.Endpoints;
import com.zextras.carbonio.files.dal.dao.User;
import com.zextras.carbonio.files.dal.dao.ebean.ACL;
import com.zextras.carbonio.files.dal.dao.ebean.Node;
import com.zextras.carbonio.files.dal.dao.ebean.Share;
import com.zextras.carbonio.files.dal.repositories.interfaces.InvitationLinkRepository;
import com.zextras.carbonio.files.dal.repositories.interfaces.NodeRepository;
import com.zextras.carbonio.files.dal.repositories.interfaces.ShareRepository;
import com.zextras.carbonio.files.exceptions.InternalServerErrorException;
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
import java.util.Optional;
import java.util.regex.Matcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * BIG TODO: This implementation MUST NOT BE APPROVED
 */
@ChannelHandler.Sharable
public class InvitationLinkController extends SimpleChannelInboundHandler<HttpRequest> {

  private final static Logger logger = LoggerFactory.getLogger(InvitationLinkController.class);

  private final InvitationLinkRepository invitationLinkRepository;
  private final NodeRepository           nodeRepository;
  private final ShareRepository          shareRepository;

  @Inject
  public InvitationLinkController(
    InvitationLinkRepository invitationLinkRepository,
    NodeRepository nodeRepository,
    ShareRepository shareRepository
  ) {
    this.invitationLinkRepository = invitationLinkRepository;
    this.nodeRepository = nodeRepository;
    this.shareRepository = shareRepository;
  }

  @Override
  protected void channelRead0(
    ChannelHandlerContext context,
    HttpRequest httpRequest
  ) {
    Matcher invitationLinkMatcher = Endpoints.INVITATION_LINK.matcher(httpRequest.uri());

    try {
      if (invitationLinkMatcher.find()) {
        String invitationId = invitationLinkMatcher.group(1);
        User requester = (User) context.channel().attr(AttributeKey.valueOf("requester")).get();

        FullHttpResponse httpResponse = invitationLinkRepository
          .getLinkByInvitationId(invitationId)
          .map(invitationLink -> {

            Node node = nodeRepository.getNode(invitationLink.getNodeId()).get();

            if (!node.getOwnerId().equals(requester.getUuid().toString())) {
              shareRepository.upsertShare(
                invitationLink.getNodeId(),
                requester.getUuid(),
                ACL.decode(invitationLink.getPermissions()),
                true,
                Optional.empty()
              );
            }

            FullHttpResponse response = new DefaultFullHttpResponse(
              httpRequest.protocolVersion(),
              HttpResponseStatus.TEMPORARY_REDIRECT
            );

            String nodeInternalURL = MessageFormat.format(
              "{0}/carbonio/files/?folder={1}&node={2}",
              requester.getDomain(),
              node.getParentId().get(),
              node.getId()
            );

            response.headers().add(HttpHeaderNames.LOCATION, nodeInternalURL);
            response.headers()
              .add(HttpHeaderNames.CONTENT_LENGTH, response.content().readableBytes());

            return response;
          })
          .orElse(new DefaultFullHttpResponse(
            httpRequest.protocolVersion(),
            HttpResponseStatus.NOT_FOUND)
          );
        context
          .writeAndFlush(httpResponse)
          .addListener(ChannelFutureListener.CLOSE);
      } else {
        context
          .writeAndFlush(new DefaultFullHttpResponse(httpRequest.protocolVersion(),
            HttpResponseStatus.NOT_FOUND))
          .addListener(ChannelFutureListener.CLOSE);
      }
    } catch (Exception exception) {
      context.fireExceptionCaught(new InternalServerErrorException(exception));
    }
  }
}
