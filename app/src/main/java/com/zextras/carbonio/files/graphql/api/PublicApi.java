// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.graphql.api;

import com.zextras.carbonio.files.dal.dao.ebean.FileVersion;
import com.zextras.carbonio.files.dal.dao.ebean.Link;
import com.zextras.carbonio.files.dal.dao.ebean.Node;
import com.zextras.carbonio.files.dal.dao.ebean.NodeCategory;
import com.zextras.carbonio.files.dal.repositories.impl.ebean.utilities.FileVersionSort;
import com.zextras.carbonio.files.dal.repositories.interfaces.FileVersionRepository;
import com.zextras.carbonio.files.dal.repositories.interfaces.LinkRepository;
import com.zextras.carbonio.files.dal.repositories.interfaces.NodeRepository;
import com.zextras.carbonio.files.graphql.errors.ErrorCodes;
import com.zextras.carbonio.files.graphql.errors.FilesGraphQLException;
import com.zextras.carbonio.files.graphql.model.NodeType;
import com.zextras.carbonio.files.graphql.model.PublicFileModel;
import com.zextras.carbonio.files.graphql.model.PublicFolderModel;
import com.zextras.carbonio.files.graphql.model.PublicNodeModel;
import com.zextras.carbonio.files.graphql.model.PublicNodePageModel;
import com.zextras.carbonio.files.graphql.model.support.PublicNodeModelFactory;
import jakarta.annotation.security.PermitAll;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Map;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.eclipse.microprofile.graphql.GraphQLApi;
import org.eclipse.microprofile.graphql.Id;
import org.eclipse.microprofile.graphql.Name;
import org.eclipse.microprofile.graphql.NonNull;
import org.eclipse.microprofile.graphql.Query;

@GraphQLApi
public class PublicApi {

  @Inject LinkRepository linkRepository;
  @Inject NodeRepository nodeRepository;
  @Inject FileVersionRepository fileVersionRepository;

  @Query("getPublicNode")
  @PermitAll
  public PublicNodeModel getPublicNode(
      @Name("node_link_id") @NonNull String nodeLinkId, @Name("access_code") String accessCode)
      throws FilesGraphQLException {

    Link link =
        linkRepository
            .getLinkByNotExpiredPublicId(nodeLinkId)
            .orElseThrow(
                () ->
                    new FilesGraphQLException(
                        ErrorCodes.LINK_NOT_FOUND,
                        "Could not find link with id " + nodeLinkId,
                        Map.of("node_link_id", nodeLinkId)));

    Node node =
        nodeRepository
            .getNode(link.getNodeId())
            .orElseThrow(
                () ->
                    FilesGraphQLException.of(
                        ErrorCodes.NODE_NOT_FOUND, "node_id", link.getNodeId()));

    if (nodeRepository.getTrashedNode(node.getId()).isPresent()) {
      throw FilesGraphQLException.of(ErrorCodes.NODE_NOT_FOUND, "node_id", node.getId());
    }

    var linkAccessCode = link.getAccessCode();
    if (linkAccessCode.isPresent()) {
      if (accessCode == null) {
        throw new FilesGraphQLException(
            ErrorCodes.ACCESS_CODE_REQUIRED,
            "Access code is required for accessing the resource with public link id: " + nodeLinkId,
            Map.of("node_link_id", nodeLinkId));
      } else if (!linkAccessCode.get().equals(accessCode)) {
        throw new FilesGraphQLException(
            ErrorCodes.WRONG_ACCESS_CODE,
            "The access code for link with public id " + nodeLinkId + " is not correct",
            Map.of("node_link_id", nodeLinkId));
      }
    }

    return buildPublicNode(node);
  }

  @Query("findPublicNodes")
  @PermitAll
  public PublicNodePageModel findPublicNodes(
      @Name("folder_id") @Id @NonNull String folderId,
      @Name("limit") Integer limit,
      @Name("node_link_id") String nodeLinkId,
      @Name("access_code") String accessCode,
      @Name("page_token") String pageToken)
      throws FilesGraphQLException {

    var optFolder = nodeRepository.getNode(folderId);

    if (optFolder.isEmpty() || !linkRepository.isLinkValidForNode(nodeLinkId, optFolder.get())) {
      throw FilesGraphQLException.of(ErrorCodes.NODE_NOT_FOUND, "folder_id", folderId);
    }

    Link link = linkRepository.getLinkByNotExpiredPublicId(nodeLinkId).get();
    if (link.getAccessCode().isPresent() && !link.getAccessCode().get().equals(accessCode)) {
      throw FilesGraphQLException.of(ErrorCodes.ACCESS_CODE_REQUIRED, "node_link_id", nodeLinkId);
    }

    ImmutablePair<List<Node>, String> findResult =
        nodeRepository.publicFindNodes(folderId, limit, pageToken);

    List<PublicNodeModel> nodes = findResult.getLeft().stream().map(this::buildPublicNode).toList();

    return new PublicNodePageModel(nodes, findResult.getRight());
  }

  /**
   * Builds the public-facing model for a node. For file nodes, the latest file version is fetched
   * explicitly via the repository (not via the LAZY-loaded {@code node.getFileVersions()}) to avoid
   * relying on a live JPA session for lazy loading — {@link PublicNodeModelFactory} is only used
   * for folder nodes where no version fetch is needed.
   */
  private PublicNodeModel buildPublicNode(Node node) {
    NodeCategory category = node.getNodeCategory();
    NodeType gqlType = NodeType.valueOf(node.getNodeType().name());

    if (category == NodeCategory.ROOT || category == NodeCategory.FOLDER) {
      return new PublicFolderModel(
          node.getId(), node.getCreatedAt(), node.getUpdatedAt(), node.getName(), gqlType);
    }

    // Explicit fetch of the latest file version via the repository, matching the pattern used by
    // NodeApi's authenticated resolvers (they call fileVersionRepository.getFileVersions() rather
    // than node.getFileVersions(), which is LAZY and may be unloaded at this point).
    FileVersion fv =
        fileVersionRepository
            .getFileVersions(node.getId(), List.of(FileVersionSort.VERSION_DESC))
            .stream()
            .findFirst()
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "No file version found for file node " + node.getId()));

    Long nodeSize = node.getSize();
    return new PublicFileModel(
        node.getId(),
        node.getCreatedAt(),
        node.getUpdatedAt(),
        node.getName(),
        node.getExtension().orElse(null),
        gqlType,
        fv.getMimeType(),
        nodeSize != null ? (double) nodeSize : (double) fv.getSize());
  }
}
