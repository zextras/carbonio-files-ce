// SPDX-FileCopyrightText: 2023 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.netty;

import com.zextras.carbonio.files.Files;
import com.zextras.carbonio.files.dal.dao.User;
import com.zextras.carbonio.files.dal.repositories.interfaces.UserRepository;
import com.zextras.carbonio.files.exceptions.AuthenticationException;
import com.zextras.carbonio.usermanagement.entities.UserId;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.util.Attribute;
import io.netty.util.AttributeKey;
import io.vavr.control.Try;
import java.util.Optional;
import java.util.UUID;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

class AuthenticationHandlerTest {

  private UserRepository        userRepositoryMock;
  private ChannelHandlerContext channelHandlerContextMock;
  private Channel               channelMock;
  private HttpRequest           httpRequestMock;
  private HttpHeaders           httpHeadersMock;

  @BeforeEach
  void initTest() {
    userRepositoryMock = Mockito.mock(UserRepository.class);
    channelHandlerContextMock = Mockito.mock(ChannelHandlerContext.class);
    channelMock = Mockito.mock(Channel.class);
    httpRequestMock = Mockito.mock(HttpRequest.class);
    httpHeadersMock = Mockito.mock(HttpHeaders.class);

    Mockito.when(channelHandlerContextMock.channel()).thenReturn(channelMock);
    Mockito.when(httpRequestMock.headers()).thenReturn(httpHeadersMock);
    Mockito.when(httpRequestMock.uri()).thenReturn("/test/");
  }


  @Test
  void givenARequestWithoutCookiesAuthenticationHandlerShouldThrowAuthenticationException() {
    // Given
    Mockito.when(httpHeadersMock.contains(HttpHeaderNames.COOKIE)).thenReturn(false);
    ArgumentCaptor<AuthenticationException> captorException =
      ArgumentCaptor.forClass(AuthenticationException.class);

    AuthenticationHandler authenticationHandler = new AuthenticationHandler(userRepositoryMock);

    // When
    authenticationHandler.channelRead0(channelHandlerContextMock, httpRequestMock);

    // Then
    Mockito
      .verify(channelHandlerContextMock, Mockito.times(0))
      .fireChannelRead(httpRequestMock);
    Mockito
      .verify(channelHandlerContextMock, Mockito.times(1))
      .fireExceptionCaught(captorException.capture());

    Assertions
      .assertThat(captorException.getValue().getMessage())
      .isEqualTo("Failed to authenticate request /test/: Missing cookies");
  }

  /**
   * An unmanaged cookie is a cookie different from {@link Files.API.Headers#COOKIE_ZM_AUTH_TOKEN}.
   * For example a ZM_AUTH_TOKEN is a cookie that Files are not able to validate (unmanaged). In
   * this scenario Files returns a "Missing cookie" error message because the client does not use an
   * acceptable type of cookie.
   */
  @Test
  void givenARequestWithUnmanagedCookieAuthenticationHandlerShouldThrowAuthenticationException() {
    // Given
    Mockito.when(httpHeadersMock.contains(HttpHeaderNames.COOKIE)).thenReturn(true);
    Mockito
      .when(httpHeadersMock.get(HttpHeaderNames.COOKIE))
      .thenReturn("IRIS=ui, UNMANAGED_TOKEN=fake");
    ArgumentCaptor<AuthenticationException> captorException =
      ArgumentCaptor.forClass(AuthenticationException.class);

    AuthenticationHandler authenticationHandler = new AuthenticationHandler(userRepositoryMock);

    // When
    authenticationHandler.channelRead0(channelHandlerContextMock, httpRequestMock);

    // Then
    Mockito
      .verify(channelHandlerContextMock, Mockito.times(0))
      .fireChannelRead(httpRequestMock);
    Mockito
      .verify(channelHandlerContextMock, Mockito.times(1))
      .fireExceptionCaught(captorException.capture());

    Assertions
      .assertThat(captorException.getValue().getMessage())
      .isEqualTo("Failed to authenticate request /test/: Missing cookies");
  }

