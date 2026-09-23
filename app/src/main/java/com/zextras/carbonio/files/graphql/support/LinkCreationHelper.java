// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.graphql.support;

import static com.zextras.carbonio.files.Constants.Config.Link.MAX_LINKS_PER_NODE;

import com.zextras.carbonio.files.Constants.API.Endpoints;
import com.zextras.carbonio.files.dal.dao.ebean.ACL.SharePermission;
import com.zextras.carbonio.files.dal.dao.ebean.Link;
import com.zextras.carbonio.files.dal.dao.ebean.Node;
import com.zextras.carbonio.files.dal.dao.ebean.NodeType;
import com.zextras.carbonio.files.dal.repositories.interfaces.LinkRepository;
import com.zextras.carbonio.files.dal.repositories.interfaces.NodeRepository;
import com.zextras.carbonio.files.utilities.PermissionsChecker;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Optional;
import java.util.UUID;
import org.apache.commons.lang3.RandomStringUtils;

/**
 * Shared public-link business logic, originally extracted from the retired {@code LinkDataFetcher}
 * (schema-first graphql-java). Both the code-first GraphQL layer ({@code LinkApi @GraphQLApi}) and
 * the trusted-caller REST surface ({@code InternalNodeResource}: {@code POST /internal/links})
 * delegate to this bean so the creation logic lives in exactly one place.
 */
@ApplicationScoped
public class LinkCreationHelper {

  private final LinkRepository linkRepository;
  private final NodeRepository nodeRepository;
  private final PermissionsChecker permissionsChecker;

  @Inject
  public LinkCreationHelper(
      LinkRepository linkRepository,
      NodeRepository nodeRepository,
      PermissionsChecker permissionsChecker) {
    this.linkRepository = linkRepository;
    this.nodeRepository = nodeRepository;
    this.permissionsChecker = permissionsChecker;
  }

  /**
   * Builds the public URL of a {@link Link}: {@code
   * <domain>/<access-or-download-endpoint>/<publicId>}.
   *
   * @param link the created/loaded link
   * @param requesterDomain the requester's domain (URL prefix)
   * @param isNodeAFolder whether the linked node is a folder (access URL) or a file (download URL)
   */
  public String buildPublicLinkUrl(Link link, String requesterDomain, boolean isNodeAFolder) {
    return (isNodeAFolder)
        ? requesterDomain + Endpoints.PUBLIC_LINK_ACCESS_URL + link.getPublicId()
        : requesterDomain + Endpoints.PUBLIC_LINK_DOWNLOAD_URL + link.getPublicId();
  }

  /**
   * Enforces READ_AND_SHARE, checks the node exists and is not a {@link NodeType#ROOT}, enforces
   * the per-node link cap, then creates the public link and returns it.
   *
   * @throws LinkLimitReachedException if the node already has {@link
   *     com.zextras.carbonio.files.Constants.Config.Link#MAX_LINKS_PER_NODE} links
   * @throws NodeAccessException if the requester lacks READ_AND_SHARE, or the node is missing / a
   *     root
   */
  public Link createPublicLink(
      String requesterId,
      String nodeId,
      Optional<Long> optExpiresAt,
      Optional<String> optDescription,
      Optional<String> optAccessCode) {
    Optional<Node> optNode = nodeRepository.getNode(nodeId);
    if (permissionsChecker.getPermissions(nodeId, requesterId).has(SharePermission.READ_AND_SHARE)
        && optNode.isPresent()
        && optNode.get().getNodeType() != NodeType.ROOT) {
      if (linkRepository.getLinkCountByNode(optNode.get()) >= MAX_LINKS_PER_NODE) {
        throw new LinkLimitReachedException(nodeId);
      }

      String publicId = RandomStringUtils.secure().nextAlphanumeric(50);

      return linkRepository.createLink(
          UUID.randomUUID().toString(),
          nodeId,
          publicId,
          optExpiresAt,
          optDescription,
          optAccessCode);
    }

    throw new NodeAccessException(nodeId);
  }

  /** Thrown by {@link #createPublicLink} when a node already has the maximum number of links. */
  public static class LinkLimitReachedException extends RuntimeException {
    public LinkLimitReachedException(String nodeId) {
      super("Link limit reached for node " + nodeId);
    }
  }

  /**
   * Thrown by {@link #createPublicLink} when the requester lacks the READ_AND_SHARE permission, or
   * the target node does not exist / is a root.
   */
  public static class NodeAccessException extends RuntimeException {
    public NodeAccessException(String nodeId) {
      super(
          "Cannot create link for node "
              + nodeId
              + ": missing READ_AND_SHARE permission, node not found, or node is a root");
    }
  }
}
