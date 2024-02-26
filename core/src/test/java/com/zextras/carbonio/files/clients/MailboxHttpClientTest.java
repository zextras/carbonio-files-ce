// SPDX-FileCopyrightText: 2024 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.clients;

import com.zextras.carbonio.files.config.FilesConfig;
import com.zextras.carbonio.files.exceptions.InternalServerErrorException;
import io.vavr.control.Try;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.apache.commons.io.IOUtils;
import org.apache.http.HttpEntity;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.impl.client.CloseableHttpClient;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

class MailboxHttpClientTest {

  private CloseableHttpClient httpClientMock;
  private MailboxHttpClient mailboxHttpClient;

  @BeforeEach
  void setUp() {
    httpClientMock = Mockito.mock(CloseableHttpClient.class);
    mailboxHttpClient = new MailboxHttpClient(httpClientMock, new FilesConfig());
  }

  @Test
  void
      givenAFileToUploadWithAnEnglishFilenameTheUploadFileShouldUploadTheBlobCorrectlyAndReturnTheAttachmentId()
          throws Exception {
    // Given
    final String contentFile = "text file";
    final String contentResponse =
        "200,'null','85e4b3d9-1f41-4292-9dc8-e933194cc1f2:dbca72a2-8b05-45c5-a83f-bbae05ab907c'";

    final CloseableHttpResponse httpResponseMock =
        Mockito.mock(CloseableHttpResponse.class, Mockito.RETURNS_DEEP_STUBS);
    Mockito.when(httpResponseMock.getStatusLine().getStatusCode()).thenReturn(200);
    Mockito.when(httpResponseMock.getEntity().getContent())
        .thenReturn(IOUtils.toInputStream(contentResponse, StandardCharsets.UTF_8));

    Mockito.when(httpClientMock.execute(Mockito.any(HttpPost.class))).thenReturn(httpResponseMock);

    final ArgumentCaptor<HttpPost> httpRequestCaptor = ArgumentCaptor.forClass(HttpPost.class);
    Mockito.when(httpClientMock.execute(httpRequestCaptor.capture())).thenReturn(httpResponseMock);

    // When
    final Try<String> tryAttachmentId =
        mailboxHttpClient.uploadFile(
            "fake-cookie",
            "test.txt",
            "text/plain",
            IOUtils.toInputStream(contentFile, StandardCharsets.UTF_8),
            Long.valueOf(contentFile.length()));

    // Then
    // Assert response
    Assertions.assertThat(tryAttachmentId.isSuccess()).isTrue();
    Assertions.assertThat(tryAttachmentId.get())
        .isEqualTo("85e4b3d9-1f41-4292-9dc8-e933194cc1f2:dbca72a2-8b05-45c5-a83f-bbae05ab907c");

    // Assert request
    final HttpPost postRequest = httpRequestCaptor.getValue();
    Assertions.assertThat(postRequest.getURI())
        .hasToString("http://127.78.0.2:20004/service/upload?fmt=raw");
    Assertions.assertThat(postRequest.getFirstHeader("Cookie").getValue()).isEqualTo("fake-cookie");
    Assertions.assertThat(postRequest.getFirstHeader("Content-Disposition").getValue())
        .isEqualTo("attachment; filename=\"test.txt\"; filename*=UTF-8''test.txt");
    Assertions.assertThat(postRequest.getProtocolVersion().getMajor()).isOne();
    Assertions.assertThat(postRequest.getProtocolVersion().getMinor()).isOne();

    final HttpEntity httpEntity = postRequest.getEntity();
    Assertions.assertThat(httpEntity.getContentLength()).isEqualTo(9L);
    Assertions.assertThat(httpEntity.getContentType().getValue()).contains("text/plain");
    Assertions.assertThat(IOUtils.toString(httpEntity.getContent(), StandardCharsets.UTF_8))
        .isEqualTo("text file");
  }

