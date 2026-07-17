// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.rest.resources;

import com.zextras.carbonio.files.rest.services.BlobService;
import com.zextras.carbonio.files.rest.services.BlobService.ZipDownload;
import com.zextras.carbonio.files.rest.services.BlobService.ZipItem;
import com.zextras.carbonio.files.rest.types.BlobResponse;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.StreamingOutput;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Shared builders for the streamed blob/ZIP HTTP responses used by both the authenticated {@link
 * BlobResource} and the {@link PublicBlobResource}. Kept transport-only (no auth, no permission
 * logic) so both resources share exactly one download-response implementation.
 */
final class BlobHttpResponses {

  private BlobHttpResponses() {}

  /** Streams a single blob: {@code Content-Type}, {@code Content-Disposition}, optional length. */
  static Response streamBlob(BlobResponse blob) {
    Response.ResponseBuilder builder =
        Response.ok(blob.getBlobStream())
            .header(HttpHeaders.CONTENT_TYPE, blob.getMimeType())
            .header(HttpHeaders.CONTENT_DISPOSITION, contentDisposition(blob.getFilename()));
    if (blob.getSize() != null) {
      builder.header(HttpHeaders.CONTENT_LENGTH, blob.getSize());
    }
    return builder.build();
  }

  /**
   * Streams a ZIP archive from a pre-resolved {@link ZipDownload} plan. Folder entries are written
   * as empty directory markers; file entries pull their blob bytes lazily from {@link Filestore} via
   * {@link BlobService#openZipEntryStream(ZipItem)} — no blob is buffered in memory.
   */
  static Response streamZip(ZipDownload zip, BlobService blobService) {
    StreamingOutput out =
        os -> {
          try (ZipOutputStream zos = new ZipOutputStream(os)) {
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
        };
    return Response.ok(out)
        .header(HttpHeaders.CONTENT_TYPE, "application/zip")
        .header(HttpHeaders.CONTENT_DISPOSITION, contentDisposition(zip.getFilename()))
        .build();
  }

  static String contentDisposition(String filename) {
    String encoded = URLEncoder.encode(filename, StandardCharsets.UTF_8);
    return String.format("attachment; filename*=UTF-8''%s", encoded);
  }
}
