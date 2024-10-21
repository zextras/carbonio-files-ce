// SPDX-FileCopyrightText: 2023 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.netty;

import com.zextras.carbonio.files.graphql.controllers.GraphQLController;
import com.zextras.carbonio.files.graphql.controllers.PublicGraphQLController;
import com.zextras.carbonio.files.rest.controllers.BlobController;
import com.zextras.carbonio.files.rest.controllers.CollaborationLinkController;
import com.zextras.carbonio.files.rest.controllers.HealthController;
import com.zextras.carbonio.files.rest.controllers.MetricsController;
import com.zextras.carbonio.files.rest.controllers.PreviewController;
import com.zextras.carbonio.files.rest.controllers.ProcedureController;
import com.zextras.carbonio.files.rest.controllers.PublicBlobController;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.stream.ChunkedWriteHandler;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

class HttpRoutingHandlerTest {

  private HealthController healthControllerMock;
  private GraphQLController graphQLControllerMock;
  private PublicGraphQLController publicGraphQLControllerMock;
  private BlobController blobControllerMock;
  private PublicBlobController publicBlobControllerMock;
  private AuthenticationHandler authenticationHandlerMock;
  private ExceptionsHandler exceptionsHandlerMock;
  private PreviewController previewControllerMock;
  private ProcedureController procedureControllerMock;
  private CollaborationLinkController collaborationLinkControllerMock;
  private MetricsController metricsControllerMock;
  private ChannelHandlerContext channelHandlerContextMock;
  private ChannelPipeline channelPipelineMock;
  private HttpRequest httpRequestMock;
  private HttpRoutingHandler httpRoutingHandler;

  @BeforeEach
  void initTest() {
    healthControllerMock = Mockito.mock(HealthController.class);
    graphQLControllerMock = Mockito.mock(GraphQLController.class);
    blobControllerMock = Mockito.mock(BlobController.class);
    publicBlobControllerMock = Mockito.mock(PublicBlobController.class);
    authenticationHandlerMock = Mockito.mock(AuthenticationHandler.class);
    exceptionsHandlerMock = Mockito.mock(ExceptionsHandler.class);
    previewControllerMock = Mockito.mock(PreviewController.class);
    procedureControllerMock = Mockito.mock(ProcedureController.class);
    publicGraphQLControllerMock = Mockito.mock(PublicGraphQLController.class);
    collaborationLinkControllerMock = Mockito.mock(CollaborationLinkController.class);
    metricsControllerMock = Mockito.mock(MetricsController.class);
    channelHandlerContextMock = Mockito.mock(ChannelHandlerContext.class);
    channelPipelineMock = Mockito.mock(ChannelPipeline.class, Mockito.RETURNS_DEEP_STUBS);
    httpRequestMock = Mockito.mock(HttpRequest.class);

    Mockito.when(channelHandlerContextMock.pipeline()).thenReturn(channelPipelineMock);
    Mockito.when(
            channelPipelineMock.addLast(Mockito.anyString(), Mockito.any(ChannelHandler.class)))
        .thenReturn(channelPipelineMock);

    httpRoutingHandler =
        new HttpRoutingHandler(
            healthControllerMock,
            graphQLControllerMock,
            blobControllerMock,
            publicBlobControllerMock,
            authenticationHandlerMock,
            exceptionsHandlerMock,
            previewControllerMock,
            procedureControllerMock,
            publicGraphQLControllerMock,
            collaborationLinkControllerMock,
            metricsControllerMock);
  }

