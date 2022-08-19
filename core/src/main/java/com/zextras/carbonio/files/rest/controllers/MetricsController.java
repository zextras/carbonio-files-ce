// SPDX-FileCopyrightText: 2022 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.rest.controllers;

import com.google.inject.Inject;
import com.zextras.carbonio.files.Files.API.Endpoints;
import com.zextras.carbonio.files.exceptions.InternalServerErrorException;
import com.zextras.carbonio.files.tasks.PrometheusService;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponseStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;

@ChannelHandler.Sharable
public class MetricsController extends SimpleChannelInboundHandler<HttpRequest> {

  private final static Logger logger = LoggerFactory.getLogger(MetricsController.class);

  private final PrometheusService prometheusService;

  @Inject
  public MetricsController(PrometheusService prometheusService) {
    this.prometheusService = prometheusService;
  }

  @Override
  protected void channelRead0(
    ChannelHandlerContext context,
    HttpRequest httpRequest
  ) {

    String uriRequest = httpRequest.uri();
    Matcher metricsMatcher = Endpoints.METRICS.matcher(uriRequest);

    try {
      if (metricsMatcher.find()) {
        metrics(context, httpRequest);
        return;
      }

      context
        .writeAndFlush(new DefaultFullHttpResponse(
          httpRequest.protocolVersion(),
          HttpResponseStatus.NOT_FOUND
        ))
        .addListener(ChannelFutureListener.CLOSE);

    } catch (Exception exception) {
      context.fireExceptionCaught(new InternalServerErrorException(exception));
    }
  }

  /**
   * Handles the /health endpoint. It responds with an {@link HttpResponseStatus#OK} (200) if the
   * following  mandatory dependencies are live:
   * <ul>
   *   <li>Database</li>
   *   <li>UserManagement</li>
   *   <li>Storages</li>
   * </ul>
   * If one of the dependency are not reachable it responds with an InternalServerError (500).
   * <p>
   * Unlike the /health/ready endpoint, this one returns also a json containing the status of the
   * service and its dependencies. This is the JSON in response if everything is ok:
   * <code>
   *   {
   *    "dependencies" : [
   *       {
   *          "live" : true,
   *          "name" : "Files Database",
   *          "ready" : true,
   *          "type" : "REQUIRED"
   *       },
   *       {
   *          "live" : true,
   *          "name" : "carbonio-user-management",
   *          "ready" : true,
   *          "type" : "REQUIRED"
   *       },
   *       {
   *          "live" : true,
   *          "name" : "carbonio-storages",
   *          "ready" : true,
   *          "type" : "REQUIRED"
   *       },
   *       {
   *          "live" : true,
   *          "name" : "carbonio-preview",
   *          "ready" : true,
   *          "type" : "OPTIONAL"
   *       }
   *    ],
   *    "ready" : true
   * }
   * </code>
   *
   * @param context is a {@link ChannelHandlerContext} used to write the response.
   * @param httpRequest is a {@link HttpRequest} representing the health/ready request
   */
  private void metrics(
    ChannelHandlerContext context,
    HttpRequest httpRequest
  ){

    String responseBody = prometheusService.getRegistry().scrape();

    FullHttpResponse response = new DefaultFullHttpResponse(
      httpRequest.protocolVersion(),
      HttpResponseStatus.OK,
      Unpooled.wrappedBuffer(responseBody.getBytes(StandardCharsets.UTF_8))
    );
    response.headers().add(HttpHeaderNames.CONTENT_TYPE, HttpHeaderValues.TEXT_PLAIN);
    response.headers().add(HttpHeaderNames.CONTENT_LENGTH, response.content().readableBytes());

    context
      .writeAndFlush(response)
      .addListener(ChannelFutureListener.CLOSE);
  }
}
