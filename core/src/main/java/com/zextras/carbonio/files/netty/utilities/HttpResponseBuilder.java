// SPDX-FileCopyrightText: 2023 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.netty.utilities;

import com.zextras.carbonio.files.rest.types.BlobResponse;
import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.vavr.control.Try;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

public class HttpResponseBuilder {

  /**
   * Allows to create a {@link DefaultHttpResponse} containing all the following headers:
   *
   * <ul>
   *   <li>{@link HttpHeaderNames#CONNECTION} to close
   *   <li>{@link HttpHeaderNames#CONTENT_LENGTH} of the blob to download
   *   <li>{@link HttpHeaderNames#CONTENT_TYPE} of the blob to download
   *   <li>{@link HttpHeaderNames#CONTENT_DISPOSITION} with the filename of the blob to download
   * </ul>
   *
   * @param blobResponse is a {@link BlobResponse} containing all the related attributes of the blob
   *     to download.
   * @return a {@link HttpResponse} containing all the necessary headers of the blob to download.
   */
  public static HttpResponse createSuccessDownloadHttpResponse(BlobResponse blobResponse) {
    final String encodedFilename =
        Try.of(() -> URLEncoder.encode(blobResponse.getFilename(), StandardCharsets.UTF_8))
            .getOrElseThrow(
                failure -> {
                  String errorMessage =
                      String.format(
                          "Unable to encode node filename %s to download",
                          blobResponse.getFilename());
                  return new IllegalArgumentException(errorMessage, failure);
                });

    DefaultHttpHeaders headers = new DefaultHttpHeaders(true);
    headers.add(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
    headers.add(HttpHeaderNames.CONTENT_LENGTH, blobResponse.getSize());
    headers.add(HttpHeaderNames.CONTENT_TYPE, blobResponse.getMimeType());
    headers.add(
        HttpHeaderNames.CONTENT_DISPOSITION,
        String.format("attachment; filename*=UTF-8''%s", encodedFilename));

    return new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK, headers);
  }
}
