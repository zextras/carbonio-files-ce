// SPDX-FileCopyrightText: 2022 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.clients;

import com.zextras.carbonio.files.rest.types.UploadToRequest.TargetModule;
import io.vavr.control.Try;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.apache.commons.io.IOUtils;
import org.apache.http.ProtocolVersion;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.mime.HttpMultipartMode;
import org.apache.http.entity.mime.MultipartEntity;
import org.apache.http.entity.mime.content.InputStreamBody;
import org.apache.http.entity.mime.content.StringBody;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;

/**
 * Http client to make http requests to the mailbox. The mailbox is reached via consul.
 */
public class MailboxHttpClient {

  private final String mailboxURL;
  private final String uploadFileEndpoint = "/service/upload?fmt=raw";

  MailboxHttpClient(String mailboxURL) {
    this.mailboxURL = mailboxURL;
  }

  public static MailboxHttpClient atURL(String mailboxURL) {
    return new MailboxHttpClient(mailboxURL);
  }

  /**
   * Allows to upload a file to the mailbox store. The attachment id returned should be attached to
   * a carbonio item (e.g. an already existing mail draft). This method can be used for the
   * following target module:
   * <ul>
   *   <li>{@link TargetModule#MAILS}</li>
   *   <li>{@link TargetModule#CALENDARS}</li>
   *   <li>{@link TargetModule#CONTACTS}</li>
   * </ul>
   *
   * @param cookies is a {@link String} representing the cookies of the requesters who wants to
   * upload the node to the mailbox.
   * @param fullFilename is a {@link String} representing the node filename with its extension.
   * @param mimeType is a {@link String} representing the mime-type of the node to upload.
   * @param file is the {@link InputStream} of the node to upload.
   *
   * @return a {@link Try} containing a {@link String} representing the mailbox attachment id
   * uploaded or a {@link Throwable} if something goes wrong.
   */
  public Try<String> uploadFile(
    String cookies,
    String fullFilename,
    String mimeType,
    InputStream file
  ) {

    try {
      CloseableHttpClient client = HttpClients.createMinimal();
      MultipartEntity multipartEntity = new MultipartEntity(HttpMultipartMode.BROWSER_COMPATIBLE);
      multipartEntity.addPart("file", new InputStreamBody(file, fullFilename));
      multipartEntity.addPart("filename", new StringBody(fullFilename));
      multipartEntity.addPart("ct", new StringBody(mimeType));

      HttpPost request = new HttpPost(mailboxURL + uploadFileEndpoint);
      request.setHeader("Cookie", cookies);
      request.setProtocolVersion(new ProtocolVersion("HTTP", 1, 1));
      request.setEntity(multipartEntity);

      CloseableHttpResponse mailboxResponse = client.execute(request);
      if (mailboxResponse.getStatusLine().getStatusCode() == 200) {
        /*
        This is the mailbox response:
        '200,'null','85e4b3d9-1f41-4292-9dc8-e933194cc1f2:dbca72a2-8b05-45c5-a83f-bbae05ab907c'\n

        To extrapolate the attachment id from it, we need to:
          - Split the response by comma to get a String[] containing three elements
          - Remove the first two elements('200', 'null')
          - Remove the ' and the \n characters
         */
        return Arrays
          .stream(IOUtils
            .toString(mailboxResponse.getEntity().getContent(), StandardCharsets.UTF_8)
            .split(",")
          )
          .reduce((first, last) -> last)
          .map(aid -> aid.replaceAll("'", "").replaceAll("\n", ""))
          .map(Try::success)
          .orElseGet(() ->
            Try.failure(new Exception("Unable to deserialize the mailbox upload response"))
          );
      }
      return Try.failure(new Exception("Upload to mailbox failed: "
        + mailboxResponse.getStatusLine().getStatusCode()
        + " "
        + mailboxResponse.getStatusLine().getReasonPhrase())
      );

    } catch (Exception exception) {
      return Try.failure(exception);
    }
  }
}
