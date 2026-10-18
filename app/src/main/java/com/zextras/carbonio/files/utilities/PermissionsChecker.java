// SPDX-FileCopyrightText: 2022 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.utilities;

import com.zextras.carbonio.files.dal.dao.ebean.ACL;
import com.zextras.carbonio.files.dal.dao.ebean.Node;
import com.zextras.carbonio.files.dal.dao.ebean.NodeType;
import com.zextras.carbonio.files.dal.dao.ebean.Share;
import com.zextras.carbonio.files.dal.repositories.interfaces.NodeRepository;
import com.zextras.carbonio.files.dal.repositories.interfaces.ShareRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Manages the relationship of permissions between a {@link Node} and a {@link
 * com.zextras.carbonio.files.dal.dao.UserMyself}. Thanks to the {@link NodeRepository} and the
 * {@link ShareRepository} this calculation is fast because it's done at run-time and doesn't
 * overload the database with complex request.
 */
@ApplicationScoped
public class PermissionsChecker {

  private final NodeRepository nodeRepository;
  private final ShareRepository shareRepository;

  @Inject
  public PermissionsChecker(NodeRepository nodeRepository, ShareRepository shareRepository) {
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
   * Calculates the {@link ACL} of a {@link Node} for a specific {@link
   * com.zextras.carbonio.files.dal.dao.UserMyself}.
   *
   * <p>The algorithm is very simple: if the user is the owner/creator of the node then it returns
   * the {@link ACL#OWNER} permissions, this means that the user can do anything with this node;
   * otherwise it checks if the node is shared with the user. It finds one then returns the
   * permissions specified in the related {@link Share} otherwise it returns {@link ACL#NONE} that
   * means that the user can do nothing with this node.
   *
   * @param nodeId is a {@link String} representing a node id
   * @param userId is a {@link String} representing a user id. This is the user we want to check its
   *     node permissions
   * @return a {@link ACL} containing all the permissions that the user has on the specified node.
   */
  public ACL getPermissions(String nodeId, String userId) {
    Node node = nodeRepository.getNode(nodeId).orElse(null);
    Optional<ACL> sharePermissions =
        node != null && needsShareLookup(node, userId)
            ? shareRepository.getSharePermissions(node.getId(), userId)
            : Optional.empty();
    return permissionsOf(node, userId, sharePermissions);
  }

  /**
   * Calculates the {@link ACL} of many nodes for a specific user reading the nodes and the user
   * shares on them with one query each.
   *
   * @param nodeIds the ids of the nodes, duplicates are allowed
   * @param userId the id of the user we want to check its node permissions
   * @return a map from each requested node id to the permissions of the user on it
   */
  public Map<String, ACL> getPermissions(List<String> nodeIds, String userId) {
    Map<String, Node> nodes =
        nodeRepository
            .getNodes(nodeIds.stream().distinct().toList(), Optional.empty())
            .collect(Collectors.toMap(Node::getId, n -> n));
    Map<String, ACL> permissions = getPermissionsOfNodes(nodes.values(), userId);
    Map<String, ACL> result = new HashMap<>();
    nodeIds.forEach(id -> result.put(id, permissions.getOrDefault(id, ACL.decode(ACL.NONE))));
    return result;
  }

  /**
   * Calculates the {@link ACL} of many already loaded nodes for a specific user reading only the
   * user shares on the nodes that need them with one query.
   *
   * @param nodes the nodes already loaded
   * @param userId the id of the user we want to check its node permissions
   * @return a map from the id of each node to the permissions of the user on it
   */
  public Map<String, ACL> getPermissionsOfNodes(Collection<Node> nodes, String userId) {
    List<String> sharedNodeIds =
        nodes.stream().filter(n -> needsShareLookup(n, userId)).map(Node::getId).toList();
    Map<String, ACL> sharePermissions =
        sharedNodeIds.isEmpty()
            ? Map.of()
            : shareRepository.getSharePermissions(sharedNodeIds, userId);
    Map<String, ACL> result = new HashMap<>();
    nodes.forEach(
        n ->
            result.put(
                n.getId(),
                permissionsOf(n, userId, Optional.ofNullable(sharePermissions.get(n.getId())))));
    return result;
  }

  private boolean needsShareLookup(Node node, String userId) {
    return !node.isHidden()
        && !node.getNodeType().equals(NodeType.ROOT)
        && !node.getOwnerId().equals(userId);
  }

  private ACL permissionsOf(Node node, String userId, Optional<ACL> sharePermissions) {
    if (node == null || node.isHidden()) {
      return ACL.decode(ACL.NONE);
    }
    ACL maxPermissions = getMaxPermissionsAvailable();
    if (node.getNodeType().equals(NodeType.ROOT) || node.getOwnerId().equals(userId)) {
      return maxPermissions.lesserACL(ACL.decode(ACL.OWNER));
    }
    return maxPermissions.lesserACL(sharePermissions.orElse(ACL.decode(ACL.SharePermission.NONE)));
  }
}
