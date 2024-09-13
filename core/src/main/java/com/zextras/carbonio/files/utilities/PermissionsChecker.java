// SPDX-FileCopyrightText: 2022 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.utilities;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.zextras.carbonio.files.dal.dao.User;
import com.zextras.carbonio.files.dal.dao.ebean.ACL;
import com.zextras.carbonio.files.dal.dao.ebean.Node;
import com.zextras.carbonio.files.dal.dao.ebean.NodeType;
import com.zextras.carbonio.files.dal.dao.ebean.Share;
import com.zextras.carbonio.files.dal.repositories.interfaces.NodeRepository;
import com.zextras.carbonio.files.dal.repositories.interfaces.ShareRepository;

/**
 * Manages the relationship of permissions between a {@link Node} and a {@link User}. Thanks to the
 * {@link NodeRepository} and the {@link ShareRepository} this calculation is fast because it's done
 * at run-time and doesn't overload the database with complex request.
 */
@Singleton
public class PermissionsChecker {

  private final NodeRepository  nodeRepository;
  private final ShareRepository shareRepository;

  @Inject
  public PermissionsChecker(
    NodeRepository nodeRepository,
    ShareRepository shareRepository
  ) {
    this.nodeRepository = nodeRepository;
    this.shareRepository = shareRepository;
  }

  /**
   * @return the {@link ACL#OWNER} permissions.
   */
  private ACL getMaxPermissionsAvailable() {
    return ACL.decode(ACL.OWNER);
  }

  /**
   * <p>Calculates the {@link ACL} of a {@link Node} for a specific {@link User}.</p>
   * <p>The algorithm is very simple: if the user is the owner/creator of the node then it returns
   * the {@link ACL#OWNER} permissions, this means that the user can do anything with this node;
   * otherwise it checks if the node is shared with the user. It finds one then returns the
   * permissions specified in the related {@link Share} otherwise it returns {@link ACL#NONE} that
   * means that the user can do nothing with this node.</p>
   *
   * @param nodeId is a {@link String} representing a node id
   * @param userId is a {@link String} representing a user id. This is the user we want to check its
   * node permissions
   *
   * @return a {@link ACL} containing all the permissions that the user has on the specified node.
   */
  public ACL getPermissions(
    String nodeId,
    String userId
  ) {
    ACL maxPermissions = getMaxPermissionsAvailable();
    return nodeRepository
      .getNode(nodeId)
      .map(node -> {
        if (!node.isHidden()) {
          if (node.getNodeType().equals(NodeType.ROOT) || node.getOwnerId().equals(userId)) {
            return maxPermissions.lesserACL(ACL.decode(ACL.OWNER));
          } else {
            return maxPermissions.lesserACL(
                shareRepository
                    .getShare(node.getId(), userId)
                    .map(Share::getPermissions)
                    .orElse(ACL.decode(ACL.SharePermission.NONE))
            );
          }
        } else {
          return ACL.decode(ACL.NONE);
        }
      }).orElse(ACL.decode(ACL.NONE));
  }
}
