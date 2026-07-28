// SPDX-FileCopyrightText: 2023 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.netty;

import com.zextras.carbonio.files.Constants;
import com.zextras.carbonio.files.dal.repositories.interfaces.UserRepository;
import com.zextras.carbonio.files.exceptions.AuthenticationException;
import com.zextras.carbonio.files.exceptions.ForbiddenException;
import com.zextras.carbonio.files.dal.dao.UserMyself;
import com.zextras.carbonio.files.dal.dao.UserStatus;
import com.zextras.carbonio.files.dal.dao.UserType;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.util.Attribute;
import io.netty.util.AttributeKey;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.util.Map;
import java.util.Optional;

class AuthenticationHandlerTest {

  private UserRepository userRepositoryMock;
  private ChannelHandlerContext channelHandlerContextMock;
  private Channel channelMock;
  private HttpRequest httpRequestMock;
  private HttpHeaders httpHeadersMock;

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
   * An unmanaged cookie is a cookie different from {@link Constants.API.Headers#COOKIE_ZM_AUTH_TOKEN}.
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
    UserMyself userMock = Mockito.mock(UserMyself.class);
    Attribute<Object> requesterChannelAttributeMock = Mockito.mock(Attribute.class);
    Attribute<Object> cookiesChannelAttributeMock = Mockito.mock(Attribute.class);

    Mockito.when(userMock.getStatus()).thenReturn(UserStatus.ACTIVE);
    Mockito.when(userMock.getType()).thenReturn(UserType.INTERNAL);
    Mockito.when(userMock.getCarbonioAttributes()).thenReturn(Map.of("carbonioFeatureFilesEnabled", "TRUE"));
    Mockito.when(httpHeadersMock.contains(HttpHeaderNames.COOKIE)).thenReturn(true);
    Mockito
        .when(httpHeadersMock.get(HttpHeaderNames.COOKIE))
        .thenReturn("IRIS=ui; ZM_AUTH_TOKEN=valid-token");
    Mockito
        .when(userRepositoryMock.getUserMyselfByCookieNotCached("IRIS=ui; ZM_AUTH_TOKEN=valid-token"))
        .thenReturn(Optional.of(userMock));
    Mockito
        .when(channelMock.attr(AttributeKey.valueOf("requester")))
        .thenReturn(requesterChannelAttributeMock);
    Mockito
        .when(channelMock.attr(AttributeKey.valueOf("cookies")))
        .thenReturn(cookiesChannelAttributeMock);

    ArgumentCaptor<UserMyself> captorUserInChannelContext = ArgumentCaptor.forClass(UserMyself.class);
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
  void givenARequestWithValidZM_AUTH_TOKENAndUserMaintenanceAuthenticationHandlerShouldThrow() {
    // Given
    UserMyself userMock = Mockito.mock(UserMyself.class);
    Attribute<Object> requesterChannelAttributeMock = Mockito.mock(Attribute.class);
    Attribute<Object> cookiesChannelAttributeMock = Mockito.mock(Attribute.class);

    Mockito.when(userMock.getStatus()).thenReturn(UserStatus.MAINTENANCE);
    Mockito.when(userMock.getType()).thenReturn(UserType.INTERNAL);
    Mockito.when(httpHeadersMock.contains(HttpHeaderNames.COOKIE)).thenReturn(true);
    Mockito
        .when(httpHeadersMock.get(HttpHeaderNames.COOKIE))
        .thenReturn("IRIS=ui; ZM_AUTH_TOKEN=valid-token");
    Mockito
        .when(userRepositoryMock.getUserMyselfByCookieNotCached("IRIS=ui; ZM_AUTH_TOKEN=valid-token"))
        .thenReturn(Optional.of(userMock));
    Mockito
        .when(channelMock.attr(AttributeKey.valueOf("requester")))
        .thenReturn(requesterChannelAttributeMock);
    Mockito
        .when(channelMock.attr(AttributeKey.valueOf("cookies")))
        .thenReturn(cookiesChannelAttributeMock);

    ArgumentCaptor<ForbiddenException> captorException = ArgumentCaptor.forClass(
        ForbiddenException.class);

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
        .isEqualTo("Failed to authorize request /test/: User is not active");
  }

  @Test
  void givenARequestWithInvalidZM_AUTH_TOKENAuthenticationHandlerShouldThrowException() {
    // Given
    Mockito.when(httpHeadersMock.contains(HttpHeaderNames.COOKIE)).thenReturn(true);
    Mockito
        .when(httpHeadersMock.get(HttpHeaderNames.COOKIE))
        .thenReturn("IRIS=ui; ZM_AUTH_TOKEN=invalid-token");

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
        .isEqualTo("Failed to authenticate request /test/: Unable to find requested user");
  }

  @Test
  void givenARequestWithValidZM_AUTH_TOKENAndNonExistentUserAuthenticationHandlerShouldThrowException() {
    // Given
    Mockito.when(httpHeadersMock.contains(HttpHeaderNames.COOKIE)).thenReturn(true);
    Mockito
        .when(httpHeadersMock.get(HttpHeaderNames.COOKIE))
        .thenReturn("IRIS=ui; ZM_AUTH_TOKEN=valid-token");
    Mockito
        .when(userRepositoryMock.getUserMyselfByCookieNotCached("IRIS=ui; ZM_AUTH_TOKEN=valid-token"))
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
            "Failed to authenticate request /test/: Unable to find requested user");
  }

