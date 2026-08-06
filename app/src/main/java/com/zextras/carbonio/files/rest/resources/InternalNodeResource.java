// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.rest.resources;

import com.zextras.carbonio.files.dal.dao.UserId;
import com.zextras.carbonio.files.dal.dao.UserInfo;
import com.zextras.carbonio.files.dal.dao.UserMyself;
import com.zextras.carbonio.files.dal.dao.ebean.ACL;
import com.zextras.carbonio.files.dal.dao.ebean.FileVersion;
import com.zextras.carbonio.files.dal.dao.ebean.Link;
import com.zextras.carbonio.files.dal.dao.ebean.Node;
import com.zextras.carbonio.files.dal.dao.ebean.NodeType;
import com.zextras.carbonio.files.dal.repositories.interfaces.NodeRepository;
import com.zextras.carbonio.files.dal.repositories.interfaces.UserRepository;
import com.zextras.carbonio.files.graphql.datafetchers.LinkDataFetcher;
import com.zextras.carbonio.files.graphql.datafetchers.NodeDataFetcher;
import com.zextras.carbonio.files.rest.types.internal.CreateFolderRequest;
import com.zextras.carbonio.files.rest.types.internal.CreatePublicLinkRequest;
import com.zextras.carbonio.files.rest.types.internal.DeleteAllRequest;
import com.zextras.carbonio.files.rest.types.internal.DeleteAllResponse;
import com.zextras.carbonio.files.rest.types.internal.InternalNodeDto;
import com.zextras.carbonio.files.rest.types.internal.InternalNodeIdDto;
import com.zextras.carbonio.files.rest.types.internal.OwnerDto;
import com.zextras.carbonio.files.rest.types.internal.ParentDto;
import com.zextras.carbonio.files.rest.types.internal.PermissionsDto;
import com.zextras.carbonio.files.rest.types.internal.PublicLinkDto;
import com.zextras.carbonio.files.utilities.PermissionsChecker;
import io.smallrye.common.annotation.Blocking;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response.Status;
import java.util.Optional;
import org.jboss.resteasy.reactive.RestResponse;

/**
 * Trusted-caller JSON REST surface of carbonio-files: {@code /internal/**}, the sole trusted-caller
 * entry point after the WIP gRPC surface (formerly {@code FilesGrpcService}) was retired in favor
 * of this REST-only design. Every endpoint carries the acting {@code userId} explicitly (path for
 * {@code GET}, body for {@code POST}/{@code DELETE}): the caller is assumed already authenticated —
 * mesh mTLS/intentions restrict who may reach {@code /internal/**}, so NO cookie/token auth filter
 * runs here — but the given user's node ACLs are still enforced (via {@link PermissionsChecker},
 * reached through the reused business beans), exactly as the cookie-authenticated REST/GraphQL
 * paths do.
 *
 * <p>Every endpoint delegates to the same beans the REST resources and the GraphQL DataFetchers use
 * — no business logic is duplicated here:
 *
 * <ul>
 *   <li>{@code GET /internal/accounts/{userId}/nodes/{nodeId}} → {@link NodeRepository#getNode} +
 *       {@link PermissionsChecker#getPermissions}, assembled into an {@link InternalNodeDto}.
 *   <li>{@code POST /internal/folders} → {@link NodeDataFetcher#createFolder} (the extracted core
 *       of the GraphQL {@code createFolder} mutation).
 *   <li>{@code POST /internal/links} → {@link LinkDataFetcher#createPublicLink} + {@link
 *       LinkDataFetcher#buildPublicLinkUrl}, mirroring {@code FilesGrpcService#createPublicLink}.
 *   <li>{@code DELETE /internal/nodes} → {@link NodeDataFetcher#deleteAllNodesAndBlobsForUser}.
 * </ul>
 */
@Path("/internal")
@Produces(MediaType.APPLICATION_JSON)
@ApplicationScoped
public class InternalNodeResource {

  private final NodeRepository nodeRepository;
  private final UserRepository userRepository;
  private final PermissionsChecker permissionsChecker;
  private final NodeDataFetcher nodeDataFetcher;
  private final LinkDataFetcher linkDataFetcher;

  @Inject
  public InternalNodeResource(
      NodeRepository nodeRepository,
      UserRepository userRepository,
      PermissionsChecker permissionsChecker,
      NodeDataFetcher nodeDataFetcher,
      LinkDataFetcher linkDataFetcher) {
    this.nodeRepository = nodeRepository;
    this.userRepository = userRepository;
    this.permissionsChecker = permissionsChecker;
    this.nodeDataFetcher = nodeDataFetcher;
    this.linkDataFetcher = linkDataFetcher;
  }

  // -------------------------------------------------------------------------------------- getNode

  @GET
  @Path("/accounts/{userId}/nodes/{nodeId}")
  @Blocking
  public RestResponse<InternalNodeDto> getNode(
      @PathParam("userId") String userId, @PathParam("nodeId") String nodeId) {
    Optional<Node> optNode = nodeRepository.getNode(nodeId);
    if (optNode.isEmpty()) {
      return RestResponse.status(Status.NOT_FOUND);
    }

    ACL permissions = permissionsChecker.getPermissions(nodeId, userId);
    if (!permissions.canRead()) {
      return RestResponse.status(Status.FORBIDDEN);
    }

    return RestResponse.ok(toInternalNodeDto(optNode.get(), permissions));
  }

