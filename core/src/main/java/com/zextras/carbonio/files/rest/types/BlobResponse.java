// SPDX-FileCopyrightText: 2022 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.rest.types;

import java.io.InputStream;

public class BlobResponse {

  private final String      filename;
  private final Long        size;
  private final String      mimeType;
  private       InputStream blobStream;

  public BlobResponse(
    InputStream blobStream,
    String filename,
    Long size,
    String mimeType
  ) {
    this.blobStream = blobStream;
    this.filename = filename;
    this.size = size;
    this.mimeType = mimeType;
  }

  public InputStream getBlobStream() {
    return blobStream;
  }

  public String getFilename() {
    return filename;
  }

  public Long getSize() {
    return size;
  }

  public String getMimeType() {
    return mimeType;
  }
}
