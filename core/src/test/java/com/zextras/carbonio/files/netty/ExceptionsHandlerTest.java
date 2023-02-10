// SPDX-FileCopyrightText: 2023 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.netty;

import com.zextras.carbonio.files.exceptions.AuthenticationException;
import com.zextras.carbonio.files.exceptions.BadRequestException;
import com.zextras.carbonio.files.exceptions.InternalServerErrorException;
import com.zextras.carbonio.files.exceptions.NodeNotFoundException;
import com.zextras.carbonio.files.exceptions.RequestEntityTooLargeException;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import java.nio.charset.StandardCharsets;
import java.util.stream.Stream;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.testcontainers.shaded.com.fasterxml.jackson.core.JsonParseException;

public class ExceptionsHandlerTest {

  private static Stream<Arguments> generateExceptions() {
    return Stream.of(
      Arguments.of(
        new RequestEntityTooLargeException(""),
        HttpResponseStatus.REQUEST_ENTITY_TOO_LARGE
      ),
      Arguments.of(new JsonParseException(null, ""), HttpResponseStatus.INTERNAL_SERVER_ERROR),
      Arguments.of(new InternalServerErrorException(""), HttpResponseStatus.INTERNAL_SERVER_ERROR),
      Arguments.of(new BadRequestException(), HttpResponseStatus.BAD_REQUEST),
      Arguments.of(new NodeNotFoundException(), HttpResponseStatus.NOT_FOUND),
      Arguments.of(new RuntimeException(), HttpResponseStatus.INTERNAL_SERVER_ERROR)
    );
  }

  private ChannelHandlerContext channelHandlerContextMock;
  private ChannelFuture         channelFutureMock;
  private ExceptionsHandler     exceptionsHandler;

  @BeforeEach
  void initTest() {
    channelHandlerContextMock = Mockito.mock(ChannelHandlerContext.class);
    channelFutureMock = Mockito.mock(ChannelFuture.class);
    exceptionsHandler = new ExceptionsHandler();
    Mockito
      .when(channelHandlerContextMock.writeAndFlush(Mockito.any(DefaultFullHttpResponse.class)))
      .thenReturn(channelFutureMock);
  }

  @ParameterizedTest
  @MethodSource("generateExceptions")
  public void givenAThrowableExceptionsHandlerShouldReturnSpecificHttpResponseStatus(
    Throwable throwable, HttpResponseStatus httpResponseStatus
  ) {
    // Given
    ArgumentCaptor<DefaultFullHttpResponse> captorHttpResponse = ArgumentCaptor.forClass(
      DefaultFullHttpResponse.class
    );

    // When
    exceptionsHandler.exceptionCaught(channelHandlerContextMock, throwable);

    // Then
    Mockito
      .verify(channelHandlerContextMock, Mockito.times(1))
      .writeAndFlush(captorHttpResponse.capture());
    Mockito
      .verify(channelFutureMock, Mockito.times(1))
      .addListener(ChannelFutureListener.CLOSE);

    DefaultFullHttpResponse httpResponse = captorHttpResponse.getValue();
    Assertions
      .assertThat(httpResponse.protocolVersion())
      .isEqualTo(HttpVersion.HTTP_1_1);
    Assertions
      .assertThat(httpResponse.status())
      .isEqualTo(httpResponseStatus);
    Assertions
      .assertThat(httpResponse.content().toString(StandardCharsets.UTF_8))
      .isEqualTo(httpResponseStatus.toString());
  }


  @Test
  public void givenAnAuthenticationExceptionExceptionsHandlerShouldReturn401HttpResponse() {
    // Given
    AuthenticationException exception = new AuthenticationException("Missing cookie");
    ArgumentCaptor<DefaultFullHttpResponse> captorHttpResponse = ArgumentCaptor.forClass(
      DefaultFullHttpResponse.class
    );

    // When
    exceptionsHandler.exceptionCaught(channelHandlerContextMock, exception);

    // Then
    Mockito
      .verify(channelHandlerContextMock, Mockito.times(1))
      .writeAndFlush(captorHttpResponse.capture());
    Mockito
      .verify(channelFutureMock, Mockito.times(1))
      .addListener(ChannelFutureListener.CLOSE);

    DefaultFullHttpResponse httpResponse = captorHttpResponse.getValue();
    Assertions
      .assertThat(httpResponse.protocolVersion())
      .isEqualTo(HttpVersion.HTTP_1_1);
    Assertions
      .assertThat(httpResponse.status())
      .isEqualTo(HttpResponseStatus.UNAUTHORIZED);
    Assertions
      .assertThat(httpResponse.content().toString(StandardCharsets.UTF_8))
      .isEqualTo("Missing cookie");
  }
}