  /**
   * Assembles the {@link InternalNodeDto} for a node the requester can read. Mirrors the relevant
   * slice of {@code NodeDataFetcher#convertNodeToDataFetcherResult}: core fields come from {@link
   * Node}, but for a file the {@code mimeType}/{@code size}/{@code version}/{@code updatedAt}
   * quadruplet is resolved from its current {@link FileVersion} and — CRITICALLY — {@code
   * updatedAt} is the FILE VERSION's timestamp, not the node's, replicating the GraphQL map's
   * silent same-key override (see the class javadoc of {@link InternalNodeDto}).
   */
  private InternalNodeDto toInternalNodeDto(Node node, ACL permissions) {
    String extension = null;
    String mimeType = null;
    Long size = null;
    Integer version = null;
    long updatedAt = node.getUpdatedAt();

    if (node.getNodeType() != NodeType.FOLDER && node.getNodeType() != NodeType.ROOT) {
      extension = node.getExtension().orElse(null);

      Integer currentVersion = node.getCurrentVersion();
      Optional<FileVersion> optFileVersion =
          node.getFileVersions().stream()
              .filter(fileVersion -> currentVersion.equals(fileVersion.getVersion()))
              .findFirst();

      if (optFileVersion.isPresent()) {
        FileVersion fileVersion = optFileVersion.get();
        mimeType = fileVersion.getMimeType();
        size = fileVersion.getSize();
        version = fileVersion.getVersion();
        updatedAt = fileVersion.getUpdatedAt();
      }
    }

    OwnerDto owner = new OwnerDto(node.getOwnerId());
    ParentDto parent = node.getParentId().map(ParentDto::new).orElse(null);
    PermissionsDto permissionsDto = new PermissionsDto(permissions.canWrite());

    return new InternalNodeDto(
        node.getId(),
        node.getName(),
        extension,
        mimeType,
        size,
        version,
        updatedAt,
        owner,
        parent,
        permissionsDto);
  }

  // ------------------------------------------------------------------------------------
  // createFolder

  @POST
  @Path("/folders")
  @Consumes(MediaType.APPLICATION_JSON)
  @Blocking
  public RestResponse<InternalNodeIdDto> createFolder(CreateFolderRequest request) {
    try {
      Node createdFolder =
          nodeDataFetcher.createFolder(
              request.userId(),
              request.destinationId(),
              request.name(),
              trustedRequester(request.userId()));
      return RestResponse.ok(new InternalNodeIdDto(createdFolder.getId()));
    } catch (NodeDataFetcher.NodeAccessException e) {
      return RestResponse.status(Status.FORBIDDEN);
    } catch (NodeDataFetcher.NodeNotFoundException e) {
      return RestResponse.status(Status.NOT_FOUND);
    }
  }

  // --------------------------------------------------------------------------------
  // createPublicLink

  /**
   * Mirrors {@code FilesGrpcService#createPublicLink} exactly: the requester's domain is resolved
   * the same trusted-caller way (a direct {@link UserRepository#getUserById} call, cookie ignored),
   * then {@link LinkDataFetcher#createPublicLink} + {@link LinkDataFetcher#buildPublicLinkUrl}
   * build the link, identically to the gRPC RPC.
   */
  @POST
  @Path("/links")
  @Consumes(MediaType.APPLICATION_JSON)
  @Blocking
  public RestResponse<PublicLinkDto> createPublicLink(CreatePublicLinkRequest request) {
    String userId = request.userId();
    String nodeId = request.nodeId();

    Optional<UserInfo> requester = userRepository.getUserById(null, userId);
    if (requester.isEmpty()) {
      return RestResponse.status(Status.NOT_FOUND);
    }

    Optional<Node> node = nodeRepository.getNode(nodeId);
    if (node.isEmpty()) {
      return RestResponse.status(Status.NOT_FOUND);
    }

    try {
      Link link =
          linkDataFetcher.createPublicLink(
              userId, nodeId, Optional.empty(), Optional.empty(), Optional.empty());

      String url =
          linkDataFetcher.buildPublicLinkUrl(
              link, requester.get().getDomain(), node.get().getNodeType().equals(NodeType.FOLDER));

      return RestResponse.ok(new PublicLinkDto(url));
    } catch (LinkDataFetcher.LinkLimitReachedException e) {
      return RestResponse.status(Status.TOO_MANY_REQUESTS);
    } catch (LinkDataFetcher.NodeAccessException e) {
      return RestResponse.status(Status.FORBIDDEN);
    }
  }

  // ---------------------------------------------------------------------------
  // deleteAllNodesAndBlobs

  @DELETE
  @Path("/nodes")
  @Consumes(MediaType.APPLICATION_JSON)
  @Blocking
  public RestResponse<DeleteAllResponse> deleteAllNodesAndBlobs(DeleteAllRequest request) {
    nodeDataFetcher.deleteAllNodesAndBlobsForUser(request.userId());
    return RestResponse.ok(new DeleteAllResponse(true));
  }

  // --------------------------------------------------------------------------------------- helpers

  /**
   * Builds a minimal {@link UserMyself} carrying only the acting user's id. Copied from {@code
   * FilesGrpcService#trustedRequester} (kept private/self-contained here rather than shared, per
   * the small size of the helper): the reused folder-creation path only reads {@code
   * requester.getId().getUserId()} (for the added-node notification's "who did this" attribution),
   * so nothing else needs resolving.
   */
  private static UserMyself trustedRequester(String userId) {
    UserMyself user = new UserMyself();
    user.setId(new UserId(userId));
    return user;
  }
}