  @Test
  void
      givenAFileToUploadWithACyrillicFilenameTheUploadFileShouldUploadTheBlobCorrectlyAndReturnTheAttachmentId()
          throws Exception {
    // Given
    final String contentResponse =
        "200,'null','85e4b3d9-1f41-4292-9dc8-e933194cc1f2:dbca72a2-8b05-45c5-a83f-bbae05ab907c'";

    final CloseableHttpResponse httpResponseMock =
        Mockito.mock(CloseableHttpResponse.class, Mockito.RETURNS_DEEP_STUBS);
    Mockito.when(httpResponseMock.getStatusLine().getStatusCode()).thenReturn(200);
    Mockito.when(httpResponseMock.getEntity().getContent())
        .thenReturn(IOUtils.toInputStream(contentResponse, StandardCharsets.UTF_8));

    Mockito.when(httpClientMock.execute(Mockito.any(HttpPost.class))).thenReturn(httpResponseMock);

    final ArgumentCaptor<HttpPost> httpRequestCaptor = ArgumentCaptor.forClass(HttpPost.class);
    Mockito.when(httpClientMock.execute(httpRequestCaptor.capture())).thenReturn(httpResponseMock);

    // When
    final Try<String> tryAttachmentId =
        mailboxHttpClient.uploadFile(
            "fake-cookie",
            "синий файл.txt",
            "text/plain",
            IOUtils.toInputStream("a", StandardCharsets.UTF_8),
            1L);

    // Then
    // Assert response
    Assertions.assertThat(tryAttachmentId.isSuccess()).isTrue();
    Assertions.assertThat(tryAttachmentId.get())
        .isEqualTo("85e4b3d9-1f41-4292-9dc8-e933194cc1f2:dbca72a2-8b05-45c5-a83f-bbae05ab907c");

    // Assert request
    Assertions.assertThat(
            httpRequestCaptor.getValue().getFirstHeader("Content-Disposition").getValue())
        .isEqualTo(
            "attachment; filename=\"синий файл.txt\";"
                + " filename*=UTF-8''%D1%81%D0%B8%D0%BD%D0%B8%D0%B9+%D1%84%D0%B0%D0%B9%D0%BB.txt");
  }

  @Test
  void givenAFileToUploadAndAnUnreachableMailboxTheUploadFileShouldReturnATryFailure()
      throws Exception {
    // Given
    final CloseableHttpResponse httpResponseMock =
        Mockito.mock(CloseableHttpResponse.class, Mockito.RETURNS_DEEP_STUBS);
    Mockito.when(httpResponseMock.getStatusLine().getStatusCode()).thenReturn(500);
    Mockito.when(httpResponseMock.getStatusLine().getReasonPhrase()).thenReturn("Generic error");

    Mockito.when(httpClientMock.execute(Mockito.any(HttpPost.class))).thenReturn(httpResponseMock);

    // When
    final Try<String> tryAttachmentId =
        mailboxHttpClient.uploadFile(
            "fake-cookie",
            "синий файл.txt",
            "text/plain",
            IOUtils.toInputStream("a", StandardCharsets.UTF_8),
            1L);

    // Then
    Assertions.assertThat(tryAttachmentId.isFailure()).isTrue();
    Assertions.assertThatThrownBy(() -> tryAttachmentId.get())
        .isInstanceOf(InternalServerErrorException.class)
        .hasMessage("Upload to mailbox failed: 500 Generic error");
  }

  @Test
  void givenAFileToUploadAndAnErrorFromTheMailboxTheUploadFileShouldReturnATryFailure()
      throws Exception {
    // Given
    final String contentResponse = "500,'null'";

    final CloseableHttpResponse httpResponseMock =
        Mockito.mock(CloseableHttpResponse.class, Mockito.RETURNS_DEEP_STUBS);
    Mockito.when(httpResponseMock.getStatusLine().getStatusCode()).thenReturn(200);
    Mockito.when(httpResponseMock.getEntity().getContent())
        .thenReturn(IOUtils.toInputStream(contentResponse, StandardCharsets.UTF_8));

    Mockito.when(httpClientMock.execute(Mockito.any(HttpPost.class))).thenReturn(httpResponseMock);

    // When
    final Try<String> tryAttachmentId =
        mailboxHttpClient.uploadFile(
            "fake-cookie",
            "test.txt",
            "text/plain",
            IOUtils.toInputStream("a", StandardCharsets.UTF_8),
            1L);

    // Then
    Assertions.assertThat(tryAttachmentId.isFailure()).isTrue();
    Assertions.assertThatThrownBy(() -> tryAttachmentId.get())
        .hasMessage("Upload to mailbox failed");
  }

  @Test
  void givenAFileToUploadAndAnExceptionDuringTheRequestBuildTheUploadFileShouldReturnATryFailure()
      throws Exception {
    // Given
    Mockito.when(httpClientMock.execute(Mockito.any(HttpPost.class)))
        .thenThrow(new IOException("fake-exception"));

    // When
    final Try<String> tryAttachmentId =
        mailboxHttpClient.uploadFile(
            "fake-cookie",
            "test.txt",
            "text/plain",
            IOUtils.toInputStream("a", StandardCharsets.UTF_8),
            1L);

    // Then
    Assertions.assertThat(tryAttachmentId.isFailure()).isTrue();
    Assertions.assertThatThrownBy(() -> tryAttachmentId.get())
        .isInstanceOf(InternalServerErrorException.class)
        .hasMessageContaining("fake-exception");
  }
}
