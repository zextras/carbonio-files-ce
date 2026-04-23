// SPDX-FileCopyrightText: 2024 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.netty.utilities;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.util.concurrent.GenericFutureListener;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class NettyBufferWriterTest {

  @Test
  void givenAWriteFailure_writeStream_shouldReleaseBufferToAvoidLeak() throws Exception {
    // Given
    ChannelHandlerContext contextMock = Mockito.mock(ChannelHandlerContext.class);
    ByteBufAllocator allocatorMock = Mockito.mock(ByteBufAllocator.class);
    ChannelPromise promiseMock = Mockito.mock(ChannelPromise.class);
    ChannelFuture failedFutureMock = Mockito.mock(ChannelFuture.class);

    ByteBuf buffer = Unpooled.buffer(64 * 1024);

    Mockito.when(contextMock.alloc()).thenReturn(allocatorMock);
    Mockito.when(allocatorMock.buffer(64 * 1024)).thenReturn(buffer);

    // Simulate Netty: release the buffer by 1 after writeAndFlush, then return a failed future
    Mockito.when(contextMock.writeAndFlush(Mockito.any(ByteBuf.class))).thenAnswer(inv -> {
      ((ByteBuf) inv.getArgument(0)).release();
      return failedFutureMock;
    });

    Mockito.when(failedFutureMock.isSuccess()).thenReturn(false);
    Mockito.when(failedFutureMock.cause()).thenReturn(new RuntimeException("write failed"));

    // Invoke the listener synchronously when addListener is called
    Mockito.doAnswer(inv -> {
      @SuppressWarnings("unchecked")
      GenericFutureListener<ChannelFuture> listener = inv.getArgument(0);
      listener.operationComplete(failedFutureMock);
      return failedFutureMock;
    }).when(failedFutureMock).addListener(Mockito.any(GenericFutureListener.class));

    InputStream inputStream =
        new ByteArrayInputStream("test data".getBytes(StandardCharsets.UTF_8));

    // When
    new NettyBufferWriter(contextMock).writeStream(inputStream, promiseMock);

    // Then: buffer should be fully released (refCnt = 0) with no leak
    Assertions.assertThat(buffer.refCnt()).isEqualTo(0);
  }
}
