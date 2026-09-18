// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.graphql.support;

import static com.zextras.carbonio.files.utilities.RenameNodeUtils.searchAlternativeName;

import com.zextras.carbonio.files.config.FilesConfig;
import com.zextras.carbonio.files.dal.dao.UserMyself;
import com.zextras.carbonio.files.dal.dao.ebean.ACL.SharePermission;
import com.zextras.carbonio.files.dal.dao.ebean.FileVersion;
import com.zextras.carbonio.files.dal.dao.ebean.Node;
import com.zextras.carbonio.files.dal.dao.ebean.NodeType;
import com.zextras.carbonio.files.dal.repositories.impl.ebean.utilities.AddedNodeType;
import com.zextras.carbonio.files.dal.repositories.impl.ebean.utilities.FileVersionSort;
import com.zextras.carbonio.files.dal.repositories.interfaces.FileVersionRepository;
import com.zextras.carbonio.files.dal.repositories.interfaces.NodeRepository;
import com.zextras.carbonio.files.dal.repositories.interfaces.NotificationRepository;
import com.zextras.carbonio.files.dal.repositories.interfaces.ShareRepository;
import com.zextras.carbonio.files.dal.repositories.interfaces.TombstoneRepository;
import com.zextras.carbonio.files.utilities.PermissionsChecker;
import com.zextras.filestore.api.Filestore;
import com.zextras.filestore.model.BulkDeleteRequestItem;
import com.zextras.filestore.model.BulkDeleteResponseItem;
import com.zextras.filestore.model.IdentifierType;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Trusted-caller-reusable node business logic, extracted verbatim from the retired {@code
 * NodeDataFetcher} (schema-first graphql-java) so it survives the code-first cutover. The GraphQL
 * layer (code-first {@code NodeApi} {@code @GraphQLApi}) keeps its own request-scoped copies of
 * these operations; this bean is the single home for the identical logic reused by the
 * trusted-caller REST surface ({@code InternalNodeResource}: {@code POST /internal/folders} and
 * {@code DELETE /internal/nodes}), which carries the acting {@code userId} explicitly instead of
 * resolving it from the authenticated cookie.
 */
@ApplicationScoped
public class NodeCreationHelper {

  private static final Logger logger = LoggerFactory.getLogger(NodeCreationHelper.class);

  private final NodeRepository nodeRepository;
  private final ShareRepository shareRepository;
  private final NotificationRepository notificationRepository;
  private final FilesConfig filesConfig;
  private final FileVersionRepository fileVersionRepository;
  private final TombstoneRepository tombstoneRepository;
  private final Filestore fileStore;
  private final PermissionsChecker permissionsChecker;
  private final ShareCascadeHelper shareCascade;

  @Inject
  public NodeCreationHelper(
      NodeRepository nodeRepository,
      ShareRepository shareRepository,
      NotificationRepository notificationRepository,
      FilesConfig filesConfig,
      FileVersionRepository fileVersionRepository,
      TombstoneRepository tombstoneRepository,
      Filestore fileStore,
      PermissionsChecker permissionsChecker,
      ShareCascadeHelper shareCascade) {
    this.nodeRepository = nodeRepository;
    this.shareRepository = shareRepository;
    this.notificationRepository = notificationRepository;
    this.filesConfig = filesConfig;
    this.fileVersionRepository = fileVersionRepository;
    this.tombstoneRepository = tombstoneRepository;
    this.fileStore = fileStore;
    this.permissionsChecker = permissionsChecker;
    this.shareCascade = shareCascade;
  }

  /**
   * Enforces READ_AND_WRITE on the parent, requires the parent to be a {@link NodeType#FOLDER} or
   * {@link NodeType#ROOT}, computes the new folder's owner, de-duplicates the requested name
   * against existing siblings, creates the folder, propagates inherited shares onto it, and fires
   * the added-node notification (gated on {@link FilesConfig#areNotificationsEnabled()}).
   *
   * @throws NodeAccessException if the requester lacks READ_AND_WRITE on the parent
   * @throws NodeNotFoundException if the parent does not exist, or is neither a FOLDER nor a ROOT
   */
  public Node createFolder(String requesterId, String parentId, String name, UserMyself requester) {
    if (!permissionsChecker
        .getPermissions(parentId, requesterId)
        .has(SharePermission.READ_AND_WRITE)) {
      throw new NodeAccessException(parentId);
    }

    Node parent =
        nodeRepository
            .getNode(parentId)
            .filter(
                p ->
                    NodeType.FOLDER.equals(p.getNodeType())
                        || NodeType.ROOT.equals(p.getNodeType()))
            .orElseThrow(() -> new NodeNotFoundException(parentId));

    String ownerId =
        (NodeType.ROOT.equals(parent.getNodeType()) || requesterId.equals(parent.getOwnerId()))
            ? requesterId
            : parent.getOwnerId();

    String folderName = searchAlternativeName(nodeRepository, name.trim(), parent.getId(), ownerId);

    final Node createdFolder =
        nodeRepository.createNewNode(
            UUID.randomUUID().toString(),
            requesterId,
            ownerId,
            parent.getId(),
            folderName,
            "",
            NodeType.FOLDER,
            NodeType.ROOT.equals(parent.getNodeType())
                ? parentId
                : parent.getAncestorIds() + "," + parentId,
            0L);

    List<String> usersToNotify = createIndirectShare(parentId, createdFolder);
    usersToNotify.remove(requesterId);

    if (!parent.getNodeType().equals(NodeType.ROOT)
        && !requesterId.equals(parent.getOwnerId())
        && !usersToNotify.contains(parent.getOwnerId())) {
      usersToNotify.add(parent.getOwnerId());
    }

    if (!usersToNotify.isEmpty() && filesConfig.areNotificationsEnabled()) {
      notificationRepository.createAddedNodeNotification(
          createdFolder, parent, requester, AddedNodeType.CREATE, usersToNotify);
    }

    return createdFolder;
  }

