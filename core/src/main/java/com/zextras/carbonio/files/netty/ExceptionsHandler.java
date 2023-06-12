// SPDX-FileCopyrightText: 2022 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.netty;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.zextras.carbonio.files.exceptions.AuthenticationException;
import com.zextras.carbonio.files.exceptions.BadRequestException;
import com.zextras.carbonio.files.exceptions.FileTypeMismatchException;
import com.zextras.carbonio.files.exceptions.InternalServerErrorException;
import com.zextras.carbonio.files.exceptions.MaxNumberOfFileVersionsException;
import com.zextras.carbonio.files.exceptions.NodeNotFoundException;
import com.zextras.carbonio.files.exceptions.RequestEntityTooLargeException;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandler.Sharable;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import java.nio.charset.StandardCharsets;
import java.util.NoSuchElementException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Sharable
public class ExceptionsHandler extends ChannelInboundHandlerAdapter {

  private static final Logger logger = LoggerFactory.getLogger(ExceptionsHandler.class);

  /**
   * {@inheritDoc}
   *
   * <p>This method handles the exception thrown If something goes wrong during the execution of
   * the request. It creates a response containing a status code based on the type of the
   * {@link Throwable} and it returns it to the client instead of forwarding the exception to the
   * next ChannelHandler in the ChannelPipeline.
   *
   * @param context is a {@link ChannelHandlerContext}.
   * @param cause is a {@link Throwable} containing the cause of the exception.
   */
  @Override
  public void exceptionCaught(
    ChannelHandlerContext context,
    Throwable cause
  ) {
    cause = cause.getCause() == null ? cause : cause.getCause();
    HttpResponseStatus responseStatus;
    String payload;

    if ( cause instanceof RequestEntityTooLargeException) {
      responseStatus = HttpResponseStatus.REQUEST_ENTITY_TOO_LARGE;
      payload = HttpResponseStatus.REQUEST_ENTITY_TOO_LARGE.toString();
    }
    else if (cause instanceof JsonProcessingException
      || cause instanceof InternalServerErrorException
    ) {
      responseStatus = HttpResponseStatus.INTERNAL_SERVER_ERROR;
      payload = HttpResponseStatus.INTERNAL_SERVER_ERROR.toString();
    }
    else if( cause instanceof BadRequestException
      || cause instanceof FileTypeMismatchException
      || cause instanceof IllegalArgumentException
    ) {
      responseStatus = HttpResponseStatus.BAD_REQUEST;
      payload = HttpResponseStatus.BAD_REQUEST.toString();
    }
    else if( cause instanceof NodeNotFoundException
      || cause instanceof NoSuchElementException
    ) {
      responseStatus = HttpResponseStatus.NOT_FOUND;
      payload = HttpResponseStatus.NOT_FOUND.toString();
    }
    else if(cause instanceof AuthenticationException) {
      responseStatus = HttpResponseStatus.UNAUTHORIZED;
      payload = cause.getMessage();
    }
    else if (cause instanceof MaxNumberOfFileVersionsException) {
      responseStatus = HttpResponseStatus.METHOD_NOT_ALLOWED;
      payload = cause.getMessage();
    }
    else {
      responseStatus = HttpResponseStatus.INTERNAL_SERVER_ERROR;
      payload = HttpResponseStatus.INTERNAL_SERVER_ERROR.toString();
    }

    logger.error(String.format("Failed to execute the request. %s", payload), cause);

    context
      .writeAndFlush(new DefaultFullHttpResponse(
        HttpVersion.HTTP_1_1,
        responseStatus,
        Unpooled.wrappedBuffer(payload.getBytes(StandardCharsets.UTF_8))
      ))
      .addListener(ChannelFutureListener.CLOSE);
  }

}
