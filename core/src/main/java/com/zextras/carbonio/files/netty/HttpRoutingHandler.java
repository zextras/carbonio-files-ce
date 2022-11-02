// SPDX-FileCopyrightText: 2022 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.netty;

import com.google.inject.Inject;
import com.zextras.carbonio.files.Files.API.Endpoints;
import com.zextras.carbonio.files.graphql.controllers.GraphQLController;
import com.zextras.carbonio.files.rest.controllers.BlobController;
import com.zextras.carbonio.files.rest.controllers.HealthController;
import com.zextras.carbonio.files.rest.controllers.MetricsController;
import com.zextras.carbonio.files.rest.controllers.CollaborationLinkController;
import com.zextras.carbonio.files.rest.controllers.PreviewController;
import com.zextras.carbonio.files.rest.controllers.ProcedureController;
import io.netty.channel.ChannelHandler.Sharable;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.stream.ChunkedWriteHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Sharable
public class HttpRoutingHandler extends SimpleChannelInboundHandler<HttpRequest> {

  private static final Logger logger = LoggerFactory.getLogger(HttpRoutingHandler.class);

  private final HealthController            healthController;
  private final GraphQLController           graphQLController;
  private final BlobController              blobController;
  private final AuthenticationHandler       authenticationHandler;
  private final ExceptionsHandler           exceptionsHandler;
  private final PreviewController           previewController;
  private final ProcedureController         procedureController;
  private final CollaborationLinkController collaborationLinkController;
  private final MetricsController metricsController;

  @Inject
  public HttpRoutingHandler(
    HealthController healthController,
    GraphQLController graphQLController,
    BlobController blobController,
    AuthenticationHandler authenticationHandler,
    ExceptionsHandler exceptionsHandler,
    PreviewController previewController,
    ProcedureController procedureController,
    CollaborationLinkController collaborationLinkController,
    MetricsController metricsController
  ) {
    logger.info("Service ready to receive http requests!");
    this.healthController = healthController;
    this.authenticationHandler = authenticationHandler;
    this.exceptionsHandler = exceptionsHandler;
    this.graphQLController = graphQLController;
    this.blobController = blobController;
    this.previewController = previewController;
    this.procedureController = procedureController;
    this.collaborationLinkController = collaborationLinkController;
    this.metricsController = metricsController;
  }

  @Override
  protected void channelRead0(
    ChannelHandlerContext context,
    HttpRequest request
  ) {

    if (Endpoints.METRICS.matcher(request.uri()).matches()) {
      context.pipeline()
        .addLast("metrics-handler", metricsController)
        .addLast("exceptions-handler", exceptionsHandler);
      context.fireChannelRead(request);
      return;
    }

    if (Endpoints.HEALTH.matcher(request.uri()).matches()) {
      context.pipeline().addLast("health-handler", healthController);
      context.fireChannelRead(request);
      return;
    }

    logger.info(request.uri());

    if (Endpoints.GRAPHQL.matcher(request.uri()).matches()) {
      context.pipeline().addLast(new HttpObjectAggregator(256 * 1024));
      context.pipeline().addLast(new ChunkedWriteHandler());
      context.pipeline().addLast("auth-handler", authenticationHandler);
      context.pipeline().addLast("graphql-handler", graphQLController);
      context.fireChannelRead(request);
      return;
    }

    if (Endpoints.DOWNLOAD_FILE.matcher(request.uri()).matches()
      || Endpoints.UPLOAD_FILE.matcher(request.uri()).matches()
      || Endpoints.UPLOAD_FILE_VERSION.matcher(request.uri()).matches()) {
      context.pipeline()
        .addLast("auth-handler", authenticationHandler)
        .addLast("rest-handler", blobController);
      context.fireChannelRead(request);
      return;
    }

    if (Endpoints.PUBLIC_LINK.matcher(request.uri()).matches()) {
      context.pipeline()
        .addLast("rest-handler", blobController);
      context.fireChannelRead(request);
      return;
    }

    if (Endpoints.COLLABORATION_LINK.matcher(request.uri()).matches()) {
      context
        .pipeline()
        .addLast("auth-handler", authenticationHandler)
        .addLast("collaboration-link-handler", collaborationLinkController);
      context.fireChannelRead(request);
      return;
    }

    if (Endpoints.PREVIEW.matcher(request.uri()).matches()) {
      context.pipeline()
        .addLast("auth-handler", authenticationHandler)
        .addLast("preview-handler", previewController);
      context.fireChannelRead(request);
      return;
    }

    if (Endpoints.UPLOAD_FILE_TO.matcher(request.uri()).matches()) {
      context.pipeline()
        .addLast(new HttpObjectAggregator(256 * 1024))
        .addLast(new ChunkedWriteHandler())
        .addLast("auth-handler", authenticationHandler)
        .addLast("procedure-handler", procedureController)
        .addLast("exceptions-handler", exceptionsHandler);
      context.fireChannelRead(request);
      return;
    }

    FullHttpResponse response = new DefaultFullHttpResponse(
      request.protocolVersion(),
      HttpResponseStatus.NOT_FOUND
    );

    context.writeAndFlush(response);
    context.close();
  }
}
