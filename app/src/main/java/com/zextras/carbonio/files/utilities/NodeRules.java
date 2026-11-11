// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.utilities;

import com.zextras.carbonio.files.Constants.Db.RootId;
import com.zextras.carbonio.files.dal.dao.ebean.Node;
import com.zextras.carbonio.files.dal.dao.ebean.NodeType;
import com.zextras.carbonio.files.dal.dao.ebean.Share;
import com.zextras.carbonio.files.dal.repositories.interfaces.ShareRepository;
import java.util.Collections;
import java.util.List;

// Roots are shared rows granting OWNER to every user: reject them where one user's data is meant.
public final class NodeRules {

  private NodeRules() {}

  public static boolean isRoot(Node node) {
    return NodeType.ROOT.equals(node.getNodeType());
  }

  public static boolean isRootId(String nodeId) {
    String id = nodeId == null ? null : nodeId.strip();
    return RootId.LOCAL_ROOT.equals(id) || RootId.TRASH_ROOT.equals(id);
  }

  public static long trashRetentionCutoff(long now, long retentionDays) {
    return now - (retentionDays * 86400 * 1000);
  }

  public static boolean isPastTrashRetention(long updatedAt, long retentionCutoff) {
    return updatedAt < retentionCutoff;
  }

  public static boolean isInTrash(Node node) {
    return node.getAncestorsList().contains(RootId.TRASH_ROOT);
  }

  public static boolean acceptsChildren(Node node) {
    if (isInTrash(node)) {
      return false;
    }
    return NodeType.FOLDER.equals(node.getNodeType())
        || (isRoot(node) && !RootId.TRASH_ROOT.equals(node.getId()));
  }

  public static List<Share> inheritableShares(ShareRepository shareRepository, String parentId) {
    return isRootId(parentId)
        ? List.of()
        : shareRepository.getShares(parentId, Collections.emptyList());
  }
}