  @Test
  void givenARequestWithValidZM_AUTH_TOKENAndGuestUserAuthenticationHandlerShouldThrow() {
    // Given
    UserMyself userMock = Mockito.mock(UserMyself.class);
    Attribute<Object> requesterChannelAttributeMock = Mockito.mock(Attribute.class);
    Attribute<Object> cookiesChannelAttributeMock = Mockito.mock(Attribute.class);

    Mockito.when(userMock.getStatus()).thenReturn(UserStatus.ACTIVE);
    Mockito.when(userMock.getType()).thenReturn(UserType.GUEST);
    Mockito.when(httpHeadersMock.contains(HttpHeaderNames.COOKIE)).thenReturn(true);
    Mockito
        .when(httpHeadersMock.get(HttpHeaderNames.COOKIE))
        .thenReturn("IRIS=ui; ZM_AUTH_TOKEN=guest-token");
    Mockito
        .when(userRepositoryMock.getUserMyselfByCookieNotCached("IRIS=ui; ZM_AUTH_TOKEN=guest-token"))
        .thenReturn(Optional.of(userMock));
    Mockito
        .when(channelMock.attr(AttributeKey.valueOf("requester")))
        .thenReturn(requesterChannelAttributeMock);
    Mockito
        .when(channelMock.attr(AttributeKey.valueOf("cookies")))
        .thenReturn(cookiesChannelAttributeMock);

    ArgumentCaptor<ForbiddenException> captorException = ArgumentCaptor.forClass(
        ForbiddenException.class);

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
        .isEqualTo("Failed to authorize request /test/: User is not internal");
  }

  @Test
  void givenARequestWithValidZM_AUTH_TOKENAndFilesFeatureDisabledAuthenticationHandlerShouldThrow() {
    // Given
    UserMyself userMock = Mockito.mock(UserMyself.class);
    Attribute<Object> requesterChannelAttributeMock = Mockito.mock(Attribute.class);
    Attribute<Object> cookiesChannelAttributeMock = Mockito.mock(Attribute.class);

    Mockito.when(userMock.getStatus()).thenReturn(UserStatus.ACTIVE);
    Mockito.when(userMock.getType()).thenReturn(UserType.INTERNAL);
    Mockito.when(userMock.getCarbonioAttributes())
        .thenReturn(Map.of("carbonioFeatureFilesEnabled", "FALSE"));
    Mockito.when(httpHeadersMock.contains(HttpHeaderNames.COOKIE)).thenReturn(true);
    Mockito
        .when(httpHeadersMock.get(HttpHeaderNames.COOKIE))
        .thenReturn("IRIS=ui; ZM_AUTH_TOKEN=no-feature-token");
    Mockito
        .when(userRepositoryMock.getUserMyselfByCookieNotCached("IRIS=ui; ZM_AUTH_TOKEN=no-feature-token"))
        .thenReturn(Optional.of(userMock));
    Mockito
        .when(channelMock.attr(AttributeKey.valueOf("requester")))
        .thenReturn(requesterChannelAttributeMock);
    Mockito
        .when(channelMock.attr(AttributeKey.valueOf("cookies")))
        .thenReturn(cookiesChannelAttributeMock);

    ArgumentCaptor<ForbiddenException> captorException = ArgumentCaptor.forClass(
        ForbiddenException.class);

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
        .isEqualTo("Failed to authorize request /test/: Files feature is not enabled for user");
  }

  @Test
  void givenSuccessfulAuth_fireChannelReadShouldRetainTheRequest() {
    // Given
    DefaultFullHttpRequest realRequest =
        new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/test/");
    realRequest.headers().add(HttpHeaderNames.COOKIE, "IRIS=ui; ZM_AUTH_TOKEN=valid-token");

    UserMyself userMock = Mockito.mock(UserMyself.class);
    Attribute<Object> requesterAttr = Mockito.mock(Attribute.class);
    Attribute<Object> cookiesAttr = Mockito.mock(Attribute.class);

    Mockito.when(userMock.getStatus()).thenReturn(UserStatus.ACTIVE);
    Mockito.when(userMock.getType()).thenReturn(UserType.INTERNAL);
    Mockito.when(userMock.getCarbonioAttributes())
        .thenReturn(Map.of("carbonioFeatureFilesEnabled", "TRUE"));
    Mockito.when(
            userRepositoryMock.getUserMyselfByCookieNotCached("IRIS=ui; ZM_AUTH_TOKEN=valid-token"))
        .thenReturn(Optional.of(userMock));
    Mockito.when(channelMock.attr(AttributeKey.valueOf("requester"))).thenReturn(requesterAttr);
    Mockito.when(channelMock.attr(AttributeKey.valueOf("cookies"))).thenReturn(cookiesAttr);

    AuthenticationHandler handler = new AuthenticationHandler(userRepositoryMock);

    // When
    Assertions.assertThat(realRequest.refCnt()).isEqualTo(1);
    handler.channelRead0(channelHandlerContextMock, realRequest);

    // Then: refCnt must be 2 — proves ReferenceCountUtil.retain() was called
    Assertions.assertThat(realRequest.refCnt()).isEqualTo(2);
    Mockito.verify(channelHandlerContextMock).fireChannelRead(realRequest);

    realRequest.release(2);
  }

  @Test
  void givenFailedAuth_requestShouldNotBeRetained() {
    // Given
    DefaultFullHttpRequest realRequest =
        new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/test/");
    realRequest.headers().add(HttpHeaderNames.COOKIE, "IRIS=ui; ZM_AUTH_TOKEN=invalid-token");

    AuthenticationHandler handler = new AuthenticationHandler(userRepositoryMock);

    // When
    Assertions.assertThat(realRequest.refCnt()).isEqualTo(1);
    handler.channelRead0(channelHandlerContextMock, realRequest);

    // Then: refCnt stays at 1 — no accidental retain on failure path
    Assertions.assertThat(realRequest.refCnt()).isEqualTo(1);
    Mockito.verify(channelHandlerContextMock, Mockito.never()).fireChannelRead(Mockito.any());

    realRequest.release();
  }
}
