// SPDX-FileCopyrightText: 2022 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.netty.utilities;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelConfig;
import java.io.IOException;
import java.io.InputStream;
import java.text.MessageFormat;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class BufferInputStream extends InputStream {

  private static final Logger logger = LoggerFactory.getLogger(BufferInputStream.class);

  private static int           maxCapacity = 500 * 1024 * 1024;
  private static int           capacity    = 100 * 1024 * 1024;
  public         ByteBuf       buffer;
  private        Lock          lock;
  private        Condition     condition;
  private        boolean       isDone;
  private        ChannelConfig nettyChannelConfig;
  private        int           sum;

  public BufferInputStream(ChannelConfig channelConfig) {
    this.nettyChannelConfig = channelConfig;
    this.buffer = Unpooled.buffer(64 * 1024, maxCapacity);
    lock = new ReentrantLock();
    sum = 0;

    condition = lock.newCondition();
    isDone = false;
  }

  @Override
  public int read() throws IOException {
    byte[] byteArray = new byte[0];
    return read(byteArray, 1, 1);
  }

  public void addContent(ByteBuf byteBuf) {
    lock.lock();
    try {
      int r = byteBuf.readableBytes();
      buffer.writeBytes(byteBuf, byteBuf.readableBytes());
      sum += r;

      if (buffer.writerIndex() >= capacity) {
        nettyChannelConfig.setAutoRead(false);
      }

      condition.signalAll();
    } catch (Exception e) {
      logger.warn(MessageFormat.format(
        "Unexpected error encountered: {0}",
        e.getMessage()
      ));
    } finally {
      lock.unlock();
    }
  }

  @Override
  public int read(byte[] byteArray) throws IOException {
    return read(byteArray, 0, byteArray.length);
  }

  @Override
  public int read(
    byte[] byteArray,
    int offset,
    int length
  ) throws IOException {
    lock.lock();
    try {

      if (isDone && buffer.readableBytes() == 0) {
        return -1;
      }

      if (length > 0 && (!isDone || buffer.readableBytes() > 0)) {
        while (!isDone && buffer.readableBytes() == 0) {
          buffer.clear();
          nettyChannelConfig.setAutoRead(true);

          boolean signaled = condition.await(10 * 1024, TimeUnit.MILLISECONDS);
          if (!signaled) {
            logger.warn("An interrupt signal has been received while reading from input buffer");
            throw new IOException("time");
          }
        }

        int readSize = Math.min(
          buffer.readableBytes(),
          length
        );

        buffer.readBytes(
          byteArray,
          offset,
          readSize
        );

        return readSize;
      }

      return 0;

    } catch (InterruptedException e) {
      return 0;
    } finally {
      lock.unlock();
    }
  }

  public void finishWrite() {
    lock.lock();
    try {
      isDone = true;
      condition.signalAll();
      logger.debug(MessageFormat.format(
        "Read done, {0} bytes written with a buffer capacity of {1}",
        sum,
        buffer.capacity()
      ));
    } finally {
      lock.unlock();
    }
  }
}
