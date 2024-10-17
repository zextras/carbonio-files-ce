// SPDX-FileCopyrightText: 2022 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.rest.controllers;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.inject.Inject;
import com.zextras.carbonio.files.Files.API.Endpoints;
import com.zextras.carbonio.files.exceptions.InternalServerErrorException;
import com.zextras.carbonio.files.rest.services.HealthService;
import com.zextras.carbonio.files.rest.types.health.DependencyType;
import com.zextras.carbonio.files.rest.types.health.HealthResponse;
import com.zextras.carbonio.files.rest.types.health.ServiceHealth;
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
import java.nio.charset.StandardCharsets;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.regex.Matcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@ChannelHandler.Sharable
public class HealthController extends SimpleChannelInboundHandler<HttpRequest> {

  private static final Logger logger = LoggerFactory.getLogger(HealthController.class);

  private final HealthService healthService;

  @Inject
  public HealthController(HealthService healthService) {
    this.healthService = healthService;
  }

  @Override
  protected void channelRead0(
    ChannelHandlerContext context,
    HttpRequest httpRequest
  ) {

    String uriRequest = httpRequest.uri();
    Matcher healthMatcher = Endpoints.HEALTH.matcher(uriRequest);
    Matcher healthLiveMatcher = Endpoints.HEALTH_LIVE.matcher(uriRequest);
    Matcher healthReadyMatcher = Endpoints.HEALTH_READY.matcher(uriRequest);

    try {
      if (healthLiveMatcher.find()) {
        healthLive(context, httpRequest);
        return;
      }

      if (healthReadyMatcher.find()) {
        healthReady(context, httpRequest);
        return;
      }

      // This must be always the last check otherwise the health/ regex matches also the
      // health/live and health/ready endpoints
      if (healthMatcher.find()) {
        health(context, httpRequest);
        return;
      }

      context.fireExceptionCaught(new NoSuchElementException());

    } catch (Exception exception) {
      logger.error("HealthController catches an exception", exception);
      context.fireExceptionCaught(exception);
    }
  }

  /**
   * Responds with an {@link HttpResponseStatus#OK} (200) representing the liveness of the service.
   *
   * @param context is a {@link ChannelHandlerContext} used to write the response.
   * @param httpRequest is a {@link HttpRequest} representing the health/live request
   */
  private void healthLive(
    ChannelHandlerContext context,
    HttpRequest httpRequest
  ) {
    logger.debug("carbonio-files is live");
    context
      .writeAndFlush(new DefaultFullHttpResponse(
        httpRequest.protocolVersion(),
        HttpResponseStatus.NO_CONTENT)
      )
      .addListener(ChannelFutureListener.CLOSE);
  }

  /**
   * Handles the /health/ready endpoint. It responds with an {@link HttpResponseStatus#OK} (200) if
   * the following  mandatory dependencies are live:
   * <ul>
   *   <li>Database</li>
   *   <li>UserManagement</li>
   *   <li>Storages</li>
   * </ul>
   * If one of the dependency are not reachable it responds with an InternalServerError (500).
   *
   * @param context is a {@link ChannelHandlerContext} used to write the response.
   * @param httpRequest is a {@link HttpRequest} representing the health/ready request
   */
  private void healthReady(
    ChannelHandlerContext context,
    HttpRequest httpRequest
  ) {
    boolean databaseIsUp = healthService.isDatabaseLive();
    boolean userManagementIsUp = healthService.isUserManagementLive();
    boolean fileStoreIsUp = healthService.isStoragesLive();

    HttpResponseStatus responseStatus = (databaseIsUp && userManagementIsUp && fileStoreIsUp)
      ? HttpResponseStatus.NO_CONTENT
      : HttpResponseStatus.INTERNAL_SERVER_ERROR;

    logger.info(MessageFormat.format("carbonio files status: {0}", responseStatus));

    context
      .writeAndFlush(new DefaultFullHttpResponse(httpRequest.protocolVersion(), responseStatus))
      .addListener(ChannelFutureListener.CLOSE);
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
   *       },
   *       {
   *          "live" : true,
   *          "name" : "carbonio-docs-connector",
   *          "ready" : true,
   *          "type" : "OPTIONAL"
   *       },
   *       {
   *          "live" : true,
   *          "name" : "carbonio-message-broker",
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
  private void health(
    ChannelHandlerContext context,
    HttpRequest httpRequest
  ) throws JsonProcessingException {

    List<ServiceHealth> dependencies = new ArrayList<>();
    dependencies.add(healthService.getDatabaseHealth());
    dependencies.add(healthService.getUserManagementHealth());
    dependencies.add(healthService.getStoragesHealth());
    dependencies.add(healthService.getPreviewHealth());
    dependencies.add(healthService.getDocsConnectorHealth());
    dependencies.add(healthService.getMessageBrokerHealth());

    boolean filesIsReady = dependencies
      .stream()
      .filter(dependencyHealth -> DependencyType.REQUIRED.equals(dependencyHealth.getType()))
      .allMatch(ServiceHealth::isReady);

    HealthResponse healthResponse = new HealthResponse()
      .setDependencies(dependencies)
      .setReady(filesIsReady);

    HttpResponseStatus responseStatus = filesIsReady
      ? HttpResponseStatus.OK
      : HttpResponseStatus.INTERNAL_SERVER_ERROR;

    String responseBody = new ObjectMapper().writeValueAsString(healthResponse);

    FullHttpResponse response = new DefaultFullHttpResponse(
      httpRequest.protocolVersion(),
      responseStatus,
      Unpooled.wrappedBuffer(responseBody.getBytes(StandardCharsets.UTF_8))
    );
    response.headers().add(HttpHeaderNames.CONTENT_TYPE, HttpHeaderValues.APPLICATION_JSON);
    response.headers().add(HttpHeaderNames.CONTENT_LENGTH, response.content().readableBytes());

    context
      .writeAndFlush(response)
      .addListener(ChannelFutureListener.CLOSE);
  }
}
