// SPDX-FileCopyrightText: 2024 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.clients;

import com.zextras.carbonio.files.config.FilesConfig;
import java.io.IOException;
import org.apache.http.ProtocolVersion;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.message.BasicStatusLine;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

public class DocsConnectorHttpClientTest {

  private CloseableHttpClient httpClientMock;

  private DocsConnectorHttpClient docsConnectorHttpClient;

  @BeforeEach
  void setup() {
    httpClientMock = Mockito.mock(CloseableHttpClient.class);
    FilesConfig filesConfigMock = Mockito.mock(FilesConfig.class);
    Mockito.when(filesConfigMock.getDocsConnectorUrl()).thenReturn("http://127.78.0.2:20005");

    docsConnectorHttpClient = new DocsConnectorHttpClient(httpClientMock, filesConfigMock);
  }

  @Test
  void givenADocsConnectorHealthyTheHealthLiveCheckShouldReturnTrue() throws IOException {
    // Given
    CloseableHttpResponse httpResponseMock = Mockito.mock(CloseableHttpResponse.class);
    Mockito.when(httpResponseMock.getStatusLine())
        .thenReturn(new BasicStatusLine(new ProtocolVersion("http", 1, 1), 204, ""));

    ArgumentCaptor<HttpGet> httpRequestCaptor = ArgumentCaptor.forClass(HttpGet.class);
    Mockito.when(httpClientMock.execute(httpRequestCaptor.capture())).thenReturn(httpResponseMock);

    // When
    boolean isDocsConnectorLive = docsConnectorHttpClient.healthLiveCheck();

    // Then
    Assertions.assertThat(isDocsConnectorLive).isTrue();

    HttpGet httpRequest = httpRequestCaptor.getValue();
    Assertions.assertThat(httpRequest.getMethod()).isEqualTo("GET");
    Assertions.assertThat(httpRequest.getURI().toString())
        .isEqualTo("http://127.78.0.2:20005/health/live/");

    Assertions.assertThat(httpRequest.getConfig().getConnectTimeout()).isEqualTo(2000L);
    Assertions.assertThat(httpRequest.getConfig().getSocketTimeout()).isEqualTo(2000L);
  }

  @Test
  void givenADocsConnectorUnreachableTheHealthLiveCheckShouldReturnFalse() throws IOException {
    // Given
    CloseableHttpResponse httpResponseMock = Mockito.mock(CloseableHttpResponse.class);
    Mockito.when(httpResponseMock.getStatusLine())
        .thenReturn(new BasicStatusLine(new ProtocolVersion("http", 1, 1), 500, ""));

    ArgumentCaptor<HttpGet> httpRequestCaptor = ArgumentCaptor.forClass(HttpGet.class);
    Mockito.when(httpClientMock.execute(httpRequestCaptor.capture())).thenReturn(httpResponseMock);

    // When
    boolean isDocsConnectorLive = docsConnectorHttpClient.healthLiveCheck();

    // Then
    Assertions.assertThat(isDocsConnectorLive).isFalse();
  }
}
