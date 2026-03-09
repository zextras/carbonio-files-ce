// SPDX-FileCopyrightText: 2022 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.netty.utilities;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.util.AttributeKey;
import io.netty.util.ReferenceCountUtil;
import java.io.IOException;
import java.io.InputStream;
import java.io.PipedInputStream;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class NettyBufferWriter {

  private static final Logger logger = LoggerFactory.getLogger(NettyBufferWriter.class);

  private final ChannelHandlerContext context;

  public NettyBufferWriter(ChannelHandlerContext context) {
    this.context = context;
  }

  public void writeStream(
    InputStream contentStream,
    ChannelPromise promise
  ) {
    ByteBuf byteBuffer = context.alloc().buffer(64 * 1024);
    byteBuffer.retain();
    writeStreamChunk(contentStream, promise, byteBuffer);
  }

  private void writeStreamChunk(
    InputStream contentStream,
    ChannelPromise promise,
    ByteBuf byteBuffer
  ) {
    try {
      byteBuffer.writeBytes(contentStream, byteBuffer.capacity());
    } catch (IOException ex) {
      promise.setFailure(ex);
      byteBuffer.release(2);
      return;
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
            writeStreamChunk(contentStream, promise, byteBuffer);
          } else {
            promise.setFailure(future.cause());
          }
        }
      );
  }

  /**
   * Streams a {@link PipedInputStream} to the channel without blocking the event loop.
   * Uses {@link PipedInputStream#available()} to check for data and schedules retries
   * when no data is ready, keeping the event loop free to serve other requests.
   * Closes the pipe when the channel is closed, signaling the producer to stop.
   *
   * @param pipedInputStream the pipe to read from
   * @param producerDone flag set by the producer after it closes the pipe output,
   *                     used to safely detect EOF without blocking
   */
  public void writePipedStream(
      PipedInputStream pipedInputStream,
      AtomicBoolean producerDone,
      AtomicBoolean cancelled
  ) {
    context.channel().closeFuture().addListener(f -> {
      if (cancelled != null) {
        cancelled.set(true);
      }
      closeQuietly(pipedInputStream);
    });
    pollAndWrite(pipedInputStream, producerDone);
  }

  private void pollAndWrite(PipedInputStream pipedInputStream, AtomicBoolean producerDone) {
    if (!context.channel().isActive()) {
      closeQuietly(pipedInputStream);
      return;
    }

    try {
      int available = pipedInputStream.available();

      if (available > 0) {
        ByteBuf buf = context.alloc().buffer(Math.min(available, 64 * 1024));
        int bytesRead = buf.writeBytes(pipedInputStream, buf.capacity());

        if (bytesRead > 0) {
          context.writeAndFlush(buf).addListener(future -> {
            if (future.isSuccess()) {
              pollAndWrite(pipedInputStream, producerDone);
            } else {
              logger.debug("Write failed, closing stream", future.cause());
              closeQuietly(pipedInputStream);
            }
          });
        } else {
          buf.release();
          sendLastContentAndClose(pipedInputStream);
        }
      } else if (producerDone.get()) {
        // Producer has finished and closed the pipe. read() will return -1 immediately.
        sendLastContentAndClose(pipedInputStream);
      } else {
        // No data yet, producer still working. Retry after short delay.
        context.executor().schedule(
            () -> pollAndWrite(pipedInputStream, producerDone),
            10, TimeUnit.MILLISECONDS
        );
      }
    } catch (IOException e) {
      logger.debug("Pipe closed during read", e);
      sendLastContentAndClose(pipedInputStream);
    }
  }

  private void sendLastContentAndClose(PipedInputStream pipedInputStream) {
    closeQuietly(pipedInputStream);
    context.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT).addListener(future -> {
      if (!"keep-alive".equals(context.channel().attr(AttributeKey.valueOf("connection")).get())) {
        context.close();
      }
    });
  }

  private void closeQuietly(InputStream stream) {
    try {
      stream.close();
    } catch (IOException e) {
      logger.debug("Error closing stream", e);
    }
  }
}
