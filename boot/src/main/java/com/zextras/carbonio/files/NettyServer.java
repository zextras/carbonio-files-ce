// SPDX-FileCopyrightText: 2022 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.zextras.carbonio.files.config.FilesConfig;
import com.zextras.carbonio.files.netty.HttpRoutingHandler;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.HttpServerCodec;
import java.util.Properties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Singleton
public class NettyServer {

  private static final Logger logger = LoggerFactory.getLogger(NettyServer.class);

  private final FilesConfig        filesConfig;
  private final HttpRoutingHandler httpRoutingHandler;

  @Inject
  public NettyServer(
    FilesConfig filesConfig,
    HttpRoutingHandler httpRoutingHandler
  ) {
    this.filesConfig = filesConfig;
    this.httpRoutingHandler = httpRoutingHandler;
  }

  public void start() {
    Properties config = filesConfig.getProperties();
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

      bootstrap
        .localAddress(
          config.getProperty(Files.Config.Service.URL, "127.78.0.2"),
          Integer.parseInt(config.getProperty(Files.Config.Service.PORT, "10000"))
        )
        .bind()
        .sync()
        .channel()
        .closeFuture()
        .sync();

    } catch (InterruptedException exception) {
      logger.error("Service stopped unexpectedly: " + exception.getMessage());
    } finally {
      workerGroup.shutdownGracefully();
      bossGroup.shutdownGracefully();
    }
  }
}
