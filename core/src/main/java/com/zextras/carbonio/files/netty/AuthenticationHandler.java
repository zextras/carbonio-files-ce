// SPDX-FileCopyrightText: 2022 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.netty;

import com.google.inject.Inject;
import com.zextras.carbonio.files.dal.dao.User;
import com.zextras.carbonio.files.dal.repositories.interfaces.UserRepository;
import com.zextras.carbonio.usermanagement.entities.UserId;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.cookie.Cookie;
import io.netty.handler.codec.http.cookie.ServerCookieDecoder;
import io.netty.util.AttributeKey;
import io.vavr.control.Try;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@ChannelHandler.Sharable
public class AuthenticationHandler extends SimpleChannelInboundHandler<HttpRequest> {

  private final UserRepository userRepository;

  @Inject
  public AuthenticationHandler(UserRepository userRepository) {
    this.userRepository = userRepository;
  }

  /**
   * Checks if the cookies exists and are valid, if so it fetches the User that made the request,
   * then it saves the cookies and the requester in the {@link ChannelHandlerContext} so they can be
   * used by other channels. Finally, it fires the http request in the next channel of the netty
   * pipeline.
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
      unAuthorizedResponse(context, httpRequest.protocolVersion(), "Missing Cookies http header");
      return;
    }

    String cookieString = headersRequest.get(HttpHeaderNames.COOKIE);

    //String cookieString = "ZM_AUTH_TOKEN=0_a9a59037a8bd650aaa1bf147a3ece5eecf6ee502_69643d33363a37646137666436652d393165302d343134372d616633342d6266393333316336363135393b6578703d31333a313636323633303538353736313b747970653d363a7a696d6272613b753d313a613b7469643d31303a313931303539393031333b637372663d313a313b";
    Set<Cookie> cookies = ServerCookieDecoder.STRICT.decode(cookieString);
    Optional<Try<UserId>> optTryUserId = cookies
      .stream()
      .filter(cookie -> cookie.name().equals("ZM_AUTH_TOKEN"))
      .findFirst()
      .map(cookie -> userRepository.validateToken(cookie.value()));

    if (optTryUserId.isPresent()) {
      optTryUserId
        .get()
        .onSuccess(id -> {
            User requester = userRepository
              .getUserById(cookieString, UUID.fromString(id.getUserId()))
              .orElse(new User("", "", "", ""));
            context.channel().attr(AttributeKey.valueOf("requester")).set(requester);
            context.channel().attr(AttributeKey.valueOf("cookies")).set(cookieString);
            context.fireChannelRead(httpRequest);
          }
        )
        .onFailure(failure -> unAuthorizedResponse(
          context,
          httpRequest.protocolVersion(),
          "Invalid ZM_AUTH_TOKEN")
        );
    } else {
      unAuthorizedResponse(
        context,
        httpRequest.protocolVersion(),
        "Error during the User fetching"
      );
    }
  }

  private void unAuthorizedResponse(
    ChannelHandlerContext context,
    HttpVersion httpVersion,
    String bodyResponse
  ) {
    context
      .writeAndFlush(new DefaultFullHttpResponse(
        httpVersion,
        HttpResponseStatus.UNAUTHORIZED,
        Unpooled.wrappedBuffer(bodyResponse.getBytes(StandardCharsets.UTF_8))
      ))
      .addListener(ChannelFutureListener.CLOSE);
  }
}
