// SPDX-FileCopyrightText: 2022 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.clients;

import com.zextras.carbonio.files.config.FilesConfig;
import com.zextras.carbonio.files.exceptions.InternalServerErrorException;
import com.zextras.carbonio.files.exceptions.RequestEntityTooLargeException;
import com.zextras.carbonio.files.rest.types.UploadToRequest.TargetModule;
import io.vavr.control.Try;
import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.MessageFormat;
import java.util.Arrays;
import javax.inject.Inject;
import org.apache.commons.io.IOUtils;
import org.apache.http.ProtocolVersion;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.InputStreamEntity;
import org.apache.http.impl.client.CloseableHttpClient;

/**
 * Http client to make http requests to the mailbox. The mailbox is reached via service discover.
 */
public class MailboxHttpClient {

  private static final String UPLOAD_FILE_ENDPOINT = "service/upload?fmt=raw";

  private final String mailboxURL;
  private final CloseableHttpClient httpClient;

  @Inject
  public MailboxHttpClient(CloseableHttpClient httpClient, FilesConfig filesConfig) {
    this.httpClient = httpClient;
    this.mailboxURL = filesConfig.getMailboxUrl();
  }

  /**
   * Allows to upload a file to the mailbox store. The attachment id returned should be attached to
   * a carbonio item (e.g. an already existing mail draft). This method can be used for the
   * following target module:
   *
   * <ul>
   *   <li>{@link TargetModule#MAILS}
   *   <li>{@link TargetModule#CALENDARS}
   *   <li>{@link TargetModule#CONTACTS}
   * </ul>
   *
   * @param cookies is a {@link String} representing the cookies of the requesters who wants to
   *     upload the node to the mailbox.
   * @param fullFilename is a {@link String} representing the node filename with its extension.
   * @param mimeType is a {@link String} representing the mime-type of the node to upload.
   * @param file is the {@link InputStream} of the node to upload.
   * @param fileLength is a {@link Long} representing the size of the blob to upload.
   * @return a {@link Try} containing a {@link String} representing the mailbox attachment id
   *     uploaded or a {@link Throwable} if something goes wrong.
   */
  public Try<String> uploadFile(
      String cookies, String fullFilename, String mimeType, InputStream file, Long fileLength) {

    try {
      final HttpPost request = new HttpPost(mailboxURL + UPLOAD_FILE_ENDPOINT);
      request.addHeader("Cookie", cookies);
      request.addHeader(
          "Content-Disposition",
          "attachment; filename=\""
              + fullFilename
              + "\"; filename*=UTF-8''"
              + URLEncoder.encode(fullFilename, StandardCharsets.UTF_8));
      request.setProtocolVersion(new ProtocolVersion("HTTP", 1, 1));

      final InputStreamEntity body =
          new InputStreamEntity(file, fileLength, ContentType.getByMimeType(mimeType));
      request.setEntity(body);

      final CloseableHttpResponse mailboxResponse = httpClient.execute(request);
      if (mailboxResponse.getStatusLine().getStatusCode() == 200) {
        /*
        This is the mailbox response:
        '200,'null','85e4b3d9-1f41-4292-9dc8-e933194cc1f2:dbca72a2-8b05-45c5-a83f-bbae05ab907c'\n

        To extrapolate the attachment id from it, we need to:
          - Split the response by comma to get a String[] containing three elements
          - Verify the status code inside the response: it must be 200, otherwise it returns a failure
          - Remove the first two elements('200', 'null')
          - Remove the ' and the \n characters
         */
        final String[] responseBody =
            IOUtils.toString(mailboxResponse.getEntity().getContent(), StandardCharsets.UTF_8)
                .split(",");

        if ("200".equals(responseBody[0])) {
          return Arrays.stream(responseBody)
              .reduce((first, last) -> last)
              .map(aid -> aid.replaceAll("'", "").replaceAll("\n", ""))
              .map(Try::success)
              .orElseGet(
                  () ->
                      Try.failure(
                          new InternalServerErrorException(
                              "Unable to deserialize mailbox upload response")));
        }

        return Try.failure(new RequestEntityTooLargeException("Upload to mailbox failed"));
      }

      final String errorMessage =
          MessageFormat.format(
              "Upload to mailbox failed: {0} {1}",
              mailboxResponse.getStatusLine().getStatusCode(),
              mailboxResponse.getStatusLine().getReasonPhrase());
      return Try.failure(new InternalServerErrorException(errorMessage));

    } catch (Exception exception) {
      return Try.failure(new InternalServerErrorException(exception));
    }
  }
}
