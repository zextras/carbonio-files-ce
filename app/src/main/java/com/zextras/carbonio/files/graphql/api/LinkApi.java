// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.graphql.api;

import static com.zextras.carbonio.files.Constants.Config.Link.MAX_LINKS_PER_NODE;

import com.zextras.carbonio.files.Constants.API.Endpoints;
import com.zextras.carbonio.files.dal.dao.UserMyself;
import com.zextras.carbonio.files.dal.dao.ebean.ACL.SharePermission;
import com.zextras.carbonio.files.dal.dao.ebean.Link;
import com.zextras.carbonio.files.dal.dao.ebean.Node;
import com.zextras.carbonio.files.dal.dao.ebean.NodeType;
import com.zextras.carbonio.files.dal.repositories.impl.ebean.utilities.LinkSort;
import com.zextras.carbonio.files.dal.repositories.interfaces.LinkRepository;
import com.zextras.carbonio.files.dal.repositories.interfaces.NodeRepository;
import com.zextras.carbonio.files.graphql.auth.AuthenticatedUser;
import com.zextras.carbonio.files.graphql.errors.ErrorCodes;
import com.zextras.carbonio.files.graphql.errors.FilesGraphQLException;
import com.zextras.carbonio.files.graphql.model.LinkModel;
import com.zextras.carbonio.files.graphql.model.NodeModel;
import com.zextras.carbonio.files.graphql.model.support.NodeModelFactory;
import com.zextras.carbonio.files.graphql.validation.GraphQLInputValidator;
import com.zextras.carbonio.files.utilities.PermissionsChecker;
import io.quarkus.security.Authenticated;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.apache.commons.lang3.RandomStringUtils;
import org.eclipse.microprofile.graphql.GraphQLApi;
import org.eclipse.microprofile.graphql.Id;
import org.eclipse.microprofile.graphql.Mutation;
import org.eclipse.microprofile.graphql.Name;
import org.eclipse.microprofile.graphql.NonNull;
import org.eclipse.microprofile.graphql.Query;
import org.eclipse.microprofile.graphql.Source;

@GraphQLApi
@Authenticated
public class LinkApi {

  @Inject LinkRepository linkRepository;
  @Inject NodeRepository nodeRepository;
  @Inject PermissionsChecker permissionsChecker;
  @Inject GraphQLInputValidator validator;
  @Inject @AuthenticatedUser UserMyself requester;

  private static LinkModel toModel(Link link, String domain, boolean isFolder) {
    String url =
        isFolder
            ? domain + Endpoints.PUBLIC_LINK_ACCESS_URL + link.getPublicId()
            : domain + Endpoints.PUBLIC_LINK_DOWNLOAD_URL + link.getPublicId();
    return new LinkModel(
        link.getLinkId(),
        url,
        link.getCreatedAt(),
        link.getExpiresAt().orElse(null),
        link.getDescription().orElse(null),
        link.getAccessCode().orElse(null),
        link.getNodeId());
  }

  // ─── Single-item @Source resolvers ────────────────────────────────────────────

  @Name("links")
  @NonNull
  public List<LinkModel> links(@Source NodeModel node) {
    String me = requester.getId().getUserId();
    if (!permissionsChecker.getPermissions(node.getId(), me).has(SharePermission.READ_AND_SHARE)) {
      return List.of();
    }
    Optional<Node> optNode = nodeRepository.getNode(node.getId());
    if (optNode.isEmpty()) {
      return List.of();
    }
    boolean isFolder = optNode.get().getNodeType().equals(NodeType.FOLDER);
    String domain = requester.getDomain();
    return linkRepository
        .getLinksByNodeId(node.getId(), LinkSort.CREATED_AT_DESC)
        .map(link -> toModel(link, domain, isFolder))
        .collect(Collectors.toList());
  }

  @Name("node")
  @NonNull
  public NodeModel node(@Source LinkModel link) throws FilesGraphQLException {
    String me = requester.getId().getUserId();
    return nodeRepository
        .getNode(link.getNodeId())
        .map(n -> NodeModelFactory.from(n, null, me))
        .orElseThrow(
            () -> FilesGraphQLException.of(ErrorCodes.NODE_NOT_FOUND, "node_id", link.getNodeId()));
  }

  @Query("getLinks")
  public @NonNull List<LinkModel> getLinks(@Name("node_id") @Id @NonNull String nodeId)
      throws FilesGraphQLException {
    validator.checkNodeId(nodeId).validate();
    String me = requester.getId().getUserId();
    Optional<Node> optNode = nodeRepository.getNode(nodeId);
    if (!permissionsChecker.getPermissions(nodeId, me).has(SharePermission.READ_AND_SHARE)
        || optNode.isEmpty()) {
      return List.of();
    }
    boolean isFolder = optNode.get().getNodeType().equals(NodeType.FOLDER);
    String domain = requester.getDomain();
    return linkRepository
        .getLinksByNodeId(nodeId, LinkSort.CREATED_AT_DESC)
        .map(link -> toModel(link, domain, isFolder))
        .collect(Collectors.toList());
  }

