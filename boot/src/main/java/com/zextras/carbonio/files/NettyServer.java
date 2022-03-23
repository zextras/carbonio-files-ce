// SPDX-FileCopyrightText: 2022 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.zextras.carbonio.files.netty.HttpRoutingHandler;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.HttpServerCodec;

@Singleton
public class NettyServer {

  private final HttpRoutingHandler httpRoutingHandler;

  @Inject
  public NettyServer(
    HttpRoutingHandler httpRoutingHandler
  ) {
    this.httpRoutingHandler = httpRoutingHandler;
  }


  public void start() throws InterruptedException {
    EventLoopGroup bossGroup = new NioEventLoopGroup();
    EventLoopGroup workerGroup = new NioEventLoopGroup();
    try {
      ServerBootstrap bootstrap = new ServerBootstrap();
      bootstrap.group(bossGroup, workerGroup)
        .channel(NioServerSocketChannel.class)
        .childHandler(
          new ChannelInitializer<>() {
            @Override
            protected void initChannel(Channel ch) {

              ChannelPipeline channelPipeline = ch.pipeline();
              channelPipeline.addLast(new HttpServerCodec());
              channelPipeline.addLast("router-handler", httpRoutingHandler);

            }
          })
        .option(ChannelOption.SO_BACKLOG, 128)
        .childOption(ChannelOption.SO_KEEPALIVE, true);

      ChannelFuture channelFuture = bootstrap.localAddress("127.78.0.2", 10000)
        .bind()
        .sync();
      channelFuture.channel()
        .closeFuture()
        .sync();
    } finally {
      workerGroup.shutdownGracefully();
      bossGroup.shutdownGracefully();
    }
  }
}
