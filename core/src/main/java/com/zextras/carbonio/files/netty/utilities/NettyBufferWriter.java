// SPDX-FileCopyrightText: 2022 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.netty.utilities;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.handler.stream.ChunkedStream;
import io.netty.util.AttributeKey;
import io.netty.util.ReferenceCountUtil;
import java.io.IOException;
import java.io.InputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class NettyBufferWriter {

  private static final Logger logger = LoggerFactory.getLogger(NettyBufferWriter.class);

  private final ChannelHandlerContext context;
  private final ByteBuf               byteBuffer;

  public NettyBufferWriter(ChannelHandlerContext context) {
    this.context = context;
    byteBuffer = context.alloc().buffer(64 * 1024);
    byteBuffer.retain();
  }

  public void writeStream(
    InputStream contentStream,
    ChannelPromise promise
  ) {
    try {
      byteBuffer.writeBytes(contentStream, byteBuffer.capacity());
    } catch (IOException ex) {
      promise.setFailure(ex);
      byteBuffer.release(2);
    }

    // writeBytes() uses a simple .read() from InputStream
    // so in worst case it could return 1 byte each time
    // but never 0 until EOF
    if (byteBuffer.writerIndex() == 0) {
      ReferenceCountUtil.safeRelease(byteBuffer, 2);
      context.flush().close();

      try {
        contentStream.close();
      } catch (IOException exception) {
       logger.error("Exception when closing the upload input stream", exception);
      }
      promise.setSuccess();
      return;
    }

    context.writeAndFlush(byteBuffer)
      .addListener(future -> {
          if (future.isSuccess()) {
            byteBuffer.retain();
            byteBuffer.clear();
            writeStream(contentStream, promise);
          } else {
            promise.setFailure(future.cause());
          }
        }
      );
  }

  public void writeStreamAsChunked(InputStream inputStream) {
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
