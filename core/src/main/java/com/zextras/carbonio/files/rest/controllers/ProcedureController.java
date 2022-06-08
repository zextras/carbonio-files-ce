// SPDX-FileCopyrightText: 2022 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.rest.controllers;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.inject.Inject;
import com.zextras.carbonio.files.Files.API.ContextAttribute;
import com.zextras.carbonio.files.dal.dao.User;
import com.zextras.carbonio.files.dal.dao.ebean.ACL.SharePermission;
import com.zextras.carbonio.files.exceptions.InternalServerErrorException;
import com.zextras.carbonio.files.exceptions.NodeNotFoundException;
import com.zextras.carbonio.files.netty.ExceptionsHandler;
import com.zextras.carbonio.files.rest.services.ProcedureService;
import com.zextras.carbonio.files.rest.types.UploadAttachmentResponse;
import com.zextras.carbonio.files.rest.types.UploadToRequest;
import com.zextras.carbonio.files.rest.types.UploadToRequest.TargetModule;
import com.zextras.carbonio.files.utilities.PermissionsChecker;
import com.zextras.carbonio.usermanagement.exceptions.BadRequest;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandler.Sharable;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.util.AttributeKey;
import java.nio.charset.StandardCharsets;
import java.text.MessageFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Sharable
public class ProcedureController extends SimpleChannelInboundHandler<FullHttpRequest> {

  private final static Logger logger = LoggerFactory.getLogger(ProcedureController.class);

  private final ProcedureService   procedureService;
  private final PermissionsChecker permissionsChecker;

  @Inject
  public ProcedureController(
    ProcedureService procedureService,
    PermissionsChecker permissionsChecker
  ) {
    this.procedureService = procedureService;
    this.permissionsChecker = permissionsChecker;
  }

  @Override
  protected void channelRead0(
    ChannelHandlerContext context,
    FullHttpRequest httpRequest
  ) {
    if (HttpMethod.POST.equals(httpRequest.method())) {
      uploadToModule(context, httpRequest);
      return;
    }

    logger.error(MessageFormat.format(
      "Request {0} {1}: bad request",
      httpRequest.method(),
      httpRequest.uri()
    ));

    context.fireExceptionCaught(new BadRequest());
  }

  /**
   * Allows to upload a specific file to a specific {@link TargetModule} module. All the exceptions
   * are handled by the {@link ExceptionsHandler} handler.
   *
   * @param context is the {@link ChannelHandlerContext}
   * @param httpRequest is the {@link FullHttpRequest}
   */
  private void uploadToModule(
    ChannelHandlerContext context,
    FullHttpRequest httpRequest
  ) {

    final User requester = (User) context
      .channel()
      .attr(AttributeKey.valueOf(ContextAttribute.REQUESTER))
      .get();

    final String requesterCookies = (String) context
      .channel()
      .attr(AttributeKey.valueOf(ContextAttribute.COOKIES))
      .get();

    UploadToRequest bodyRequest;
    try {
      bodyRequest = new ObjectMapper().readValue(
        httpRequest.content().toString(StandardCharsets.UTF_8),
        UploadToRequest.class
      );
    } catch (JsonProcessingException exception) {
      logger.error(MessageFormat.format(
        "Request {0}: Unable to deserialize the body request\n{1}",
        httpRequest.uri(),
        exception.getMessage()
      ));

      context.fireExceptionCaught(exception);
      return;
    }

    if (permissionsChecker
      .getPermissions(bodyRequest.getNodeId().toString(), requester.getUuid())
      .has(SharePermission.READ_ONLY)
    ) {
      procedureService
        .uploadToModule(
          bodyRequest.getNodeId(),
          bodyRequest.getTargetModule(),
          requester,
          requesterCookies
        )
        .onSuccess(attachmentId -> {
          try {
            UploadAttachmentResponse bodyResponse = new UploadAttachmentResponse(attachmentId);

            HttpHeaders headers = new DefaultHttpHeaders(true);
            headers.add(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
            headers.add(HttpHeaderNames.CONTENT_TYPE, HttpHeaderValues.APPLICATION_JSON);

            HttpResponse httpResponse = new DefaultFullHttpResponse(
              httpRequest.protocolVersion(),
              HttpResponseStatus.OK,
              Unpooled.wrappedBuffer(new ObjectMapper().writeValueAsBytes(bodyResponse)),
              headers,
              new DefaultHttpHeaders()
            );

            logger.info(MessageFormat.format(
              "Request {0}: Uploaded node {1} to {2}. The attachment id is: {3}",
              httpRequest.uri(),
              bodyRequest.getNodeId(),
              bodyRequest.getTargetModule(),
              attachmentId
            ));

            context
              .writeAndFlush(httpResponse)
              .addListener(ChannelFutureListener.CLOSE);

          } catch (JsonProcessingException exception) {
            logger.error("Unable to serialize upload-to response: " + exception);
            context.fireExceptionCaught(new InternalServerErrorException(exception));
          }
        })
        .onFailure(failure -> {
            logger.error(MessageFormat.format(
              "Unable to upload the blob {0} to {1} \n {2}",
              bodyRequest.getNodeId(),
              bodyRequest.getTargetModule(),
              failure.getMessage()
            ));

            context.fireExceptionCaught(failure);
          }
        );
    } else {
      logger.error(MessageFormat.format(
        "Request {0} node {1} to upload not found",
        httpRequest.uri(),
        bodyRequest.getNodeId()
      ));
      context.fireExceptionCaught(new NodeNotFoundException());
    }
  }
}
