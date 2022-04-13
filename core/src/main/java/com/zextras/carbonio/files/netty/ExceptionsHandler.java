// SPDX-FileCopyrightText: 2022 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.netty;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.zextras.carbonio.files.exceptions.BadRequestException;
import com.zextras.carbonio.files.exceptions.InternalServerErrorException;
import com.zextras.carbonio.files.exceptions.NodeNotFoundException;
import com.zextras.carbonio.files.exceptions.RequestEntityTooLargeException;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandler.Sharable;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;

@Sharable
public class ExceptionsHandler extends ChannelInboundHandlerAdapter {

  @Override
  public void exceptionCaught(
    ChannelHandlerContext context,
    Throwable cause
  ) {
    HttpResponseStatus responseStatus;

    if ( cause instanceof RequestEntityTooLargeException) {
      responseStatus = HttpResponseStatus.REQUEST_ENTITY_TOO_LARGE;
    }
    else if (cause instanceof JsonProcessingException
      || cause instanceof InternalServerErrorException
    ) {
      responseStatus = HttpResponseStatus.INTERNAL_SERVER_ERROR;
    }
    else if( cause instanceof BadRequestException) {
      responseStatus = HttpResponseStatus.BAD_REQUEST;
    }
    else if( cause instanceof NodeNotFoundException) {
      responseStatus = HttpResponseStatus.NOT_FOUND;
    } else {
      responseStatus = HttpResponseStatus.INTERNAL_SERVER_ERROR;
    }

    context
      .writeAndFlush(new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, responseStatus))
      .addListener(ChannelFutureListener.CLOSE);
  }

}
