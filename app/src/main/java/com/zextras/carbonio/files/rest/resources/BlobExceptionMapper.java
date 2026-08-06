// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.rest.resources;

import com.zextras.carbonio.files.exceptions.AliasNotAloneInDownload;
import com.zextras.carbonio.files.exceptions.BadRequestException;
import com.zextras.carbonio.files.exceptions.FileSizeException;
import com.zextras.carbonio.files.exceptions.FileTypeMismatchException;
import com.zextras.carbonio.files.exceptions.MaxNumberOfFileVersionsException;
import com.zextras.carbonio.files.exceptions.NodeNotFoundException;
import com.zextras.carbonio.files.exceptions.RequestEntityTooLargeException;
import com.zextras.carbonio.files.exceptions.ZipGenerationException;
import jakarta.ws.rs.NotAllowedException;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import java.util.NoSuchElementException;
import java.util.concurrent.RejectedExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Maps exceptions thrown by the blob REST resources to HTTP responses, mirroring the legacy Netty
 * {@code ExceptionsHandler} status table.
 *
 * <p>Authentication failures already arrive as a {@link WebApplicationException} (401) from {@link
 * BlobAuthenticator} and are passed through unchanged. Permission denials are NOT distinguished
 * from "node not found": legacy parity (pinned by the acceptance suite, e.g. {@code
 * AuthenticatedDownloadApiIT}) requires both to collapse into the same 404 ({@code
 * NoSuchElementException}), matching the legacy stack, which surfaced both as the same {@code
 * NoSuchElementException} -> 404. See {@link BlobResource#notFoundOrForbidden}.
 */
@Provider
public class BlobExceptionMapper implements ExceptionMapper<Throwable> {

  private static final Logger logger = LoggerFactory.getLogger(BlobExceptionMapper.class);

  @Context UriInfo uriInfo;

  @Override
  public Response toResponse(Throwable exception) {
    // Legacy parity: ProcedureController (/upload-to) and PreviewController (/preview/**) are
    // Netty handlers that gate their single supported HTTP verb THEMSELVES (path-only routing,
    // in-handler method check) and fall back to a generic BadRequestException -> 400 on a
    // mismatch, never a 405. RESTEasy Reactive's own routing, by contrast, raises a genuine
    // NotAllowedException (405) whenever a path matches a resource but not the declared verb.
    // Reproduce the legacy 400 ONLY for these two legacy-matched route families -- every other
    // resource (BlobResource/PublicBlobResource/CollaborationLinkResource) had NO verb gate at
    // all in Netty (the path regex ran the handler regardless of method), so a generic 405->400
    // rewrite here would NOT be legacy parity for them and must not be applied.
    if (exception instanceof NotAllowedException && isLegacyMethodGatedRoute()) {
      Response.Status status = Response.Status.BAD_REQUEST;
      return Response.status(status).entity(statusLine(status)).type("text/plain").build();
    }

    // Auth (and any other) WebApplicationException already carries its intended response.
    if (exception instanceof WebApplicationException wae) {
      return wae.getResponse();
    }

    Throwable cause = exception.getCause() == null ? exception : exception.getCause();
    if (cause instanceof WebApplicationException wae) {
      return wae.getResponse();
    }

    Response.Status status;
    String payload;

    if (cause instanceof BadRequestException
        || cause instanceof FileTypeMismatchException
        || cause instanceof IllegalArgumentException
        || cause instanceof AliasNotAloneInDownload) {
      status = Response.Status.BAD_REQUEST;
      payload = statusLine(status);
    } else if (cause instanceof NodeNotFoundException || cause instanceof NoSuchElementException) {
      status = Response.Status.NOT_FOUND;
      payload = statusLine(status);
    } else if (cause instanceof MaxNumberOfFileVersionsException) {
      status = Response.Status.METHOD_NOT_ALLOWED;
      payload = cause.getMessage();
    } else if (cause instanceof FileSizeException
        || cause instanceof RequestEntityTooLargeException) {
      status = Response.Status.REQUEST_ENTITY_TOO_LARGE;
      payload = statusLine(status);
    } else if (cause instanceof ZipGenerationException) {
      status = Response.Status.INTERNAL_SERVER_ERROR;
      payload = cause.getMessage();
    } else if (cause instanceof RejectedExecutionException) {
      // The dedicated TransferPool (see com.zextras.carbonio.files.config.TransferPool) is bounded
      // (fixed threads + bounded queue, AbortPolicy): once BOTH are saturated, a burst of large
      // transfers must surface as a transient "the server is overloaded, retry" signal rather than
      // as a generic, retry-discouraging 500 -- 503 Service Unavailable is the correct HTTP status
      // for "temporarily cannot handle the request due to load".
      status = Response.Status.SERVICE_UNAVAILABLE;
      payload = statusLine(status);
    } else {
      status = Response.Status.INTERNAL_SERVER_ERROR;
      payload = statusLine(status);
    }

    logger.error("Failed to execute the blob request. {}", payload, cause);
    return Response.status(status).entity(payload).type("text/plain").build();
  }

  /**
   * Reproduces the legacy Netty {@code HttpResponseStatus#toString()} body format ({@code "<code>
   * <reason phrase>"}, e.g. {@code "404 Not Found"}). The legacy {@code ExceptionsHandler} used
   * this exact string as the response body, and the acceptance suite asserts it verbatim (e.g.
   * {@code AuthenticatedDownloadApiIT} expects {@code "404 Not Found"} / {@code "413 Request Entity
   * Too Large"}), so the bare reason phrase ({@code getReasonPhrase()}) alone is not enough.
   */
  private static String statusLine(Response.Status status) {
    return status.getStatusCode() + " " + status.getReasonPhrase();
  }

  /**
   * {@code true} for the two route families whose legacy Netty controller explicitly gated its
   * single supported HTTP verb in-handler (see {@link #toResponse}): {@code /upload-to} ({@code
   * ProcedureController}) and {@code /preview/**} ({@code PreviewController}).
   */
  private boolean isLegacyMethodGatedRoute() {
    if (uriInfo == null) {
      return false;
    }
    String path = uriInfo.getPath();
    String normalized = path.startsWith("/") ? path.substring(1) : path;
    return normalized.equals("upload-to") || normalized.startsWith("preview/");
  }
}
