// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.graphql.api;

import com.zextras.carbonio.files.dal.dao.ebean.FileVersion;
import com.zextras.carbonio.files.dal.dao.ebean.Link;
import com.zextras.carbonio.files.dal.dao.ebean.Node;
import com.zextras.carbonio.files.dal.dao.ebean.NodeCategory;
import com.zextras.carbonio.files.dal.repositories.impl.InvalidPageTokenException;
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

    FileVersion fv =
        isFolder(node) ? null : fileVersionRepository.getLastFileVersion(node.getId()).orElse(null);
    return buildPublicNode(node, fv, gqlContext.getPath());
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

    Map<String, FileVersion> lastVersions =
        fileVersionRepository.getLastFileVersions(
            findResult.getLeft().stream().filter(n -> !isFolder(n)).map(Node::getId).toList());
    List<PublicNodeModel> nodes = new ArrayList<>();
    for (Node node : findResult.getLeft()) {
      nodes.add(
          buildPublicNode(node, lastVersions.get(node.getId()), gqlContext.getPath() + "/nodes"));
    }

    return new PublicNodePageModel(nodes, findResult.getRight());
  }

  private static boolean isFolder(Node node) {
    NodeCategory category = node.getNodeCategory();
    return category == NodeCategory.ROOT || category == NodeCategory.FOLDER;
  }

  private PublicNodeModel buildPublicNode(Node node, FileVersion fv, String path)
      throws FilesGraphQLException {
    NodeType gqlType = NodeType.valueOf(node.getNodeType().name());

    if (isFolder(node)) {
      return new PublicFolderModel(
          node.getId(), node.getCreatedAt(), node.getUpdatedAt(), node.getName(), gqlType);
    }

    if (fv == null) {
      throw FilesGraphQLErrors.dataFetching(path, "No value present");
    }

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
