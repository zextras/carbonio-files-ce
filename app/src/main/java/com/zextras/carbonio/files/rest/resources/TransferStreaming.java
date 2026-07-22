// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.rest.resources;

import com.zextras.carbonio.files.rest.services.BlobService;
import com.zextras.carbonio.files.rest.services.BlobService.ZipDownload;
import com.zextras.carbonio.files.rest.services.BlobService.ZipItem;
import com.zextras.carbonio.files.rest.types.BlobResponse;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.subscription.UniEmitter;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpServerResponse;
import jakarta.ws.rs.core.HttpHeaders;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Transport-only helpers that pump blob/ZIP bytes to a Vert.x {@link HttpServerResponse} on the
 * dedicated {@link com.zextras.carbonio.files.config.TransferPool}, with REAL backpressure (one
 * chunk per demand, no whole-blob buffering, no busy-wait). Shared by the authenticated {@link
 * BlobResource} and the {@link PublicBlobResource} so both have exactly one download-streaming
 * implementation.
 *
 * <p>The single-blob response is sent fixed-length (a {@code Content-Length} header is set BEFORE
 * the first write, which makes Vert.x emit a non-chunked response); the ZIP response has no known
 * length so it enables chunked mode explicitly ({@code setChunked(true)}) before the first write —
 * Vert.x rejects a manual write otherwise. Headers, {@code Content-Disposition} encoding and the ZIP
 * entry layout are preserved verbatim from the previous {@code BlobHttpResponses} builders.
 */
final class TransferStreaming {

  /** 64 KiB per demand — one chunk is read from the source and written per backpressure cycle. */
  private static final int CHUNK_SIZE = 64 * 1024;

  private TransferStreaming() {}

  /**
   * Sets the single-download headers ({@code Content-Type}, {@code Content-Disposition} and, when
   * known, a fixed {@code Content-Length}) then streams the blob {@link InputStream} to {@code resp}
   * on the transfer pool. The source stream is always closed (success or failure); the returned
   * {@link Uni} completes when the response is fully written and ended, or fails on any transfer
   * error (client disconnect, storages read error, pool saturation).
   */
  static Uni<Void> streamBlob(BlobResponse blob, HttpServerResponse resp, ExecutorService pool) {
    return Uni.createFrom()
        .<Void>emitter(
            emitter -> {
              try {
                pool.execute(
                    () -> {
                      try {
                        resp.putHeader(HttpHeaders.CONTENT_TYPE, blob.getMimeType());
                        resp.putHeader(
                            HttpHeaders.CONTENT_DISPOSITION,
                            BlobHttpResponses.contentDisposition(blob.getFilename()));
                        if (blob.getSize() != null) {
                          // Fixed-length (no transfer-encoding: chunked) — same as the previous
                          // Response.header(CONTENT_LENGTH, blob.getSize()).
                          resp.putHeader(
                              HttpHeaders.CONTENT_LENGTH, Long.toString(blob.getSize()));
                        } else {
                          // No known length: Vert.x rejects any manual write unless the response is
                          // either fixed-length (Content-Length) OR explicitly chunked.
                          resp.setChunked(true);
                        }
                        pumpInputStream(blob.getBlobStream(), resp);
                        emitter.complete(null);
                      } catch (Throwable failure) {
                        closeQuietly(blob.getBlobStream());
                        handleTransferFailure(resp, emitter, failure);
                      }
                    });
              } catch (RejectedExecutionException rejected) {
                // Transfer pool saturated (AbortPolicy): the storages stream is already open, so
                // close it here to avoid leaking it (the pool task that would have closed it never
                // runs).
                closeQuietly(blob.getBlobStream());
                emitter.fail(rejected);
              }
            });
  }

  /**
   * Streams a ZIP archive from a pre-resolved {@link ZipDownload} plan on the transfer pool. Sets
   * {@code Content-Type: application/zip} + {@code Content-Disposition} (no {@code Content-Length},
   * so the response is chunked). Folder entries are written as empty directory markers; file entries
   * pull their blob bytes lazily from storages via {@link BlobService#openZipEntryStream(ZipItem)} —
   * no blob is buffered in memory — and each entry stream is closed as soon as it is drained.
   */
  static Uni<Void> streamZip(
      ZipDownload zip, BlobService blobService, HttpServerResponse resp, ExecutorService pool) {
    return Uni.createFrom()
        .<Void>emitter(
            emitter -> {
              try {
                pool.execute(
                    () -> {
                      try {
                        resp.putHeader(HttpHeaders.CONTENT_TYPE, "application/zip");
                        resp.putHeader(
                            HttpHeaders.CONTENT_DISPOSITION,
                            BlobHttpResponses.contentDisposition(zip.getFilename()));
                        // No Content-Length for a streamed archive: Vert.x rejects any manual write
                        // unless the response is explicitly chunked, so enable it BEFORE the first
                        // write (the ZipOutputStream's first putNextEntry).
                        resp.setChunked(true);
                        writeZip(zip, blobService, resp);
                        emitter.complete(null);
                      } catch (Throwable failure) {
                        handleTransferFailure(resp, emitter, failure);
                      }
                    });
              } catch (RejectedExecutionException rejected) {
                emitter.fail(rejected);
              }
            });
  }

