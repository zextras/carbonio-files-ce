// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.graphql.support;

import com.zextras.carbonio.files.dal.dao.ebean.ACL;
import com.zextras.carbonio.files.dal.dao.ebean.Node;
import com.zextras.carbonio.files.dal.dao.ebean.NodeType;
import com.zextras.carbonio.files.dal.dao.ebean.Share;
import com.zextras.carbonio.files.dal.repositories.interfaces.NodeRepository;
import com.zextras.carbonio.files.dal.repositories.interfaces.ShareRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@ApplicationScoped
public class ShareCascadeHelper {

  private final NodeRepository nodeRepository;
  private final ShareRepository shareRepository;

  @Inject
  public ShareCascadeHelper(NodeRepository nodeRepository, ShareRepository shareRepository) {
    this.nodeRepository = nodeRepository;
    this.shareRepository = shareRepository;
  }

  public void cascadeUpsertShare(
      String nodeId, String userId, ACL permission, Optional<Long> expiredAt) {
    List<String> childrenIds =
        nodeRepository.getChildrenIds(nodeId, Optional.empty(), Optional.empty(), false);
    if (!childrenIds.isEmpty()) {
      List<Node> childrenNodes =
          nodeRepository.getNodes(childrenIds, Optional.empty()).collect(Collectors.toList());
      List<Node> folderNodes =
          childrenNodes.stream()
              .filter(n -> n.getNodeType() == NodeType.FOLDER)
              .collect(Collectors.toList());

      shareRepository.upsertShareBulk(childrenIds, userId, permission, false, false, expiredAt);

      folderNodes.forEach(
          folderNode -> cascadeUpsertShare(folderNode.getId(), userId, permission, expiredAt));
    }
  }

  public void cascadeDeleteShare(String nodeId, String userId) {
    // NodeRepositoryImpl#getChildrenIds returns an unmodifiable list (Stream#toList), so it must be
    // copied into a mutable one before appending the trashed children below.
    List<String> childrenIds =
        new ArrayList<>(
            nodeRepository.getChildrenIds(nodeId, Optional.empty(), Optional.empty(), true));
    List<String> trashedChildrenIds = nodeRepository.getTrashedNodeIdsByOldParent(nodeId);
    childrenIds.addAll(trashedChildrenIds);
    if (!childrenIds.isEmpty()) {
      List<Node> childrenNodes =
          nodeRepository.getNodes(childrenIds, Optional.empty()).collect(Collectors.toList());
      List<Share> shares =
          shareRepository
              .getShares(
                  childrenNodes.stream().map(Node::getId).collect(Collectors.toList()), userId)
              .stream()
              .filter(Share::isDirect)
              .collect(Collectors.toList());
      List<Node> deletableNodes =
          childrenNodes.stream()
              .filter(
                  node ->
                      shares.stream().noneMatch(share -> share.getNodeId().equals(node.getId())))
              .collect(Collectors.toList());
      List<Node> folderNodes =
          deletableNodes.stream()
              .filter(n -> n.getNodeType() == NodeType.FOLDER)
              .collect(Collectors.toList());

      shareRepository.deleteSharesBulk(
          deletableNodes.stream().map(Node::getId).collect(Collectors.toList()), userId);

      folderNodes.forEach(folderNode -> cascadeDeleteShare(folderNode.getId(), userId));
    }
  }
}
