// SPDX-FileCopyrightText: 2022 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.netty.utilities;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.util.ReferenceCountUtil;
import java.io.IOException;
import java.io.InputStream;

public class NettyBufferWriter {

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
      } catch (IOException e) {
        e.printStackTrace();
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
}
