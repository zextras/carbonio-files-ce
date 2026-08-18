// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.rest.resources;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.zextras.carbonio.files.Constants.API.Headers;
import com.zextras.carbonio.files.dal.dao.UserMyself;
import com.zextras.carbonio.files.dal.dao.ebean.ACL.SharePermission;
import com.zextras.carbonio.files.exceptions.RequestEntityTooLargeException;
import com.zextras.carbonio.files.rest.services.ProcedureService;
import com.zextras.carbonio.files.rest.types.UploadAttachmentResponse;
import com.zextras.carbonio.files.rest.types.UploadToRequest;
import com.zextras.carbonio.files.utilities.PermissionsChecker;
import io.smallrye.common.annotation.Blocking;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.CookieParam;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.io.InputStream;
import java.text.MessageFormat;
import java.util.NoSuchElementException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@code /upload-to} procedure endpoint: uploads a node's blob to an external module (mailbox
 * mail/calendar/contacts attachment). Quarkus/JAX-RS port of the legacy Netty {@code
 * ProcedureController}: same permission check, same JSON request/response shape, same status-code
 * semantics — a missing node OR a permission denial both collapse to 404 ({@link
 * NoSuchElementException}, matching the legacy {@code NodeNotFoundException}); a folder target
 * surfaces as 400 ({@link IllegalArgumentException}, matching the legacy {@code
 * BadRequestException}); everything else falls through to {@link BlobExceptionMapper}'s default
 * 500. Served at ROOT (see {@link BlobResource}).
 */
@Path("/upload-to")
@ApplicationScoped
public class ProcedureResource {

  private static final Logger logger = LoggerFactory.getLogger(ProcedureResource.class);
  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  // Legacy parity: the Netty pipeline's HttpObjectAggregator(256 * 1024) rejected an oversized
  // /upload-to body BEFORE the request ever reached auth-handler/procedure-handler. The body is
  // read as a raw InputStream (see readBoundedUtf8) BEFORE authentication so the cap is enforced
  // on actual bytes, not a trusted Content-Length header (bypassed by chunked transfer-encoding).
  private static final long UPLOAD_TO_MAX_BODY_SIZE_BYTES = 256L * 1024;

  private final ProcedureService procedureService;
  private final PermissionsChecker permissionsChecker;
  private final BlobAuthenticator authenticator;

  @Inject
  public ProcedureResource(
      ProcedureService procedureService,
      PermissionsChecker permissionsChecker,
      BlobAuthenticator authenticator) {
    this.procedureService = procedureService;
    this.permissionsChecker = permissionsChecker;
    this.authenticator = authenticator;
  }

  @POST
  @Blocking
  @Consumes(MediaType.APPLICATION_JSON)
  @Produces(MediaType.APPLICATION_JSON)
  public Response uploadTo(
      @HeaderParam("Cookie") String cookieHeader,
      @CookieParam(Headers.COOKIE_ZM_AUTH_TOKEN) String zmToken,
      InputStream requestBody) {

    String jsonBody;
    try {
      jsonBody = RequestBodyLimits.readBoundedUtf8(requestBody, UPLOAD_TO_MAX_BODY_SIZE_BYTES);
    } catch (RequestEntityTooLargeException e) {
      // Legacy parity: the Netty HttpObjectAggregator(256 * 1024) rejected an oversized body with
      // an
      // EMPTY 413 (before auth), so return an empty 413 here rather than BlobExceptionMapper's
      // body.
      return Response.status(413).build();
    }
    UserMyself requester = authenticator.requireUser(cookieHeader, zmToken);
    String requesterCookies =
        (cookieHeader != null && !cookieHeader.isBlank())
            ? cookieHeader
            : Headers.COOKIE_ZM_AUTH_TOKEN + "=" + zmToken;

    // Legacy parity: a malformed JSON body throws JsonProcessingException, which the legacy
    // ExceptionsHandler mapped to 500 (NOT 400) — left uncaught here so BlobExceptionMapper's
    // default branch produces the same 500.
    UploadToRequest bodyRequest;
    try {
      bodyRequest = OBJECT_MAPPER.readValue(jsonBody, new TypeReference<>() {});
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new RuntimeException("Unable to deserialize the upload-to request body", e);
    }

    if (!permissionsChecker
        .getPermissions(bodyRequest.getNodeId().toString(), requester.getId().getUserId())
        .has(SharePermission.READ_ONLY)) {
      logger.error(
          MessageFormat.format("Request node {0} to upload not found", bodyRequest.getNodeId()));
      throw new NoSuchElementException("Node " + bodyRequest.getNodeId() + " not found");
    }

    String attachmentId =
        procedureService
            .uploadToModule(
                bodyRequest.getNodeId(), bodyRequest.getTargetModule(), requester, requesterCookies)
            .getOrElseThrow(
                failure -> {
                  if (failure instanceof RuntimeException runtimeException) {
                    return runtimeException;
                  }
                  return new RuntimeException(failure);
                });

    logger.info(
        MessageFormat.format(
            "Uploaded node {0} to {1}. The attachment id is: {2}",
            bodyRequest.getNodeId(), bodyRequest.getTargetModule(), attachmentId));

    return uploadAttachmentResponse(attachmentId);
  }

  private static Response uploadAttachmentResponse(String attachmentId) {
    try {
      String json =
          OBJECT_MAPPER
              .copy()
              .configure(SerializationFeature.FAIL_ON_EMPTY_BEANS, false)
              .writeValueAsString(new UploadAttachmentResponse(attachmentId));
      return Response.ok(json).type(MediaType.APPLICATION_JSON).build();
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new RuntimeException(e);
    }
  }
}
