// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.graphql.support;

import com.zextras.carbonio.files.dal.dao.ebean.ACL;
import com.zextras.carbonio.files.dal.dao.ebean.Node;
import com.zextras.carbonio.files.dal.dao.ebean.NodeType;
import com.zextras.carbonio.files.dal.dao.ebean.Share;
import com.zextras.carbonio.files.dal.locks.NodeLocks;
import com.zextras.carbonio.files.dal.repositories.interfaces.NodeRepository;
import com.zextras.carbonio.files.dal.repositories.interfaces.ShareRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@ApplicationScoped
public class ShareCascadeHelper {

  private final NodeRepository nodeRepository;
  private final ShareRepository shareRepository;
  private final NodeLocks nodeLocks;

  @Inject
  public ShareCascadeHelper(
      NodeRepository nodeRepository, ShareRepository shareRepository, NodeLocks nodeLocks) {
    this.nodeRepository = nodeRepository;
    this.shareRepository = shareRepository;
    this.nodeLocks = nodeLocks;
  }

  public void cascadeUpsertShare(
      String nodeId, String userId, ACL permission, Optional<Long> expiredAt) {
    List<String> frontier = List.of(nodeId);
    while (!frontier.isEmpty()) {
      nodeLocks.lockChildren(frontier);
      List<Node> children = nodeRepository.getChildren(frontier, false);
      shareRepository.upsertShareBulk(
          children.stream().map(Node::getId).toList(), userId, permission, false, false, expiredAt);
      frontier = folderIds(children);
    }
  }

  public void cascadeDeleteShare(String nodeId, String userId) {
    List<String> frontier = List.of(nodeId);
    while (!frontier.isEmpty()) {
      nodeLocks.lockChildren(frontier);
      Map<String, Node> level = new LinkedHashMap<>();
      nodeRepository.getChildren(frontier, true).forEach(node -> level.put(node.getId(), node));

      nodeLocks.lockRows(trashedChildrenOf(frontier));
      List<String> trashedIds =
          trashedChildrenOf(frontier).stream().filter(id -> !level.containsKey(id)).toList();
      nodeRepository
          .getNodes(trashedIds, Optional.empty())
          .forEach(node -> level.put(node.getId(), node));

      if (level.isEmpty()) {
        return;
      }
      Set<String> directlyShared =
          shareRepository.getShares(List.copyOf(level.keySet()), userId).stream()
              .filter(Share::isDirect)
              .map(Share::getNodeId)
              .collect(Collectors.toSet());
      List<Node> deletableNodes =
          level.values().stream().filter(node -> !directlyShared.contains(node.getId())).toList();

      shareRepository.deleteSharesBulk(deletableNodes.stream().map(Node::getId).toList(), userId);
      frontier = folderIds(deletableNodes);
    }
  }

  private List<String> trashedChildrenOf(List<String> folderIds) {
    return nodeRepository.getTrashedNodeIdsByOldParents(folderIds).stream().distinct().toList();
  }

  private static List<String> folderIds(List<Node> nodes) {
    return nodes.stream()
        .filter(node -> node.getNodeType() == NodeType.FOLDER)
        .map(Node::getId)
        .toList();
  }
}
