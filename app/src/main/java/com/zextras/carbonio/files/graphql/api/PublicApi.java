// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.graphql.api;

import com.zextras.carbonio.files.dal.dao.ebean.Link;
import com.zextras.carbonio.files.dal.dao.ebean.Node;
import com.zextras.carbonio.files.dal.repositories.interfaces.LinkRepository;
import com.zextras.carbonio.files.dal.repositories.interfaces.NodeRepository;
import com.zextras.carbonio.files.graphql.errors.ErrorCodes;
import com.zextras.carbonio.files.graphql.errors.FilesGraphQLException;
import com.zextras.carbonio.files.graphql.model.PublicNodeModel;
import com.zextras.carbonio.files.graphql.model.PublicNodePageModel;
import com.zextras.carbonio.files.graphql.model.support.PublicNodeModelFactory;
import jakarta.annotation.security.PermitAll;
import jakarta.inject.Inject;
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
                    FilesGraphQLException.of(
                        ErrorCodes.LINK_NOT_FOUND, "node_link_id", nodeLinkId));

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
        throw FilesGraphQLException.of(ErrorCodes.ACCESS_CODE_REQUIRED, "node_link_id", nodeLinkId);
      } else if (!linkAccessCode.get().equals(accessCode)) {
        throw FilesGraphQLException.of(ErrorCodes.WRONG_ACCESS_CODE, "node_link_id", nodeLinkId);
      }
    }

    return PublicNodeModelFactory.from(node);
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

    List<PublicNodeModel> nodes =
        findResult.getLeft().stream().map(PublicNodeModelFactory::from).toList();

    return new PublicNodePageModel(nodes, findResult.getRight());
  }
}
