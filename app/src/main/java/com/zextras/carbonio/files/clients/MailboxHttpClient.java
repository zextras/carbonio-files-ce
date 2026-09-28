// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.clients;

import com.zextras.carbonio.files.Constants;
import com.zextras.carbonio.files.exceptions.DependencyException;
import com.zextras.carbonio.files.exceptions.RequestEntityTooLargeException;
import com.zextras.carbonio.files.utilities.ContentDispositionUtils;
import com.zextras.carbonio.quarkus.extensions.bootstrap.NetworkingConfigService;
import io.vavr.control.Try;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.text.MessageFormat;
import java.util.Arrays;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Http client to upload a blob to carbonio-mailbox ({@code /upload-to}). Quarkus CDI port of the
 * legacy Guice {@code MailboxHttpClient}: same endpoint, headers, and CSV response parsing; host
 * and port come from {@link NetworkingConfigService} ({@code networking-config.carbonio.mailbox.*},
 * defaulting to the mesh IP/port from {@code package/carbonio-files.hcl}: {@code
 * 127.78.0.2:20004}).
 *
 * <p>The legacy implementation used Apache HttpClient ({@code InputStreamEntity} with an explicit
 * {@code Content-Length}); this port uses the JDK's {@link HttpClient} instead (no new dependency
 * needed). {@link HttpRequest.Builder} forbids setting {@code Content-Length} manually, so the body
 * is streamed with chunked transfer-encoding instead of a fixed length — mailbox reads the stream
 * to completion either way, so the wire behaviour is equivalent.
 */
@ApplicationScoped
public class MailboxHttpClient {

  private static final Logger logger = LoggerFactory.getLogger(MailboxHttpClient.class);
  private static final String UPLOAD_FILE_ENDPOINT = "service/upload?fmt=raw";

  private final String mailboxUrl;
  private final HttpClient httpClient;

  @Inject
  public MailboxHttpClient(NetworkingConfigService networkingConfig) {
    String host =
        networkingConfig
            .get(Constants.Config.Mailbox.HOST_PROPERTY)
            .orElse(Constants.Config.Mailbox.DEFAULT_HOST);
    String port =
        networkingConfig
            .get(Constants.Config.Mailbox.PORT_PROPERTY)
            .orElse(String.valueOf(Constants.Config.Mailbox.DEFAULT_PORT));
    this.mailboxUrl =
        String.format("%s://%s:%s/", Constants.Config.Mailbox.DEFAULT_PROTOCOL, host, port);
    // Force HTTP/1.1: the JDK client's default (HTTP/2 with an HTTP/1.1 upgrade attempt) trips
    // plaintext servers that only speak HTTP/1.1 (e.g. WireMock/Jetty in the ITs) into a protocol
    // error. mailbox itself is plain HTTP/1.1, and the carbonio-preview REST SDK's own HttpClient
    // pins the same version for the same reason.
    this.httpClient = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
  }

  /**
   * Uploads {@code file} to the mailbox store. The returned attachment id should be attached to an
   * already existing carbonio item (e.g. a mail draft).
   *
   * @param cookies the requester's cookies, forwarded verbatim to mailbox.
   * @param fullFilename the node filename with its extension.
   * @param mimeType the node's mime-type, sent as the request's {@code Content-Type} header.
   * @param file the blob to upload.
   * @param fileLength the blob size (unused by the JDK transport, kept for signature parity with
   *     the caller, which already has it on hand from the {@code FileVersion}).
   * @return a {@link Try} containing the mailbox attachment id, or a failure ({@link
   *     RequestEntityTooLargeException} if mailbox's own embedded status isn't {@code 200}, {@link
   *     DependencyException} for any transport-level failure).
   */
  public Try<String> uploadFile(
      String cookies, String fullFilename, String mimeType, InputStream file, long fileLength) {
    try {
      HttpRequest request =
          HttpRequest.newBuilder()
              .uri(URI.create(mailboxUrl + UPLOAD_FILE_ENDPOINT))
              .header("Cookie", cookies)
              .header("Content-Type", mimeType)
              .header("Content-Disposition", ContentDispositionUtils.attachment(fullFilename))
              .POST(BodyPublishers.ofInputStream(() -> file))
              .build();

      HttpResponse<String> mailboxResponse = httpClient.send(request, BodyHandlers.ofString());

      if (mailboxResponse.statusCode() == 200) {
        /*
         This is the mailbox response:
         '200,'null','85e4b3d9-1f41-4292-9dc8-e933194cc1f2:dbca72a2-8b05-45c5-a83f-bbae05ab907c'\n

         To extrapolate the attachment id from it, we need to:
           - Split the response by comma to get a String[] containing three elements
           - Verify the embedded status code inside the response: it must be 200, otherwise it
             returns a failure
           - Remove the first two elements ('200', 'null')
           - Remove the ' and the \n characters
        */
        String[] responseBody = mailboxResponse.body().split(",");

        if ("200".equals(responseBody[0])) {
          return Arrays.stream(responseBody)
              .reduce((first, last) -> last)
              .map(aid -> aid.replaceAll("'", "").replaceAll("\n", ""))
              .map(Try::success)
              .orElseGet(
                  () ->
                      Try.failure(
                          new DependencyException(
                              "Unable to deserialize mailbox upload response")));
        }

        return Try.failure(new RequestEntityTooLargeException("Upload to mailbox failed"));
      }

      String errorMessage =
          MessageFormat.format("Upload to mailbox failed: {0}", mailboxResponse.statusCode());
      logger.error(errorMessage);
      return Try.failure(new DependencyException(errorMessage));

    } catch (Exception exception) {
      logger.error("Upload to mailbox failed", exception);
      return Try.failure(new DependencyException(exception));
    }
  }
}
