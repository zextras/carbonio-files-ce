// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.acceptance.seam.impl;

import com.zextras.carbonio.files.Simulator;
import com.zextras.carbonio.files.TestUtils;
import com.zextras.carbonio.files.acceptance.seam.FilesTestApp;
import com.zextras.carbonio.files.acceptance.seam.Mocks;
import com.zextras.carbonio.files.acceptance.seam.TestDataAccess;
import com.zextras.carbonio.files.netty.HttpRoutingHandler;
import com.zextras.carbonio.files.utilities.http.HttpRequest;
import com.zextras.carbonio.files.utilities.http.HttpResponse;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.HttpServerCodec;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest.BodyPublisher;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * {@link FilesTestApp} implementation that boots the REAL Netty server on an ephemeral port
 * (transport Option B) and drives it over a genuine HTTP socket with {@link
 * java.net.http.HttpClient}. This is the transport whose shape transfers unchanged to the future
 * Quarkus impl: the acceptance-test bodies never learn which transport they got — they only see
 * the neutral {@link FilesTestApp}/{@link TestDataAccess}/{@link Mocks} facades.
 *
 * <p>It reuses the exact same {@link Simulator} wiring as {@link GuiceNettyFilesTestApp} (Guice
 * injector + Testcontainers + MockServer + in-process gRPC UM). The external-dependency fakes work
 * identically over a real socket because {@code MockFilesConfig}/system-properties point the app's
 * collaborators at the fakes regardless of how the request reaches the {@link HttpRoutingHandler}.
 *
 * <p>Selected via {@code -Dfiles.test.transport=http}; otherwise the default embedded
 * {@link GuiceNettyFilesTestApp} (EmbeddedChannel) is used. See {@link GuiceNettyFilesTestAppBuilder}.
 */
public class RealHttpFilesTestApp implements FilesTestApp {

  private final Simulator simulator;
  private final TestDataAccess testDataAccess;
  private final Mocks mocks;

  private final EventLoopGroup bossGroup;
  private final EventLoopGroup workerGroup;
  private final Channel serverChannel;
  private final HttpClient httpClient;
  private final int port;

  RealHttpFilesTestApp(Simulator simulator) {
    this.simulator = simulator;
    this.testDataAccess = new GuiceNettyTestDataAccess(simulator);
    this.mocks = new GuiceNettyMocks(simulator);

    // ---------------------------------------------------------------------------------------
    // Mirror of NettyServer's pipeline (boot module); keep in sync — only HttpServerCodec +
    // HttpRoutingHandler live here, per-route aggregators (HttpObjectAggregator/ChunkedWriteHandler)
    // are added INSIDE HttpRoutingHandler per matched endpoint. This is the small, top-level-only
    // drift risk the user accepted by implementing Option B as a test-only server rather than
    // relocating NettyServer into a shared module.
    //
    // HttpRoutingHandler is @Sharable (see the class annotation), exactly as production relies on:
    // NettyServer resolves it once from the injector and reuses the single instance across all
    // channels. We do the same here. Every request closes its channel after the response
    // (all controllers write with ChannelFutureListener.CLOSE / Connection: close), so there is no
    // HTTP keep-alive reuse and thus no per-request pipeline-mutation clash.
    // ---------------------------------------------------------------------------------------
    final HttpRoutingHandler httpRoutingHandler =
        simulator.getInjector().getInstance(HttpRoutingHandler.class);

    this.bossGroup = new NioEventLoopGroup(1);
    this.workerGroup = new NioEventLoopGroup();

    try {
      ServerBootstrap bootstrap = new ServerBootstrap();
      bootstrap
          .group(bossGroup, workerGroup)
          .channel(NioServerSocketChannel.class)
          .childHandler(
              new ChannelInitializer<Channel>() {
                @Override
                protected void initChannel(Channel ch) {
                  ChannelPipeline channelPipeline = ch.pipeline();
                  channelPipeline.addLast(new HttpServerCodec());
                  channelPipeline.addLast("router-handler", httpRoutingHandler);
                }
              })
          .option(ChannelOption.SO_BACKLOG, 128)
          .childOption(ChannelOption.SO_KEEPALIVE, true);

      // Bind on loopback, ephemeral port (0 → OS-assigned).
      this.serverChannel = bootstrap.bind("localhost", 0).sync().channel();
      this.port = ((InetSocketAddress) serverChannel.localAddress()).getPort();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      workerGroup.shutdownGracefully();
      bossGroup.shutdownGracefully();
      throw new IllegalStateException("Failed to bind the test Files server on an ephemeral port", e);
    }

    // Force HTTP/1.1: the Netty server speaks HTTP/1.1 only; the default HttpClient would attempt
    // an h2c upgrade the server does not understand.
    this.httpClient = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
  }