  /**
   * Writes the whole ZIP archive to {@code resp} with backpressure. Body preserved verbatim from the
   * previous {@code BlobHttpResponses.streamZip} {@code StreamingOutput}, only the sink changed: a
   * {@link BackpressuredResponseOutputStream} over the Vert.x response instead of the container's
   * {@code OutputStream}. The response is ended explicitly after the archive is finished.
   */
  private static void writeZip(ZipDownload zip, BlobService blobService, HttpServerResponse resp)
      throws IOException {
    OutputStream sink = new BackpressuredResponseOutputStream(resp);
    try (ZipOutputStream zos = new ZipOutputStream(sink)) {
      for (ZipItem item : zip.getItems()) {
        if (item.isFolder()) {
          zos.putNextEntry(new ZipEntry(item.getPath() + "/"));
          zos.closeEntry();
        } else {
          ZipEntry entry = new ZipEntry(item.getPath());
          entry.setSize(item.getSize());
          zos.putNextEntry(entry);
          try (InputStream in = blobService.openZipEntryStream(item)) {
            in.transferTo(zos);
          } catch (Exception e) {
            throw new IOException(
                "Storages failed: unable to download node with id " + item.getNodeId(), e);
          }
          zos.closeEntry();
        }
      }
      zos.finish();
    }
    awaitVertx(resp.end());
  }

  /**
   * Pumps a source {@link InputStream} to the Vert.x response one {@value #CHUNK_SIZE}-byte chunk at
   * a time, honouring the response write queue by blocking on each {@code write()} future (see
   * {@link #writeChunk}) rather than busy-waiting or driving {@code drainHandler} off the event
   * loop. The source is always closed; the response is ended after the last chunk.
   */
  private static void pumpInputStream(InputStream source, HttpServerResponse resp)
      throws IOException {
    try (InputStream in = source) {
      byte[] buffer = new byte[CHUNK_SIZE];
      int read;
      while ((read = in.read(buffer)) != -1) {
        if (read == 0) {
          continue;
        }
        byte[] chunk = new byte[read];
        System.arraycopy(buffer, 0, chunk, 0, read);
        writeChunk(resp, chunk);
      }
    }
    awaitVertx(resp.end());
  }

  /**
   * Writes one chunk to the Vert.x response and blocks the current (transfer-pool) thread on the
   * write {@link io.vertx.core.Future} until it completes. In Vert.x 4 the {@code write()} future
   * honours the response's write-queue backpressure — it completes only once the chunk has been
   * accepted/written — so blocking on it paces the producer to the (possibly slow) client WITHOUT
   * the off-event-loop {@code writeQueueFull()}/{@code drainHandler} coordination that dead-locks
   * when driven from a worker thread. A client disconnect FAILS the future (it never hangs).
   */
  private static void writeChunk(HttpServerResponse resp, byte[] chunk) throws IOException {
    awaitVertx(resp.write(Buffer.buffer(chunk)));
  }

  /** Blocks on a Vert.x {@link io.vertx.core.Future}, translating its outcome to IO semantics. */
  private static void awaitVertx(io.vertx.core.Future<Void> future) throws IOException {
    try {
      future.toCompletionStage().toCompletableFuture().get();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IOException("Interrupted while writing the HTTP response body", e);
    } catch (ExecutionException e) {
      Throwable cause = e.getCause() != null ? e.getCause() : e;
      throw new IOException("HTTP response write failed", cause);
    }
  }

  /**
   * Terminates a failed transfer correctly relative to whether the response is already committed.
   *
   * <ul>
   *   <li>If the response head has NOT been written yet (failure during header setup or before the
   *       first chunk), the status can still be set: fail the {@link Uni} so BlobExceptionMapper maps
   *       the exception to the normal 4xx/5xx.
   *   <li>If the head HAS been written (status 200 + headers + at least one chunk already sent — e.g.
   *       a mid-stream storages drop or a client disconnect), the status can no longer be changed.
   *       Stop writing, end the (truncated) body if still open, and COMPLETE the Uni — the client
   *       sees a truncated 200 rather than a broken status transition.
   * </ul>
   */
  private static void handleTransferFailure(
      HttpServerResponse resp, UniEmitter<? super Void> emitter, Throwable failure) {
    if (resp.headWritten()) {
      if (!resp.ended()) {
        try {
          resp.end();
        } catch (Exception ignored) {
          // the connection may already be gone (client disconnect) — nothing more to do
        }
      }
      emitter.complete(null);
    } else {
      emitter.fail(failure);
    }
  }

  private static void closeQuietly(InputStream stream) {
    if (stream == null) {
      return;
    }
    try {
      stream.close();
    } catch (IOException ignored) {
      // best-effort close of the storages stream on a failed/rejected transfer
    }
  }

  /**
   * An {@link OutputStream} that forwards every write to a Vert.x {@link HttpServerResponse} with the
   * same backpressure discipline as {@link #pumpInputStream}. {@link #close()} is a no-op: the
   * enclosing {@code ZipOutputStream.close()} must not end the HTTP response — the caller ends it
   * explicitly after the archive is finished.
   */
  private static final class BackpressuredResponseOutputStream extends OutputStream {

    private final HttpServerResponse resp;

    private BackpressuredResponseOutputStream(HttpServerResponse resp) {
      this.resp = resp;
    }

    @Override
    public void write(int b) throws IOException {
      write(new byte[] {(byte) b}, 0, 1);
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
      if (len == 0) {
        return;
      }
      byte[] chunk = new byte[len];
      System.arraycopy(b, off, chunk, 0, len);
      writeChunk(resp, chunk);
    }

    @Override
    public void close() {
      // Intentionally does NOT end the response; TransferStreaming.writeZip ends it explicitly.
    }
  }
}