  @Mutation("createLink")
  public @NonNull LinkModel createLink(
      @Name("node_id") @Id @NonNull String nodeId,
      @Name("expires_at") Long expiresAt,
      @Name("description") String description,
      @Name("access_code") String accessCode)
      throws FilesGraphQLException {
    validator
        .checkNodeId(nodeId)
        .checkLinkDescription(description)
        .checkLinkAccessCode(accessCode)
        .validate();
    String me = requester.getId().getUserId();
    Optional<Node> optNode = nodeRepository.getNode(nodeId);
    if (!permissionsChecker.getPermissions(nodeId, me).has(SharePermission.READ_AND_SHARE)
        || optNode.isEmpty()
        || optNode.get().getNodeType() == NodeType.ROOT) {
      throw FilesGraphQLException.of(ErrorCodes.NODE_WRITE_ERROR, "node_id", nodeId);
    }
    if (linkRepository.getLinkCountByNode(optNode.get()) >= MAX_LINKS_PER_NODE) {
      throw FilesGraphQLException.of(ErrorCodes.LINK_LIMIT_EXCEEDED, "node_id", nodeId);
    }
    String publicId = RandomStringUtils.secure().nextAlphanumeric(50);
    Link created =
        linkRepository.createLink(
            UUID.randomUUID().toString(),
            nodeId,
            publicId,
            Optional.ofNullable(expiresAt),
            Optional.ofNullable(description),
            Optional.ofNullable(accessCode));
    boolean isFolder = optNode.get().getNodeType().equals(NodeType.FOLDER);
    return toModel(created, requester.getDomain(), isFolder);
  }

  @Mutation("updateLink")
  public LinkModel updateLink(
      @Name("link_id") @Id @NonNull String linkId,
      @Name("expires_at") Long expiresAt,
      @Name("description") String description,
      @Name("access_code") String accessCode)
      throws FilesGraphQLException {
    validator
        .checkLinkId(linkId)
        .checkLinkDescription(description)
        .checkLinkAccessCode(accessCode)
        .validate();
    String me = requester.getId().getUserId();
    Link link =
        linkRepository
            .getLinkById(linkId)
            .orElseThrow(
                () -> FilesGraphQLException.of(ErrorCodes.LINK_NOT_FOUND, "link_id", linkId));
    if (!permissionsChecker
        .getPermissions(link.getNodeId(), me)
        .has(SharePermission.READ_AND_SHARE)) {
      throw FilesGraphQLException.of(ErrorCodes.LINK_NOT_FOUND, "link_id", linkId);
    }
    Optional<Node> optNode = nodeRepository.getNode(link.getNodeId());
    if (optNode.isEmpty()) {
      throw FilesGraphQLException.of(ErrorCodes.NODE_WRITE_ERROR, "node_id", link.getNodeId());
    }
    Optional.ofNullable(expiresAt).ifPresent(link::setExpiresAt);
    Optional.ofNullable(description).ifPresent(link::setDescription);
    Optional.ofNullable(accessCode).ifPresent(link::setAccessCode);
    Link updated = linkRepository.updateLink(link);
    boolean isFolder = optNode.get().getNodeType().equals(NodeType.FOLDER);
    return toModel(updated, requester.getDomain(), isFolder);
  }

  @Mutation("deleteLinks")
  public @NonNull @Id List<String> deleteLinks(
      @Name("link_ids") @NonNull @Id List<@NonNull String> linkIds) throws FilesGraphQLException {
    validator.checkLinkIds(linkIds).validate();
    String me = requester.getId().getUserId();
    List<String> toDelete = new ArrayList<>();
    List<String> failed = new ArrayList<>();
    for (String id : linkIds) {
      boolean authorized =
          linkRepository
              .getLinkById(id)
              .filter(
                  l ->
                      permissionsChecker
                          .getPermissions(l.getNodeId(), me)
                          .has(SharePermission.READ_AND_SHARE))
              .isPresent();
      if (authorized) {
        toDelete.add(id);
      } else {
        failed.add(id);
      }
    }
    linkRepository.deleteLinksBulk(toDelete);
    if (!failed.isEmpty()) {
      throw new FilesGraphQLException(
          ErrorCodes.LINK_NOT_FOUND,
          ErrorCodes.LINK_NOT_FOUND.name(),
          toDelete,
          Map.of("deleteLinks", toDelete));
    }
    return toDelete;
  }
}