  @Override
  public HttpResponse send(HttpRequest request) {
    // Match TestUtils.sendRequest: the body is wrapped as {"query":"<body>"} (queryPayload).
    // All send() POSTs target the GraphQL endpoints, which carry this JSON body; GET requests
    // (downloads/previews/health) ignore the request body server-side, so we send them without a
    // body — this is behaviorally identical to the embedded path (whose wrapped GET body the
    // download pipeline discards) and avoids splitting HttpContent past the non-aggregated
    // download handlers over a real socket.
    final String wrappedBody = TestUtils.queryPayload(request.getBodyPayload().orElse(""));
    return exchange(request, wrappedBody);
  }

  @Override
  public HttpResponse sendForm(HttpRequest request) {
    // Match TestUtils.sendFormRequest: the pre-built application/x-www-form-urlencoded body is
    // forwarded verbatim (NO queryPayload wrapping).
    return exchange(request, request.getBodyPayload().orElse(""));
  }

  @Override
  public HttpResponse upload(HttpRequest request) {
    // Match TestUtils.sendUpload: raw bytes over the wire, custom headers forwarded as-is, NO
    // queryPayload wrapping. java.net.http.HttpClient computes Content-Length itself from the
    // BodyPublisher (Content-Length is a restricted header it will not let us set explicitly),
    // which mirrors the embedded transport's "always computed from the real bytes" behaviour.
    final byte[] body = request.getBinaryBody().orElse(new byte[0]);

    final java.net.http.HttpRequest.Builder builder =
        java.net.http.HttpRequest.newBuilder(URI.create("http://localhost:" + port + request.getEndpoint()))
            .timeout(Duration.ofSeconds(60));

    request
        .getHeaders()
        .ifPresent(headers -> headers.forEach(h -> builder.header(h.getKey(), h.getValue())));
    request.getCookie().ifPresent(cookie -> builder.header("Cookie", cookie));

    builder.method(request.getMethod(), BodyPublishers.ofByteArray(body));

    try {
      final java.net.http.HttpResponse<String> response =
          httpClient.send(builder.build(), BodyHandlers.ofString(StandardCharsets.UTF_8));
      return HttpResponse.of(response.statusCode(), flattenHeaders(response), response.body());
    } catch (Exception e) {
      throw new IllegalStateException(
          "Real-HTTP upload to " + request.getMethod() + " " + request.getEndpoint() + " failed", e);
    }
  }

  private HttpResponse exchange(HttpRequest request, String body) {
    final java.net.http.HttpRequest.Builder builder =
        java.net.http.HttpRequest.newBuilder(URI.create("http://localhost:" + port + request.getEndpoint()))
            .timeout(Duration.ofSeconds(60));

    request
        .getHeaders()
        .ifPresent(headers -> headers.forEach(h -> builder.header(h.getKey(), h.getValue())));
    request.getCookie().ifPresent(cookie -> builder.header("Cookie", cookie));

    final String method = request.getMethod();
    if ("GET".equalsIgnoreCase(method) || "HEAD".equalsIgnoreCase(method)) {
      builder.method(method, BodyPublishers.noBody());
    } else {
      final BodyPublisher publisher =
          BodyPublishers.ofString(body, StandardCharsets.UTF_8);
      builder.method(method, publisher);
    }

    try {
      final java.net.http.HttpResponse<String> response =
          httpClient.send(builder.build(), BodyHandlers.ofString(StandardCharsets.UTF_8));
      return HttpResponse.of(response.statusCode(), flattenHeaders(response), response.body());
    } catch (Exception e) {
      throw new IllegalStateException(
          "Real-HTTP request to " + request.getMethod() + " " + request.getEndpoint() + " failed", e);
    }
  }

  private static List<Map.Entry<String, String>> flattenHeaders(
      java.net.http.HttpResponse<String> response) {
    final List<Map.Entry<String, String>> entries = new ArrayList<>();
    response
        .headers()
        .map()
        .forEach((name, values) -> values.forEach(value -> entries.add(Map.entry(name, value))));
    return entries;
  }

  @Override
  public TestDataAccess backdoor() {
    return testDataAccess;
  }

  @Override
  public Mocks mocks() {
    return mocks;
  }

  @Override
  public void close() {
    try {
      if (serverChannel != null) {
        serverChannel.close().sync();
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    } finally {
      workerGroup.shutdownGracefully();
      bossGroup.shutdownGracefully();
      simulator.stopAll();
    }
  }
}
