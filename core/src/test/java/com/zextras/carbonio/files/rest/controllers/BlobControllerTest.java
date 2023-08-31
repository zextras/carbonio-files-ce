// SPDX-FileCopyrightText: 2023 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.rest.controllers;

import com.zextras.carbonio.files.dal.dao.User;
import com.zextras.carbonio.files.rest.services.BlobService;
import com.zextras.carbonio.files.rest.types.BlobResponse;
import com.zextras.carbonio.files.tasks.PrometheusService;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.util.AttributeKey;
import java.io.ByteArrayInputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.stream.Stream;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

public class BlobControllerTest {

  private BlobService blobServiceMock;
  private PrometheusService prometheusServiceMock;

  @BeforeEach
  void init() {
    blobServiceMock = Mockito.mock(BlobService.class);
    prometheusServiceMock = Mockito.mock(PrometheusService.class);
  }

  static Stream<Arguments> downloadURLProvider() {
    return Stream.of(
      Arguments.of("/download/53d6fd97-a933-409f-b9f3-156690a64476", null),
      Arguments.of("/download/53d6fd97-a933-409f-b9f3-156690a64476/", null),
      Arguments.of("/download/53d6fd97-a933-409f-b9f3-156690a64476/1", 1),
      Arguments.of("/download/53d6fd97-a933-409f-b9f3-156690a64476/1/", 1)
    );
  }

  @ParameterizedTest
  @MethodSource("downloadURLProvider")
  void downloadBlob(String uri, Integer version) {
    // Given
    User userMock = Mockito.mock(User.class);

    ChannelHandlerContext contextMock = Mockito.mock(
      ChannelHandlerContext.class,
      Mockito.RETURNS_DEEP_STUBS
    );
    Mockito
      .when(contextMock.channel().attr(AttributeKey.valueOf("requester")).get())
      .thenReturn(userMock);

    HttpRequest httpRequestMock = Mockito.mock(HttpRequest.class);
    Mockito.when(httpRequestMock.uri()).thenReturn(uri);

    BlobResponse blobResponseMock = Mockito.mock(BlobResponse.class);
    Mockito
      .when(blobResponseMock.getBlobStream())
      .thenReturn(new ByteArrayInputStream("test".getBytes(StandardCharsets.UTF_8)));
    Mockito.when(blobResponseMock.getMimeType()).thenReturn("text/plain");
    Mockito.when(blobResponseMock.getSize()).thenReturn(Long.valueOf("test".length()));
    Mockito.when(blobResponseMock.getFilename()).thenReturn("document.txt");

    Mockito
      .when(blobServiceMock.downloadFileById(
        "53d6fd97-a933-409f-b9f3-156690a64476",
        version,
        userMock
      ))
      .thenReturn(Optional.of(blobResponseMock));

    BlobController blobController = new BlobController(blobServiceMock, prometheusServiceMock);

    // When
    blobController.channelRead0(contextMock, httpRequestMock);

    // Then
    ArgumentCaptor<DefaultHttpResponse> httpResponseCaptor =
      ArgumentCaptor.forClass(DefaultHttpResponse.class);

    Mockito.verify(contextMock, Mockito.times(1)).write(httpResponseCaptor.capture());

    DefaultHttpResponse httpResponse = httpResponseCaptor.getValue();
    Assertions.assertThat(httpResponse.status()).isEqualTo(HttpResponseStatus.OK);
    Assertions.assertThat(httpResponse.protocolVersion()).isEqualTo(HttpVersion.HTTP_1_1);
    Assertions
      .assertThat(httpResponse.headers().get(HttpHeaderNames.CONNECTION))
      .isEqualTo(HttpHeaderValues.CLOSE.toString());
    Assertions
      .assertThat(httpResponse.headers().get(HttpHeaderNames.CONTENT_LENGTH))
      .isEqualTo("4");
    Assertions
      .assertThat(httpResponse.headers().get(HttpHeaderNames.CONTENT_TYPE))
      .isEqualTo("text/plain");
    Assertions
      .assertThat(httpResponse.headers().get(HttpHeaderNames.CONTENT_DISPOSITION))
      .isEqualTo(
        "attachment; filename*=UTF-8''" + URLEncoder.encode("document.txt", StandardCharsets.UTF_8)
      );

    Mockito.verifyNoInteractions(contextMock.fireExceptionCaught(Mockito.any(Throwable.class)));
  }
}
