// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.graphql.api;

import com.zextras.carbonio.files.dal.dao.ebean.FileVersion;
import com.zextras.carbonio.files.dal.dao.ebean.Link;
import com.zextras.carbonio.files.dal.dao.ebean.Node;
import com.zextras.carbonio.files.dal.dao.ebean.NodeCategory;
import com.zextras.carbonio.files.dal.repositories.impl.InvalidPageTokenException;
import com.zextras.carbonio.files.dal.repositories.impl.ebean.utilities.FileVersionSort;
import com.zextras.carbonio.files.dal.repositories.interfaces.FileVersionRepository;
import com.zextras.carbonio.files.dal.repositories.interfaces.LinkRepository;
import com.zextras.carbonio.files.dal.repositories.interfaces.NodeRepository;
import com.zextras.carbonio.files.graphql.errors.FilesGraphQLErrors;
import com.zextras.carbonio.files.graphql.errors.FilesGraphQLException;
import com.zextras.carbonio.files.graphql.model.NodeType;
import com.zextras.carbonio.files.graphql.model.PublicFileModel;
import com.zextras.carbonio.files.graphql.model.PublicFolderModel;
import com.zextras.carbonio.files.graphql.model.PublicNodeModel;
import com.zextras.carbonio.files.graphql.model.PublicNodePageModel;
import io.smallrye.graphql.api.Context;
import jakarta.annotation.security.PermitAll;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.List;
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
  @Inject Context gqlContext;

  @Query("getPublicNode")
  @PermitAll
  public PublicNodeModel getPublicNode(
      @Name("node_link_id") @NonNull String nodeLinkId, @Name("access_code") String accessCode)
      throws FilesGraphQLException {

    Link link =
        linkRepository
            .getLinkByNotExpiredPublicId(nodeLinkId)
            .orElseThrow(() -> FilesGraphQLErrors.linkNotFound(nodeLinkId));

    Node node =
        nodeRepository
            .getNode(link.getNodeId())
            .orElseThrow(() -> FilesGraphQLErrors.nodeNotFound(link.getNodeId()));

    if (nodeRepository.getTrashedNode(node.getId()).isPresent()) {
      throw FilesGraphQLErrors.nodeNotFound(link.getNodeId());
    }

    var linkAccessCode = link.getAccessCode();
    if (linkAccessCode.isPresent()) {
      if (accessCode == null) {
        throw FilesGraphQLErrors.accessCodeRequired(nodeLinkId);
      } else if (!linkAccessCode.get().equals(accessCode)) {
        throw FilesGraphQLErrors.wrongAccessCode(nodeLinkId);
      }
    }

    return buildPublicNode(node, gqlContext.getPath());
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
      throw FilesGraphQLErrors.nodeNotFound(folderId);
    }

    Link link = linkRepository.getLinkByNotExpiredPublicId(nodeLinkId).get();
    if (link.getAccessCode().isPresent() && !link.getAccessCode().get().equals(accessCode)) {
      throw FilesGraphQLErrors.accessCodeRequired(nodeLinkId);
    }

    ImmutablePair<List<Node>, String> findResult;
    try {
      findResult = nodeRepository.publicFindNodes(folderId, limit, pageToken);
    } catch (InvalidPageTokenException e) {
      throw FilesGraphQLErrors.dataFetching(gqlContext.getPath(), e.getMessage());
    }

    List<PublicNodeModel> nodes = new ArrayList<>();
    for (Node node : findResult.getLeft()) {
      nodes.add(buildPublicNode(node, gqlContext.getPath() + "/nodes"));
    }

    return new PublicNodePageModel(nodes, findResult.getRight());
  }

  /**
   * Builds the public-facing model for a node. For file nodes, the latest file version is fetched
   * explicitly via the repository (not via the LAZY-loaded {@code node.getFileVersions()}) to avoid
   * relying on a live JPA session for lazy loading. Folder nodes need no version fetch.
   */
  private PublicNodeModel buildPublicNode(Node node, String path) throws FilesGraphQLException {
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
            .orElseThrow(() -> FilesGraphQLErrors.dataFetching(path, "No value present"));

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