  @Test
  void givenARequestWithValidZM_AUTH_TOKENAuthenticationHandlerShouldAuthenticateTheRequestCorrectly() {
    // Given
    User userMock = Mockito.mock(User.class);
    Attribute<Object> requesterChannelAttributeMock = Mockito.mock(Attribute.class);
    Attribute<Object> cookiesChannelAttributeMock = Mockito.mock(Attribute.class);

    Mockito.when(httpHeadersMock.contains(HttpHeaderNames.COOKIE)).thenReturn(true);
    Mockito
      .when(httpHeadersMock.get(HttpHeaderNames.COOKIE))
      .thenReturn("IRIS=ui; ZM_AUTH_TOKEN=valid-token");
    Mockito
      .when(userRepositoryMock.validateToken("valid-token"))
      .thenReturn(Try.success(new UserId("6c594bb9-f8c7-424f-9320-7bf72daae3e7")));
    Mockito
      .when(userRepositoryMock.getUserById("IRIS=ui; ZM_AUTH_TOKEN=valid-token",
        "6c594bb9-f8c7-424f-9320-7bf72daae3e7"))
      .thenReturn(Optional.of(userMock));
    Mockito
      .when(channelMock.attr(AttributeKey.valueOf("requester")))
      .thenReturn(requesterChannelAttributeMock);
    Mockito
      .when(channelMock.attr(AttributeKey.valueOf("cookies")))
      .thenReturn(cookiesChannelAttributeMock);

    ArgumentCaptor<User> captorUserInChannelContext = ArgumentCaptor.forClass(User.class);
    ArgumentCaptor<String> captorCookieInChannelContext = ArgumentCaptor.forClass(String.class);

    AuthenticationHandler authenticationHandler = new AuthenticationHandler(userRepositoryMock);

    // When
    authenticationHandler.channelRead0(channelHandlerContextMock, httpRequestMock);

    // Then
    Mockito
      .verify(channelHandlerContextMock, Mockito.times(1))
      .fireChannelRead(httpRequestMock);
    Mockito
      .verify(requesterChannelAttributeMock, Mockito.times(1))
      .set(captorUserInChannelContext.capture());
    Mockito
      .verify(cookiesChannelAttributeMock, Mockito.times(1))
      .set(captorCookieInChannelContext.capture());

    Assertions
      .assertThat(captorUserInChannelContext.getValue())
      .isEqualTo(userMock);
    Assertions
      .assertThat(captorCookieInChannelContext.getValue())
      .isEqualTo("IRIS=ui; ZM_AUTH_TOKEN=valid-token");
  }

  @Test
  void givenARequestWithInvalidZM_AUTH_TOKENAuthenticationHandlerShouldThrowException() {
    // Given
    Mockito.when(httpHeadersMock.contains(HttpHeaderNames.COOKIE)).thenReturn(true);
    Mockito
      .when(httpHeadersMock.get(HttpHeaderNames.COOKIE))
      .thenReturn("IRIS=ui; ZM_AUTH_TOKEN=invalid-token");
    Mockito
      .when(userRepositoryMock.validateToken("invalid-token"))
      .thenReturn(Try.failure(new Throwable()));

    ArgumentCaptor<AuthenticationException> captorException = ArgumentCaptor.forClass(
      AuthenticationException.class);

    AuthenticationHandler authenticationHandler = new AuthenticationHandler(userRepositoryMock);

    // When
    authenticationHandler.channelRead0(channelHandlerContextMock, httpRequestMock);

    // Then
    Mockito
      .verify(channelHandlerContextMock, Mockito.times(0))
      .fireChannelRead(httpRequestMock);
    Mockito
      .verify(channelHandlerContextMock, Mockito.times(1))
      .fireExceptionCaught(captorException.capture());

    Assertions
      .assertThat(captorException.getValue().getMessage())
      .isEqualTo("Failed to authenticate request /test/: Invalid ZM_AUTH_TOKEN");
  }

  @Test
  void givenARequestWithValidZM_AUTH_TOKENAndNonExistentUserAuthenticationHandlerShouldThrowException() {
    // Given
    Mockito.when(httpHeadersMock.contains(HttpHeaderNames.COOKIE)).thenReturn(true);
    Mockito
      .when(httpHeadersMock.get(HttpHeaderNames.COOKIE))
      .thenReturn("IRIS=ui; ZM_AUTH_TOKEN=valid-token");
    Mockito
      .when(userRepositoryMock.validateToken("valid-token"))
      .thenReturn(Try.success(new UserId("6c594bb9-f8c7-424f-9320-7bf72daae3e7")));
    Mockito
      .when(userRepositoryMock.getUserById("IRIS=ui; ZM_AUTH_TOKEN=valid-token",
        "6c594bb9-f8c7-424f-9320-7bf72daae3e7"))
      .thenReturn(Optional.empty());

    ArgumentCaptor<AuthenticationException> captorException = ArgumentCaptor.forClass(
      AuthenticationException.class);

    AuthenticationHandler authenticationHandler = new AuthenticationHandler(userRepositoryMock);

    // When
    authenticationHandler.channelRead0(channelHandlerContextMock, httpRequestMock);

    // Then
    Mockito
      .verify(channelHandlerContextMock, Mockito.times(0))
      .fireChannelRead(httpRequestMock);
    Mockito
      .verify(channelHandlerContextMock, Mockito.times(1))
      .fireExceptionCaught(captorException.capture());

    Assertions
      .assertThat(captorException.getValue().getMessage())
      .isEqualTo(
        "Failed to authenticate request /test/: Unable to find user with id 6c594bb9-f8c7-424f-9320-7bf72daae3e7");
  }
}