  @ParameterizedTest
  @ValueSource(strings = {"/metrics/", "/metrics"})
  void givenAMetricsRequestHttpRoutingHandlerShouldAddTheRightHandlersInTheChannelPipeline(
      String uri) {
    // Given
    Mockito.when(httpRequestMock.uri()).thenReturn(uri);

    // When
    httpRoutingHandler.channelRead0(channelHandlerContextMock, httpRequestMock);

    // Then
    Mockito.verify(channelPipelineMock, Mockito.times(1))
        .addLast("metrics-handler", metricsControllerMock);
    Mockito.verify(channelPipelineMock, Mockito.times(1))
        .addLast("exceptions-handler", exceptionsHandlerMock);
    Mockito.verify(channelHandlerContextMock, Mockito.times(1)).fireChannelRead(httpRequestMock);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "/health/",
        "/health",
        "/health/live",
        "/health/live/",
        "/health/ready",
        "/health/ready/"
      })
  void givenAnHealthRequestHttpRoutingHandlerShouldAddTheRightHandlersInTheChannelPipeline(
      String uri) {
    // Given
    Mockito.when(httpRequestMock.uri()).thenReturn(uri);

    // When
    httpRoutingHandler.channelRead0(channelHandlerContextMock, httpRequestMock);

    // Then
    Mockito.verify(channelPipelineMock, Mockito.times(1))
        .addLast("health-handler", healthControllerMock);
    Mockito.verify(channelPipelineMock, Mockito.times(1))
        .addLast("exceptions-handler", exceptionsHandlerMock);
    Mockito.verify(channelHandlerContextMock, Mockito.times(1)).fireChannelRead(httpRequestMock);
  }

  @ParameterizedTest
  @ValueSource(strings = {"/graphql/", "/graphql"})
  void givenAGraphqlRequestHttpRoutingHandlerShouldAddTheRightHandlersInTheChannelPipeline(
      String uri) {
    // Given
    Mockito.when(channelPipelineMock.addLast(Mockito.any(HttpObjectAggregator.class)))
        .thenReturn(channelPipelineMock);
    Mockito.when(channelPipelineMock.addLast(Mockito.any(ChunkedWriteHandler.class)))
        .thenReturn(channelPipelineMock);
    Mockito.when(httpRequestMock.uri()).thenReturn(uri);

    // When
    httpRoutingHandler.channelRead0(channelHandlerContextMock, httpRequestMock);

    // Then
    Mockito.verify(channelPipelineMock, Mockito.times(1))
        .addLast(Mockito.any(HttpObjectAggregator.class));
    Mockito.verify(channelPipelineMock, Mockito.times(1))
        .addLast(Mockito.any(ChunkedWriteHandler.class));
    Mockito.verify(channelPipelineMock, Mockito.times(1))
        .addLast("auth-handler", authenticationHandlerMock);
    Mockito.verify(channelPipelineMock, Mockito.times(1))
        .addLast("graphql-handler", graphQLControllerMock);
    Mockito.verify(channelPipelineMock, Mockito.times(1))
        .addLast("exceptions-handler", exceptionsHandlerMock);
    Mockito.verify(channelHandlerContextMock, Mockito.times(1)).fireChannelRead(httpRequestMock);
  }

  @ParameterizedTest
  @ValueSource(strings = {"/public/graphql/", "/public/graphql"})
  void givenAPublicGraphqlRequestHttpRoutingHandlerShouldAddTheRightHandlersInTheChannelPipeline(
    String uri) {
    // Given
    Mockito.when(channelPipelineMock.addLast(Mockito.any(HttpObjectAggregator.class)))
      .thenReturn(channelPipelineMock);
    Mockito.when(channelPipelineMock.addLast(Mockito.any(ChunkedWriteHandler.class)))
      .thenReturn(channelPipelineMock);
    Mockito.when(httpRequestMock.uri()).thenReturn(uri);

    // When
    httpRoutingHandler.channelRead0(channelHandlerContextMock, httpRequestMock);

    // Then
    Mockito.verify(channelPipelineMock, Mockito.times(1))
      .addLast(Mockito.any(HttpObjectAggregator.class));
    Mockito.verify(channelPipelineMock, Mockito.times(1))
      .addLast(Mockito.any(ChunkedWriteHandler.class));
    Mockito.verify(channelPipelineMock, Mockito.times(1))
      .addLast("public-graphql-handler", publicGraphQLControllerMock);
    Mockito.verify(channelPipelineMock, Mockito.times(1))
      .addLast("exceptions-handler", exceptionsHandlerMock);
    Mockito.verify(channelHandlerContextMock, Mockito.times(1)).fireChannelRead(httpRequestMock);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "/download/8caeef71-6f72-439c-847a-38e90efd0965",
        "/download/8caeef71-6f72-439c-847a-38e90efd0965/",
        "/download/8caeef71-6f72-439c-847a-38e90efd0965/1",
        "/download/8caeef71-6f72-439c-847a-38e90efd0965/1/",
        "/upload",
        "/upload/",
        "/upload-version",
        "/upload-version/"
      })
  void
      givenAnUploadOrDownloadRequestHttpRoutingHandlerShouldAddTheRightHandlersInTheChannelPipeline(
          String uri) {
    // Given
    Mockito.when(httpRequestMock.uri()).thenReturn(uri);

    // When
    httpRoutingHandler.channelRead0(channelHandlerContextMock, httpRequestMock);

    // Then
    Mockito.verify(channelPipelineMock, Mockito.times(1))
        .addLast("auth-handler", authenticationHandlerMock);
    Mockito.verify(channelPipelineMock, Mockito.times(1))
        .addLast("rest-handler", blobControllerMock);
    Mockito.verify(channelPipelineMock, Mockito.times(1))
        .addLast("exceptions-handler", exceptionsHandlerMock);
    Mockito.verify(channelHandlerContextMock, Mockito.times(1)).fireChannelRead(httpRequestMock);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "/link/abcd1234",
        "/link/abcd1234/",
        "/link/abcd1234abcd1234abcd1234abcd1234",
        "/link/abcd1234abcd1234abcd1234abcd1234/",
        "/public/link/download/abcd1234",
        "/public/link/download/abcd1234/",
        "/public/link/download/abcd1234abcd1234abcd1234abcd1234",
        "/public/link/download/abcd1234abcd1234abcd1234abcd1234/",
        "/public/download/00000000-0000-0000-0000-000000000000?node_link_id=00000000-0000-0000-0000-000000000001",
        "/public/download/00000000-0000-0000-0000-000000000000/?node_link_id=00000000-0000-0000-0000-000000000001"
      })
  void givenAPublicLinkRequestHttpRoutingHandlerShouldAddTheRightHandlersInTheChannelPipeline(
      String uri) {
    // Given
    Mockito.when(httpRequestMock.uri()).thenReturn(uri);

    // When
    httpRoutingHandler.channelRead0(channelHandlerContextMock, httpRequestMock);

    // Then
    Mockito.verify(channelPipelineMock, Mockito.times(1))
        .addLast("rest-handler", publicBlobControllerMock);
    Mockito.verify(channelPipelineMock, Mockito.times(1))
        .addLast("exceptions-handler", exceptionsHandlerMock);
    Mockito.verify(channelHandlerContextMock, Mockito.times(1)).fireChannelRead(httpRequestMock);
  }

  @ParameterizedTest
  @ValueSource(strings = {"/invite/abcd1234", "/invite/abcd1234/"})
  void givenAnInvitationLinkRequestHttpRoutingHandlerShouldAddTheRightHandlersInTheChannelPipeline(
      String uri) {
    // Given
    Mockito.when(httpRequestMock.uri()).thenReturn(uri);

    // When
    httpRoutingHandler.channelRead0(channelHandlerContextMock, httpRequestMock);

    // Then
    Mockito.verify(channelPipelineMock, Mockito.times(1))
        .addLast("auth-handler", authenticationHandlerMock);
    Mockito.verify(channelPipelineMock, Mockito.times(1))
        .addLast("collaboration-link-handler", collaborationLinkControllerMock);
    Mockito.verify(channelPipelineMock, Mockito.times(1))
        .addLast("exceptions-handler", exceptionsHandlerMock);
    Mockito.verify(channelHandlerContextMock, Mockito.times(1)).fireChannelRead(httpRequestMock);
  }

  @ParameterizedTest
  // The main regex for the Preview endpoint is "/preview/(.*)"
  // However I listed all the acceptable url even if the pattern accepts everything
  @ValueSource(
      strings = {
        "/preview/image/8caeef71-6f72-439c-847a-38e90efd0965/1/10x10",
        "/preview/image/8caeef71-6f72-439c-847a-38e90efd0965/1/10x10/",
        "/preview/image/8caeef71-6f72-439c-847a-38e90efd0965/1/10x10/thumbnail",
        "/preview/image/8caeef71-6f72-439c-847a-38e90efd0965/1/10x10/thumbnail/",
        "/preview/pdf/8caeef71-6f72-439c-847a-38e90efd0965/1",
        "/preview/pdf/8caeef71-6f72-439c-847a-38e90efd0965/1/",
        "/preview/pdf/8caeef71-6f72-439c-847a-38e90efd0965/1/10x10/thumbnail",
        "/preview/pdf/8caeef71-6f72-439c-847a-38e90efd0965/1/10x10/thumbnail/",
        "/preview/document/8caeef71-6f72-439c-847a-38e90efd0965/1",
        "/preview/document/8caeef71-6f72-439c-847a-38e90efd0965/1/",
        "/preview/document/8caeef71-6f72-439c-847a-38e90efd0965/1/10x10/thumbnail",
        "/preview/document/8caeef71-6f72-439c-847a-38e90efd0965/1/10x10/thumbnail/"
      })
  void givenAPreviewRequestHttpRoutingHandlerShouldAddTheRightHandlersInTheChannelPipeline(
      String uri) {
    // Given
    Mockito.when(httpRequestMock.uri()).thenReturn(uri);

    // When
    httpRoutingHandler.channelRead0(channelHandlerContextMock, httpRequestMock);

    // Then
    Mockito.verify(channelPipelineMock, Mockito.times(1))
        .addLast("auth-handler", authenticationHandlerMock);
    Mockito.verify(channelPipelineMock, Mockito.times(1))
        .addLast("preview-handler", previewControllerMock);
    Mockito.verify(channelPipelineMock, Mockito.times(1))
        .addLast("exceptions-handler", exceptionsHandlerMock);
    Mockito.verify(channelHandlerContextMock, Mockito.times(1)).fireChannelRead(httpRequestMock);
  }

  @ParameterizedTest
  @ValueSource(strings = {"/upload-to/", "/upload-to"})
  void givenAnUploadToModuleRequestHttpRoutingHandlerShouldAddTheRightHandlersInTheChannelPipeline(
      String uri) {
    // Given
    Mockito.when(channelPipelineMock.addLast(Mockito.any(HttpObjectAggregator.class)))
        .thenReturn(channelPipelineMock);
    Mockito.when(channelPipelineMock.addLast(Mockito.any(ChunkedWriteHandler.class)))
        .thenReturn(channelPipelineMock);
    Mockito.when(httpRequestMock.uri()).thenReturn(uri);

    // When
    httpRoutingHandler.channelRead0(channelHandlerContextMock, httpRequestMock);

    // Then
    Mockito.verify(channelPipelineMock, Mockito.times(1))
        .addLast(Mockito.any(HttpObjectAggregator.class));
    Mockito.verify(channelPipelineMock, Mockito.times(1))
        .addLast(Mockito.any(ChunkedWriteHandler.class));
    Mockito.verify(channelPipelineMock, Mockito.times(1))
        .addLast("auth-handler", authenticationHandlerMock);
    Mockito.verify(channelPipelineMock, Mockito.times(1))
        .addLast("procedure-handler", procedureControllerMock);
    Mockito.verify(channelPipelineMock, Mockito.times(1))
        .addLast("exceptions-handler", exceptionsHandlerMock);
    Mockito.verify(channelHandlerContextMock, Mockito.times(1)).fireChannelRead(httpRequestMock);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "/invalid/endpoint",
        "/metrics/invalid",
        "/health/invalid",
        "/health/live/invalid",
        "/health/ready/invalid",
        "/graphql/invalid",
        "/download/invalid",
        "/download/8caeef71-6f72-439c-847a-38e90efd0965/invalid",
        "/download/8caeef71-6f72-439c-847a-38e90efd0965/1/invalid",
        "/upload/invalid",
        "/upload-version/invalid",
        "/link/abcd1234/invalid",
        "/link/seven00",
        "/link/seven00/",
        "/link/nine00000",
        "/link/nine00000/",
        "/link/thirtythreethirtythreethirtythree",
        "/link/thirtythreethirtythreethirtythree/",
        "/public/link/download/abcd1234/invalid",
        "/public/link/download/seven00",
        "/public/link/download/seven00/",
        "/public/link/download/nine00000",
        "/public/link/download/nine00000/",
        "/public/link/download/thirtythreethirtythreethirtythree",
        "/public/link/download/thirtythreethirtythreethirtythree/",
        "/public/download/invalid",
        "/public/download/00000000-0000-0000-0000-000000000000/invalid",
        "/invite/abcd1234/invalid",
        "/upload-to/invalid"
      })
  void givenAnInvalidEndpointRequestHttpRoutingHandlerShouldRespondWith404(String invalidUri) {
    // Given
    Mockito.when(httpRequestMock.uri()).thenReturn(invalidUri);
    Mockito.when(httpRequestMock.protocolVersion()).thenReturn(HttpVersion.HTTP_1_1);

    ArgumentCaptor<FullHttpResponse> captorHttpResponse =
        ArgumentCaptor.forClass(FullHttpResponse.class);

    // When
    httpRoutingHandler.channelRead0(channelHandlerContextMock, httpRequestMock);

    // Then
    Mockito.verify(channelHandlerContextMock, Mockito.times(1))
        .writeAndFlush(captorHttpResponse.capture());
    Mockito.verify(channelHandlerContextMock, Mockito.times(1)).close();

    Assertions.assertThat(captorHttpResponse.getValue().protocolVersion())
        .isEqualTo(HttpVersion.HTTP_1_1);
    Assertions.assertThat(captorHttpResponse.getValue().status())
        .isEqualTo(HttpResponseStatus.NOT_FOUND);
  }
}
