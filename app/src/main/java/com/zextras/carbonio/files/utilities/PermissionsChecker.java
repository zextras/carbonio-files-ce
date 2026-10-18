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
    ACL maxPermissions = getMaxPermissionsAvailable();
    Node node = nodeRepository.getNode(nodeId).orElse(null);
    Map<String, ACL> sharePermissions =
        node != null && needsShareLookup(node, userId)
            ? shareRepository
                .getShare(node.getId(), userId)
                .map(share -> Map.of(node.getId(), share.getPermissions()))
                .orElse(Map.of())
            : Map.of();
    return permissionsOf(node, userId, maxPermissions, sharePermissions);
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
    ACL maxPermissions = getMaxPermissionsAvailable();
    Map<String, Node> nodes =
        nodeRepository
            .getNodes(nodeIds.stream().distinct().toList(), Optional.empty())
            .collect(Collectors.toMap(Node::getId, n -> n));
    List<String> sharedNodeIds =
        nodes.values().stream().filter(n -> needsShareLookup(n, userId)).map(Node::getId).toList();
    Map<String, ACL> sharePermissions =
        sharedNodeIds.isEmpty()
            ? Map.of()
            : shareRepository.getShares(sharedNodeIds, userId).stream()
                .collect(Collectors.toMap(Share::getNodeId, Share::getPermissions));
    Map<String, ACL> result = new HashMap<>();
    nodeIds.forEach(
        id ->
            result.put(id, permissionsOf(nodes.get(id), userId, maxPermissions, sharePermissions)));
    return result;
  }

  private boolean needsShareLookup(Node node, String userId) {
    return !node.isHidden()
        && !node.getNodeType().equals(NodeType.ROOT)
        && !node.getOwnerId().equals(userId);
  }

  private ACL permissionsOf(
      Node node, String userId, ACL maxPermissions, Map<String, ACL> sharePermissions) {
    if (node == null || node.isHidden()) {
      return ACL.decode(ACL.NONE);
    }
    if (node.getNodeType().equals(NodeType.ROOT) || node.getOwnerId().equals(userId)) {
      return maxPermissions.lesserACL(ACL.decode(ACL.OWNER));
    }
    return maxPermissions.lesserACL(
        sharePermissions.getOrDefault(node.getId(), ACL.decode(ACL.SharePermission.NONE)));
  }
}
