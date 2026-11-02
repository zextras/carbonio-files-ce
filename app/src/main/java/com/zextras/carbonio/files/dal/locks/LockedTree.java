// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.dal.locks;

import com.zextras.carbonio.files.dal.dao.ebean.Node;
import com.zextras.carbonio.files.dal.dao.ebean.NodeType;
import com.zextras.carbonio.files.dal.repositories.interfaces.NodeRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.List;

@ApplicationScoped
public class LockedTree {

  private final NodeLocks nodeLocks;
  private final NodeRepository nodeRepository;

  @Inject
  public LockedTree(NodeLocks nodeLocks, NodeRepository nodeRepository) {
    this.nodeLocks = nodeLocks;
    this.nodeRepository = nodeRepository;
  }

  /** Roots must already be locked; every level is locked before it is read. */
  public List<Node> collect(List<Node> roots) {
    List<Node> result = new ArrayList<>(roots);
    List<String> frontier = folderIds(roots);
    while (!frontier.isEmpty()) {
      nodeLocks.lockChildren(frontier);
      List<Node> children = nodeRepository.getChildren(frontier, true);
      result.addAll(children);
      frontier = folderIds(children);
    }
    return result;
  }

  private static List<String> folderIds(List<Node> nodes) {
    return nodes.stream()
        .filter(node -> node.getNodeType() == NodeType.FOLDER)
        .map(Node::getId)
        .toList();
  }
}
