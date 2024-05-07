// SPDX-FileCopyrightText: 2022 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.netty;

import com.google.inject.Inject;
import com.zextras.carbonio.files.Files;
import com.zextras.carbonio.files.dal.repositories.interfaces.UserRepository;
import com.zextras.carbonio.files.exceptions.AuthenticationException;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.cookie.Cookie;
import io.netty.handler.codec.http.cookie.ServerCookieDecoder;
import io.netty.util.AttributeKey;
import java.util.Optional;
import java.util.Set;

@ChannelHandler.Sharable
public class AuthenticationHandler extends SimpleChannelInboundHandler<HttpRequest> {

  private static final String UNAUTHORIZED_ERROR_MESSAGE = "Failed to authenticate request %s: %s";

  private final UserRepository userRepository;

  @Inject
  public AuthenticationHandler(UserRepository userRepository) {
    this.userRepository = userRepository;
  }

  /**
   * Authenticates the requests via cookies. It can check one type of cookie:
   * <ul>
   *   <li>{@link Files.API.Headers#COOKIE_ZM_AUTH_TOKEN}</li>
   * </ul>
   * If the cookie is valid, it fetches the User that made the request, saves some info in the
   * {@link ChannelHandlerContext} so they can be used by other channels and fires the http request
   * in the next channel of the netty pipeline.
   *
   * @param context is a {@link ChannelHandlerContext} representing the context of this channel.
   * @param httpRequest is a {@link HttpRequest} representing the request in input.
   */
  @Override
  protected void channelRead0(
    ChannelHandlerContext context,
    HttpRequest httpRequest
  ) {
    HttpHeaders headersRequest = httpRequest.headers();
    if (!headersRequest.contains((HttpHeaderNames.COOKIE))) {
      context.fireExceptionCaught(new AuthenticationException(String.format(
        UNAUTHORIZED_ERROR_MESSAGE,
        httpRequest.uri(),
        "Missing cookies"
      )));
      return;
    }

    String cookiesString = headersRequest.get(HttpHeaderNames.COOKIE);
    Set<Cookie> cookies = ServerCookieDecoder.STRICT.decode(cookiesString);
    Optional<Cookie> optCookie = cookies
      .stream()
      .filter(cookie ->
        cookie.name().equals(Files.API.Headers.COOKIE_ZM_AUTH_TOKEN)
      )
      .findFirst();

    if (optCookie.isPresent()) {
      switch (optCookie.get().name()) {
        case Files.API.Headers.COOKIE_ZM_AUTH_TOKEN:
          validateAuthTokenAndFetchAccount(
            context,
            httpRequest,
            cookiesString,
            optCookie.get().value()
          );
          break;
        default: // The execution will never reach this point
          break;
      }
    } else {
      context.fireExceptionCaught(new AuthenticationException(String.format(
        UNAUTHORIZED_ERROR_MESSAGE,
        httpRequest.uri(),
        "Missing cookies"
      )));
    }
  }

  /**
   * This method allows to:
   * <ul>
   *   <li>Validate the {@link Files.API.Headers#COOKIE_ZM_AUTH_TOKEN}</li>
   *   <li>Fetch the User that made the requests (if the token is valid)</li>
   *   <li>Save the cookies and the requester in the {@link ChannelHandlerContext} so they can be
   *   used by other channels</li>
   *   <li>Fire the http request in the next channel of the netty pipeline</li>
   * </ul>
   *
   * @param context is a {@link ChannelHandlerContext} representing the context of this channel.
   * @param httpRequest is a {@link HttpRequest} representing the request in input.
   * @param cookies is a {@link String} representing all the received cookies
   * @param zmAuthToken is a {@link String} representing the ZM_AUTH_TOKEN
   */
  private void validateAuthTokenAndFetchAccount(
    ChannelHandlerContext context,
    HttpRequest httpRequest,
    String cookies,
    String zmAuthToken
  ) {
    userRepository
      .validateToken(zmAuthToken)
      .onSuccess(userId -> {
          userRepository
            .getUserMyselfByCookie(cookies, userId.getUserId())
            .ifPresentOrElse(
              user -> {
                context.channel()
                  .attr(AttributeKey.valueOf(Files.API.ContextAttribute.REQUESTER))
                  .set(user);
                context.channel()
                  .attr(AttributeKey.valueOf(Files.API.ContextAttribute.COOKIES))
                  .set(cookies);

                context.fireChannelRead(httpRequest);
              },
              () ->
                context.fireExceptionCaught(new AuthenticationException(String.format(
                  UNAUTHORIZED_ERROR_MESSAGE,
                  httpRequest.uri(),
                  "Unable to find user with id " + userId.getUserId()
                )))
            );
        }
      )
      .onFailure(failure ->
        context.fireExceptionCaught(new AuthenticationException(String.format(
          UNAUTHORIZED_ERROR_MESSAGE,
          httpRequest.uri(),
          "Invalid ZM_AUTH_TOKEN"
        )))
      );
  }
}