  /**
   * Purges every non-root node owned by {@code userId}, including their blobs on Storages. DB rows
   * (tombstones + node/version deletes + folder cascade) are committed in a single transaction
   * FIRST; only AFTER commit is the best-effort storages {@code bulkDelete} attempted, so no blob
   * is ever orphaned by a failed metadata delete. Tombstones of blobs that storages fails to delete
   * are kept for the purge retry.
   *
   * @throws RuntimeException if the DB transaction fails (caller must treat as a rollback)
   */
  public void deleteAllNodesAndBlobsForUser(String userId) {
    List<Node> allNodes =
        nodeRepository.findNodesByOwner(userId).stream()
            .filter(Objects::nonNull)
            .filter(node -> !node.getNodeType().equals(NodeType.ROOT))
            .toList();

    List<Node> fileNodes =
        allNodes.stream().filter(node -> !node.getNodeType().equals(NodeType.FOLDER)).toList();

    Map<String, List<FileVersion>> fileVersionsByOwner = new HashMap<>();
    fileNodes.forEach(
        node -> {
          List<FileVersion> versions =
              fileVersionRepository.getFileVersions(
                  node.getId(), List.of(FileVersionSort.VERSION_ASC));
          fileVersionsByOwner
              .computeIfAbsent(node.getOwnerId(), k -> new ArrayList<>())
              .addAll(versions);
        });

    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              fileVersionsByOwner.forEach(
                  (ownerId, versions) ->
                      tombstoneRepository.createTombstonesBulk(versions, ownerId));
              deleteNodes(allNodes);
              allNodes.stream()
                  .filter(node -> node.getNodeType().equals(NodeType.FOLDER))
                  .map(Node::getId)
                  .forEach(this::cascadeDeleteNode);
            });

    fileVersionsByOwner.forEach(
        (ownerId, versions) -> {
          List<BulkDeleteRequestItem> deleteRequests =
              versions.stream()
                  .map(fv -> BulkDeleteRequestItem.filesItem(fv.getNodeId(), fv.getVersion()))
                  .collect(Collectors.toList());
          if (deleteRequests.isEmpty()) return;
          List<BulkDeleteResponseItem> failedItems;
          try {
            failedItems = fileStore.bulkDelete(IdentifierType.files, ownerId, deleteRequests);
          } catch (Exception e) {
            logger.warn(
                "Bulk delete failed for owner {}: {}. Tombstones remain for retry.",
                ownerId,
                e.getMessage());
            return;
          }
          if (failedItems == null) {
            logger.warn(
                "Bulk delete returned null for owner {} (treated as failure). Tombstones remain for"
                    + " retry.",
                ownerId);
            return;
          }
          Set<String> failedNodeIds =
              failedItems.stream().map(BulkDeleteResponseItem::getNode).collect(Collectors.toSet());
          versions.stream()
              .filter(fv -> !failedNodeIds.contains(fv.getNodeId()))
              .forEach(
                  fv ->
                      tombstoneRepository.deleteTombstonesByNodeAndVersion(
                          fv.getNodeId(), fv.getVersion()));
        });
  }

  private List<String> createIndirectShare(String sharedParentId, Node nodeToShare) {
    List<String> targetUserIds = new ArrayList<>();
    shareRepository
        .getShares(sharedParentId, Collections.emptyList())
        .forEach(
            share -> {
              targetUserIds.add(share.getTargetUserId());
              shareRepository.upsertShare(
                  nodeToShare.getId(),
                  share.getTargetUserId(),
                  share.getPermissions(),
                  false,
                  false,
                  share.getExpiredAt());

              if (nodeToShare.getNodeType().equals(NodeType.FOLDER)) {
                shareCascade.cascadeUpsertShare(
                    nodeToShare.getId(),
                    share.getTargetUserId(),
                    share.getPermissions(),
                    share.getExpiredAt());
              }
            });
    return targetUserIds;
  }

  private void cascadeDeleteNode(String nodeId) {
    List<String> childrenIds =
        nodeRepository.getChildrenIds(nodeId, Optional.empty(), Optional.empty(), true);

    if (!childrenIds.isEmpty()) {
      List<Node> children =
          nodeRepository.getNodes(childrenIds, Optional.empty()).collect(Collectors.toList());
      deleteNodes(children);
      children.stream()
          .filter(node -> node.getNodeType().equals(NodeType.FOLDER))
          .forEach(node -> cascadeDeleteNode(node.getId()));
    }

    shareRepository.deleteSharesBulk(nodeRepository.getTrashedNodeIdsByOldParent(nodeId));
  }

  private void deleteNodes(List<Node> nodes) {
    List<String> nodeIds = nodes.stream().map(Node::getId).collect(Collectors.toList());

    if (!nodeIds.isEmpty()) {
      shareRepository.deleteSharesBulk(nodeIds);
      nodeRepository.deleteNodes(nodeIds);
    }
  }

  /** Thrown by {@link #createFolder} when the requester lacks READ_AND_WRITE on the parent. */
  public static class NodeAccessException extends RuntimeException {
    public NodeAccessException(String parentId) {
      super("Cannot create folder under node " + parentId + ": missing READ_AND_WRITE permission");
    }
  }

  /**
   * Thrown by {@link #createFolder} when the parent does not exist, or is neither a {@link
   * NodeType#FOLDER} nor a {@link NodeType#ROOT}.
   */
  public static class NodeNotFoundException extends RuntimeException {
    public NodeNotFoundException(String parentId) {
      super("Cannot create folder: parent node " + parentId + " not found, or not a folder/root");
    }
  }
}
