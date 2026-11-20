// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.rest.resources;

import com.zextras.carbonio.files.Constants.API.Headers;
import com.zextras.carbonio.files.dal.dao.UserMyself;
import com.zextras.carbonio.files.dal.dao.ebean.Node;
import com.zextras.carbonio.files.rest.services.CollaborationLinkService;
import io.smallrye.common.annotation.Blocking;
import io.vavr.control.Try;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.CookieParam;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Response;
import java.text.MessageFormat;
import java.util.NoSuchElementException;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponses;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The collaboration-link invitation endpoint: {@code GET /invite/{invitationId}}. Quarkus/JAX-RS
 * port of the legacy Netty {@code CollaborationLinkController}: same path pattern (an 8
 * word-character invitation id, matching the legacy {@code
 * Constants.API.Endpoints#COLLABORATION_LINK} regex), same delegation to {@link
 * CollaborationLinkService#createShareByInvitationId}, same 307 redirect to the Files webapp on
 * success. Resources are served at ROOT (carbonio-proxy strips its {@code /services/files} prefix,
 * see {@link BlobResource}), so no {@code quarkus.rest.path} is set.
 *
 * <p>On failure (unknown invitation id or dangling node reference) it throws a plain {@link
 * NoSuchElementException}, mapped to HTTP 404 by the global {@link BlobExceptionMapper} — same
 * outcome as the legacy controller's {@code context.fireExceptionCaught(new
 * NoSuchElementException())}. Authentication failures surface as 401 via {@link BlobAuthenticator},
 * matching the blob REST endpoints.
 */
@Path("/")
@ApplicationScoped
@APIResponses({
  @APIResponse(responseCode = "401", description = "Missing or invalid authentication cookie"),
  @APIResponse(
      responseCode = "403",
      description = "User not allowed (inactive, guest or feature disabled)")
})
public class CollaborationLinkResource {

  private static final Logger logger = LoggerFactory.getLogger(CollaborationLinkResource.class);

  private final CollaborationLinkService collaborationLinkService;
  private final BlobAuthenticator authenticator;

  @Inject
  public CollaborationLinkResource(
      CollaborationLinkService collaborationLinkService, BlobAuthenticator authenticator) {
    this.collaborationLinkService = collaborationLinkService;
    this.authenticator = authenticator;
  }

  @GET
  @Path("/invite/{invitationId:\\w{8}}")
  @Blocking
  @APIResponses({
    @APIResponse(responseCode = "307", description = "Redirect to the invitation target"),
    @APIResponse(
        responseCode = "404",
        description = "Node, link or version not found or not accessible")
  })
  public Response invite(
      @HeaderParam("Cookie") @Parameter(required = false) String cookieHeader,
      @CookieParam(Headers.COOKIE_ZM_AUTH_TOKEN) @Parameter(required = false) String zmToken,
      @PathParam("invitationId") String invitationId) {

    UserMyself requester = authenticator.requireUser(cookieHeader, zmToken);

    Try<Node> result =
        collaborationLinkService.createShareByInvitationId(
            invitationId, requester.getId().getUserId());

    if (result.isFailure()) {
      logger.error(
          MessageFormat.format("Unable to create the share from invitation id {0}", invitationId),
          result.getCause());
      throw new NoSuchElementException();
    }

    Node sharedNode = result.get();
    String nodeInternalURL =
        MessageFormat.format(
            "{0}/carbonio/files/?file={1}&node={1}&tab=sharing",
            requester.getDomain(), sharedNode.getId());

    return Response.status(Response.Status.TEMPORARY_REDIRECT)
        .header(HttpHeaders.LOCATION, nodeInternalURL)
        .build();
  }
}
