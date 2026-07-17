// SPDX-FileCopyrightText: 2022 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.rest.types;

import java.io.InputStream;
import java.io.PipedInputStream;
import java.util.concurrent.atomic.AtomicBoolean;

public class BlobResponse {

  private final String      filename;
  private final Long        size;
  private final String      mimeType;
  private       InputStream blobStream;
  private final AtomicBoolean producerDone;
  private final AtomicBoolean cancelled;

  public BlobResponse(
    InputStream blobStream,
    String filename,
    Long size,
    String mimeType
  ) {
    this(blobStream, filename, size, mimeType, null, null);
  }

  public BlobResponse(
    InputStream blobStream,
    String filename,
    Long size,
    String mimeType,
    AtomicBoolean producerDone,
    AtomicBoolean cancelled
  ) {
    this.blobStream = blobStream;
    this.filename = filename;
    this.size = size;
    this.mimeType = mimeType;
    this.producerDone = producerDone;
    this.cancelled = cancelled;
  }

  public InputStream getBlobStream() {
    return blobStream;
  }

  /**
   * Returns the piped input stream if this response is backed by a pipe (ZIP downloads).
   * Returns null for direct stream responses (single file downloads).
   */
  public PipedInputStream getPipedStream() {
    return blobStream instanceof PipedInputStream ? (PipedInputStream) blobStream : null;
  }

  /**
   * Returns the flag that indicates the producer thread has finished and closed the pipe.
   * Only set for ZIP download responses. Null for single file downloads.
   */
  public AtomicBoolean getProducerDone() {
    return producerDone;
  }

  /**
   * Returns the cancellation flag shared with the producer thread.
   * Setting this to true signals the producer to stop creating the ZIP.
   * Only set for ZIP download responses. Null for single file downloads.
   */
  public AtomicBoolean getCancelled() {
    return cancelled;
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
