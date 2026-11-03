// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.graphql.api;

import static com.zextras.carbonio.files.utilities.RenameNodeUtils.searchAlternativeName;

import com.zextras.carbonio.files.Constants;
import com.zextras.carbonio.files.Constants.Db.RootId;
import com.zextras.carbonio.files.Constants.ServiceDiscover.Config;
import com.zextras.carbonio.files.config.FilesConfig;
import com.zextras.carbonio.files.dal.dao.UserMyself;
import com.zextras.carbonio.files.dal.dao.ebean.ACL;
import com.zextras.carbonio.files.dal.dao.ebean.ACL.SharePermission;
import com.zextras.carbonio.files.dal.dao.ebean.FileVersion;
import com.zextras.carbonio.files.dal.dao.ebean.Node;
import com.zextras.carbonio.files.dal.dao.ebean.NodeCategory;
import com.zextras.carbonio.files.dal.dao.ebean.Share;
import com.zextras.carbonio.files.dal.locks.LockKey;
import com.zextras.carbonio.files.dal.locks.LockRetry;
import com.zextras.carbonio.files.dal.locks.LockSetChangedException;
import com.zextras.carbonio.files.dal.locks.LockedTree;
import com.zextras.carbonio.files.dal.locks.NodeLocks;
import com.zextras.carbonio.files.dal.locks.RetryOnLockConflict;
import com.zextras.carbonio.files.dal.repositories.impl.InvalidPageTokenException;
import com.zextras.carbonio.files.dal.repositories.impl.ebean.utilities.AddedNodeType;
import com.zextras.carbonio.files.dal.repositories.impl.ebean.utilities.FileVersionSort;
import com.zextras.carbonio.files.dal.repositories.impl.ebean.utilities.RemovedNodeType;
import com.zextras.carbonio.files.dal.repositories.interfaces.FileVersionRepository;
import com.zextras.carbonio.files.dal.repositories.interfaces.NodeRepository;
import com.zextras.carbonio.files.dal.repositories.interfaces.NotificationRepository;
import com.zextras.carbonio.files.dal.repositories.interfaces.ShareRepository;
import com.zextras.carbonio.files.dal.repositories.interfaces.TombstoneRepository;
import com.zextras.carbonio.files.graphql.auth.AuthenticatedUser;
import com.zextras.carbonio.files.graphql.errors.ErrorCodes;
import com.zextras.carbonio.files.graphql.errors.FilesGraphQLErrors;
import com.zextras.carbonio.files.graphql.errors.FilesGraphQLException;
import com.zextras.carbonio.files.graphql.model.FileModel;
import com.zextras.carbonio.files.graphql.model.FolderModel;
import com.zextras.carbonio.files.graphql.model.NodeModel;
import com.zextras.carbonio.files.graphql.model.NodePageModel;
import com.zextras.carbonio.files.graphql.model.NodeSort;
import com.zextras.carbonio.files.graphql.model.NodeType;
import com.zextras.carbonio.files.graphql.model.PermissionsModel;
import com.zextras.carbonio.files.graphql.model.RootModel;
import com.zextras.carbonio.files.graphql.model.support.NodeModelFactory;
import com.zextras.carbonio.files.graphql.spi.CopyFailureClassifier;
import com.zextras.carbonio.files.graphql.support.NodeCreationHelper;
import com.zextras.carbonio.files.graphql.support.ShareCascadeHelper;
import com.zextras.carbonio.files.graphql.validation.GraphQLInputValidator;
import com.zextras.carbonio.files.rest.services.VersionReservations;
import com.zextras.carbonio.files.rest.services.VersionReservations.Reservation;
import com.zextras.carbonio.files.utilities.PermissionsChecker;
import com.zextras.filestore.api.Filestore;
import com.zextras.filestore.model.BulkDeleteRequestItem;
import com.zextras.filestore.model.BulkDeleteResponseItem;
import com.zextras.filestore.model.FilesIdentifier;
import com.zextras.filestore.model.IdentifierType;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.security.Authenticated;
import io.smallrye.graphql.api.Context;
import io.smallrye.graphql.api.Nullable;
import io.vavr.control.Try;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import jakarta.transaction.Transactional.TxType;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.eclipse.microprofile.graphql.GraphQLApi;
import org.eclipse.microprofile.graphql.Id;
import org.eclipse.microprofile.graphql.Mutation;
import org.eclipse.microprofile.graphql.Name;
import org.eclipse.microprofile.graphql.NonNull;
import org.eclipse.microprofile.graphql.Query;
import org.eclipse.microprofile.graphql.Source;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Code-first GraphQL API for the Node/File/Folder types, served by quarkus-smallrye-graphql at
 * {@code /graphql}.
 *
 * <p><strong>CE/Advanced-edition seam:</strong> in the code-first model the schema is derived by
 * SmallRye from the annotated beans it auto-discovers, so an additional edition (e.g. Advanced)
 * extends the schema simply by shipping more {@code @GraphQLApi}/{@code @Type} beans on the
 * classpath — no {@code SchemaContributor}/{@code WiringContributor} registration is needed (those
 * belonged to the retired schema-first graphql-java stack).
 */
@GraphQLApi
@Authenticated
public class NodeApi {

  private static final Logger logger = LoggerFactory.getLogger(NodeApi.class);

  @Inject NodeRepository nodeRepository;
  @Inject FileVersionRepository fileVersionRepository;
  @Inject PermissionsChecker permissionsChecker;
  @Inject ShareRepository shareRepository;
  @Inject FilesConfig filesConfig;
  @Inject CopyFailureClassifier copyFailureClassifier;
  @Inject GraphQLInputValidator validator;
  @Inject Context gqlContext;
  @Inject @AuthenticatedUser UserMyself requester;
  @Inject TombstoneRepository tombstoneRepository;
  @Inject NotificationRepository notificationRepository;
  @Inject Filestore fileStore;
  @Inject ShareCascadeHelper shareCascade;
  @Inject NodeCreationHelper nodeCreationHelper;
  @Inject VersionReservations versionReservations;
  @Inject EntityManager entityManager;
  @Inject NodeLocks nodeLocks;
  @Inject LockedTree lockedTree;
  @Inject LockRetry lockRetry;

  // ─── Queries ──────────────────────────────────────────────────────────────────

  @Query("getNode")
  public NodeModel getNode(
      @Name("node_id") @Id @NonNull String nodeId, @Name("version") Integer version)
      throws FilesGraphQLException {
    validator.checkNodeId(nodeId).validate();
    String me = requester.getId().getUserId();
    if (!permissionsChecker.getPermissions(nodeId, me).has(SharePermission.READ_ONLY)) {
      throw FilesGraphQLErrors.nodeNotFound(nodeId);
    }
    Node node =
        nodeRepository.getNode(nodeId).orElseThrow(() -> FilesGraphQLErrors.nodeNotFound(nodeId));
    NodeModel model = NodeModelFactory.from(node, version, me);
    NodeModelFactory.requireVersion(model, model);
    return model;
  }

  @Query("findNodes")
  public NodePageModel findNodes(
      @Name("keywords") @Nullable List<@NonNull String> keywords,
      @Name("flagged") Boolean flagged,
      @Name("shared_by_me") Boolean sharedByMe,
      @Name("shared_with_me") Boolean sharedWithMe,
      @Name("direct_share") Boolean directShare,
      @Name("folder_id") String folderId,
      @Name("cascade") Boolean cascade,
      @Name("type") NodeType type,
      @Name("owner_id") String ownerId,
      @Name("limit") Integer limit,
      @Name("page_token") String pageToken,
      @Name("sort") NodeSort sort)
      throws FilesGraphQLException {
    validator.checkPositiveLimit(limit).validate();
    String me = requester.getId().getUserId();

    Optional<com.zextras.carbonio.files.dal.repositories.impl.ebean.utilities.NodeSort> optSort =
        Optional.ofNullable(sort)
            .map(
                s ->
                    com.zextras.carbonio.files.dal.repositories.impl.ebean.utilities.NodeSort
                        .valueOf(s.name()));
    Optional<com.zextras.carbonio.files.dal.dao.ebean.NodeType> optNodeType =
        Optional.ofNullable(type)
            .map(t -> com.zextras.carbonio.files.dal.dao.ebean.NodeType.valueOf(t.name()));

    ImmutablePair<List<Node>, String> findResult;
    try {
      findResult =
          nodeRepository.findNodes(
              me,
              optSort,
              Optional.ofNullable(flagged),
              Optional.ofNullable(folderId),
              Optional.ofNullable(cascade),
              Optional.ofNullable(sharedWithMe),
              Optional.ofNullable(sharedByMe),
              Optional.ofNullable(directShare),
              Optional.ofNullable(limit),
              optNodeType,
              Optional.ofNullable(ownerId),
              keywords != null ? keywords : Collections.emptyList(),
              Optional.ofNullable(pageToken));
    } catch (InvalidPageTokenException e) {
      throw FilesGraphQLErrors.dataFetching(gqlContext.getPath(), e.getMessage());
    }

    List<NodeModel> nodes =
        findResult.getLeft().stream()
            .map(n -> NodeModelFactory.from(n, null, me))
            .collect(Collectors.toList());

    NodePageModel page = new NodePageModel(nodes, findResult.getRight());
    NodeModelFactory.requireVersions(nodes, page);
    return page;
  }

  @Query("getVersions")
  public @NonNull List<FileModel> getVersions(
      @Name("node_id") @Id @NonNull String nodeId,
      @Name("versions") @Nullable List<@NonNull Integer> versions)
      throws FilesGraphQLException {
    String me = requester.getId().getUserId();
    if (!permissionsChecker.getPermissions(nodeId, me).has(SharePermission.READ_ONLY)) {
      throw FilesGraphQLErrors.nodeWriteError(nodeId)
          .withPartialResults(Collections.singletonList(null));
    }

    List<Integer> versionNumbers;
    if (versions == null) {
      versionNumbers =
          fileVersionRepository
              .getFileVersions(nodeId, List.of(FileVersionSort.VERSION_DESC))
              .stream()
              .map(FileVersion::getVersion)
              .collect(Collectors.toList());
    } else {
      versionNumbers = versions;
    }

    Node node =
        nodeRepository.getNode(nodeId).orElseThrow(() -> FilesGraphQLErrors.nodeNotFound(nodeId));

    if (node.getNodeCategory() != NodeCategory.FILE) {
      return List.of();
    }

    List<FileModel> result =
        versionNumbers.stream()
            .map(ver -> (FileModel) NodeModelFactory.from(node, ver, me))
            .collect(Collectors.toList());
    NodeModelFactory.requireVersions(result, result);
    return result;
  }

  @Query("getPath")
  public @NonNull List<NodeModel> getPath(@Name("node_id") @Id @NonNull String nodeId)
      throws FilesGraphQLException {
    validator.checkNodeId(nodeId).validate();
    String me = requester.getId().getUserId();
    if (!permissionsChecker.getPermissions(nodeId, me).has(SharePermission.READ_ONLY)) {
      throw pathNodeNotFound(nodeId);
    }

    Node node = nodeRepository.getNode(nodeId).orElseThrow(() -> pathNodeNotFound(nodeId));

    List<String> pathNodeIds = new ArrayList<>(node.getAncestorsList());
    pathNodeIds.add(nodeId);

    List<Node> treeNodes =
        nodeRepository
            .getNodes(pathNodeIds, Optional.empty())
            .sorted(Comparator.comparing(n -> pathNodeIds.indexOf(n.getId())))
            .collect(Collectors.toList());

    if (node.getNodeType().equals(com.zextras.carbonio.files.dal.dao.ebean.NodeType.ROOT)
        || node.getOwnerId().equals(me)) {
      return toPathModels(treeNodes, me);
    }

    List<Share> shares =
        shareRepository.getShares(
            treeNodes.stream().map(Node::getId).collect(Collectors.toList()), me);

    List<Node> sharedNodes =
        treeNodes.stream()
            .filter(
                treeNode -> shares.stream().anyMatch(s -> s.getNodeId().equals(treeNode.getId())))
            .collect(Collectors.toList());

    return toPathModels(
        treeNodes.subList(treeNodes.indexOf(sharedNodes.get(0)), treeNodes.size()), me);
  }

  private static List<NodeModel> toPathModels(List<Node> nodes, String me)
      throws FilesGraphQLException {
    List<NodeModel> models =
        nodes.stream().map(n -> NodeModelFactory.from(n, null, me)).collect(Collectors.toList());
    NodeModelFactory.requireVersions(models, models);
    return models;
  }

  @Query("getRootsList")
  public @NonNull List<RootModel> getRootsList() {
    return nodeRepository.getRootsList().stream()
        .map(root -> new RootModel(root.getId(), root.getName()))
        .collect(Collectors.toList());
  }

  // ─── Single-item @Source resolvers ────────────────────────────────────────────

  @Name("children")
  @NonNull
  public NodePageModel children(
      @Source FolderModel folder,
      @Name("limit") @NonNull int limit,
      @Name("sort") @NonNull NodeSort sort,
      @Name("page_token") String pageToken)
      throws FilesGraphQLException {
    validator.checkLimitPagination(limit).validate();
    String me = requester.getId().getUserId();
    com.zextras.carbonio.files.dal.repositories.impl.ebean.utilities.NodeSort dalSort =
        com.zextras.carbonio.files.dal.repositories.impl.ebean.utilities.NodeSort.valueOf(
            sort.name());
    Optional<String> optOwnerId =
        RootId.LOCAL_ROOT.equals(folder.getId()) ? Optional.of(me) : Optional.empty();
    ImmutablePair<List<Node>, String> result;
    try {
      result =
          nodeRepository.findNodes(
              me,
              Optional.of(dalSort),
              Optional.empty(),
              Optional.of(folder.getId()),
              Optional.of(false),
              Optional.empty(),
              Optional.empty(),
              Optional.empty(),
              Optional.of(limit),
              Optional.empty(),
              optOwnerId,
              Collections.emptyList(),
              Optional.ofNullable(pageToken));
    } catch (InvalidPageTokenException e) {
      throw FilesGraphQLErrors.dataFetching(gqlContext.getPath(), e.getMessage());
    }
    List<NodeModel> nodes =
        result.getLeft().stream()
            .map(n -> NodeModelFactory.from(n, null, me))
            .collect(Collectors.toList());
    NodePageModel page = new NodePageModel(nodes, result.getRight());
    NodeModelFactory.requireVersions(nodes, page);
    return page;
  }

  // ─── Batch @Source resolvers ───────────────────────────────────────────────

  @Name("parent")
  public List<NodeModel> parents(@Source List<NodeModel> nodes) {
    List<String> parentIds = nodes.stream().map(NodeModel::getParentId).toList();
    Map<String, Node> byId =
        nodeRepository
            .getNodes(
                parentIds.stream().filter(Objects::nonNull).distinct().toList(), Optional.empty())
            .collect(Collectors.toMap(Node::getId, n -> n));
    String me = requester.getId().getUserId();
    Map<String, ACL> permissions = permissionsChecker.getPermissionsOfNodes(byId.values(), me);
    return parentIds.stream()
        .map(id -> id == null ? null : byId.get(id))
        .map(
            n -> {
              if (n == null) return null;
              if (!permissions.get(n.getId()).has(SharePermission.READ_ONLY)) return null;
              return NodeModelFactory.from(n, null, me);
            })
        .toList();
  }

  @Name("permissions")
  @NonNull
  public List<@NonNull PermissionsModel> permissions(@Source List<NodeModel> nodes) {
    Map<String, ACL> byId =
        permissionsChecker.getPermissions(
            nodes.stream().map(NodeModel::getId).toList(), requester.getId().getUserId());
    return nodes.stream().map(n -> new PermissionsModel(byId.get(n.getId()))).toList();
  }

  // ─── Mutations ────────────────────────────────────────────────────────────────

  @Mutation("createFolder")
  @RetryOnLockConflict
  public @NonNull NodeModel createFolder(
      @Name("destination_id") @NonNull String destinationId, @Name("name") @NonNull String name)
      throws FilesGraphQLException {
    validator.checkNodeId(destinationId).checkNodeName(name).validate();
    String me = requester.getId().getUserId();
    try {
      Node createdFolder = nodeCreationHelper.createFolder(me, destinationId, name, requester);
      return NodeModelFactory.from(createdFolder, null, me);
    } catch (NodeCreationHelper.NodeAccessException e) {
      throw FilesGraphQLErrors.nodeWriteError(destinationId.trim());
    } catch (NodeCreationHelper.NodeNotFoundException e) {
      throw FilesGraphQLErrors.nodeNotFound(destinationId.trim());
    }
  }

  @Mutation("updateNode")
  @RetryOnLockConflict
  public @NonNull NodeModel updateNode(
      @Name("node_id") @NonNull String nodeId,
      @Name("name") String name,
      @Name("description") String description,
      @Name("flagged") Boolean flagged)
      throws FilesGraphQLException {
    validator.checkNodeId(nodeId).checkNodeName(name).checkNodeDescription(description).validate();
    String me = requester.getId().getUserId();
    List<NodeLocks.NodePlace> places = nodeLocks.placesOf(List.of(nodeId));
    List<LockKey> keys = new ArrayList<>();
    keys.add(LockKey.node(nodeId));
    places.stream()
        .filter(place -> place.parentId() != null && place.ownerId() != null)
        .forEach(place -> keys.add(LockKey.children(place.parentId(), place.ownerId())));
    nodeLocks.lock(keys);
    if (!permissionsChecker.getPermissions(nodeId, me).has(SharePermission.READ_AND_WRITE)) {
      throw FilesGraphQLErrors.nodeNotFound(nodeId);
    }
    Node nodeToUpdate =
        nodeRepository.getNode(nodeId).orElseThrow(() -> FilesGraphQLErrors.nodeNotFound(nodeId));

    String parentFolderId = nodeToUpdate.getParentId().orElse(RootId.LOCAL_ROOT);
    if (!places.isEmpty()
        && places.get(0).parentId() != null
        && !places.get(0).parentId().equals(parentFolderId)) {
      throw new LockSetChangedException("node " + nodeId + " moved");
    }

    if (name != null) {
      String nodeFullName = nodeToUpdate.getExtension().map(ext -> name + "." + ext).orElse(name);
      if (!searchAlternativeName(
              nodeRepository, nodeFullName, parentFolderId, nodeToUpdate.getOwnerId())
          .equals(nodeFullName)) {
        throw FilesGraphQLErrors.duplicateNode(nodeId, parentFolderId);
      }
      nodeToUpdate.setName(name);
    }
    if (description != null) nodeToUpdate.setDescription(description);
    if (flagged != null) nodeRepository.flagForUser(nodeId, me, flagged);
    nodeToUpdate.setLastEditorId(me);
    nodeRepository.updateNode(nodeToUpdate);
    NodeModel model = NodeModelFactory.from(nodeToUpdate, null, me);
    NodeModelFactory.requireVersion(model, model);
    return model;
  }

  @Mutation("flagNodes")
  @RetryOnLockConflict
  public @Nullable @Id List<@NonNull String> flagNodes(
      @Name("node_ids") @Nullable List<@NonNull String> nodeIds,
      @Name("flag") @NonNull Boolean flag)
      throws FilesGraphQLException {
    validator.requireNodeIds(nodeIds).validate();
    nodeLocks.lock(nodeIds.stream().map(LockKey::node).toList());
    String me = requester.getId().getUserId();

    List<String> flaggableNodes =
        nodeIds.stream()
            .filter(
                nodeId -> {
                  Optional<Node> rNode = nodeRepository.getNode(nodeId);
                  return rNode.isPresent()
                      && rNode.get().getNodeType()
                          != com.zextras.carbonio.files.dal.dao.ebean.NodeType.ROOT;
                })
            .filter(
                nodeId ->
                    permissionsChecker
                        .getPermissions(nodeId, me)
                        .has(SharePermission.READ_AND_WRITE))
            .collect(Collectors.toList());

    List<String> nodesInError =
        nodeIds.stream().filter(nodeId -> !flaggableNodes.contains(nodeId)).toList();

    flaggableNodes.forEach(nodeId -> nodeRepository.flagForUser(nodeId, me, flag));

    if (!nodesInError.isEmpty()) {
      throw FilesGraphQLErrors.nodeWriteError(nodesInError.get(0))
          .withPartialResults(flaggableNodes);
    }
    return flaggableNodes;
  }

  private List<NodeLocks.NodePlace> lockNodesAndParents(
      Collection<String> nodeIds, Collection<LockKey> extra) {
    List<NodeLocks.NodePlace> places = nodeLocks.placesOf(nodeIds);
    return lockPlaces(places, extra);
  }

  private List<NodeLocks.NodePlace> lockPlaces(
      List<NodeLocks.NodePlace> places, Collection<LockKey> extra) {
    List<LockKey> keys = new ArrayList<>(extra);
    places.forEach(
        place -> {
          keys.add(LockKey.node(place.nodeId()));
          if (place.parentId() != null && place.ownerId() != null) {
            keys.add(LockKey.children(place.parentId(), place.ownerId()));
          }
        });
    nodeLocks.lock(keys);
    return places;
  }

  private void requireUnmoved(List<NodeLocks.NodePlace> places, Collection<String> nodeIds) {
    if (!Set.copyOf(places).equals(Set.copyOf(nodeLocks.placesOf(nodeIds)))) {
      throw new LockSetChangedException("nodes moved or changed owner");
    }
  }

  @Mutation("trashNodes")
  @RetryOnLockConflict
  public @Nullable @Id List<@NonNull String> trashNodes(
      @Name("node_ids") @Nullable List<@NonNull String> nodeIds) throws FilesGraphQLException {
    validator.checkNodeIds(nodeIds).validate();
    requireUnmoved(lockNodesAndParents(nodeIds, List.of()), nodeIds);
    String me = requester.getId().getUserId();

    List<String> trashableNodes =
        nodeIds.stream()
            .filter(
                nodeId -> {
                  Optional<Node> rNode = nodeRepository.getNode(nodeId);
                  return rNode.isPresent()
                      && rNode.get().getNodeType()
                          != com.zextras.carbonio.files.dal.dao.ebean.NodeType.ROOT;
                })
            .filter(
                nodeId ->
                    permissionsChecker
                        .getPermissions(nodeId, me)
                        .has(SharePermission.READ_AND_WRITE))
            .collect(Collectors.toCollection(ArrayList::new));

    if (!trashableNodes.isEmpty()) {
      List<Runnable> pendingNotifications = new ArrayList<>();
      List<Node> candidateNodes =
          nodeRepository.getNodes(trashableNodes, Optional.empty()).toList();
      List<String> alreadyTrashedIds =
          nodeRepository.getTrashedNodeIds(candidateNodes.stream().map(Node::getId).toList());
      List<Node> nodesToTrash =
          candidateNodes.stream()
              .filter(node -> !alreadyTrashedIds.contains(node.getId()))
              .toList();
      trashableNodes.retainAll(nodesToTrash.stream().map(Node::getId).toList());
      nodesToTrash.forEach(
          trashedNode -> {
            String nodeParentId = trashedNode.getParentId().get();

            List<String> usersToNotify =
                new ArrayList<>(shareRepository.getSharesUsersIds(trashedNode.getId(), List.of()));
            usersToNotify.remove(me);

            Node parent = nodeRepository.getNode(trashedNode.getParentId().get()).get();
            if (!parent.getNodeType().equals(com.zextras.carbonio.files.dal.dao.ebean.NodeType.ROOT)
                && !me.equals(parent.getOwnerId())
                && !usersToNotify.contains(parent.getOwnerId())) {
              usersToNotify.add(parent.getOwnerId());
            }

            if (!usersToNotify.isEmpty() && filesConfig.areNotificationsEnabled()) {
              pendingNotifications.add(
                  () ->
                      notificationRepository.createRemovedNodeNotification(
                          trashedNode, parent, requester, RemovedNodeType.DELETE, usersToNotify));
            }

            trashedNode.setAncestorIds(Constants.Db.RootId.TRASH_ROOT);
            trashedNode.setParentId(RootId.TRASH_ROOT);
            nodeRepository.trashNode(trashedNode.getId(), nodeParentId);
            nodeRepository.updateNode(trashedNode);
            cascadeUpdateAncestors(trashedNode);
          });

      pendingNotifications.forEach(Runnable::run);
    }

    List<String> nodesInError =
        nodeIds.stream().filter(nodeId -> !trashableNodes.contains(nodeId)).toList();

    if (!nodesInError.isEmpty()) {
      throw FilesGraphQLErrors.nodeWriteError(nodesInError.get(0))
          .withPartialResults(trashableNodes);
    }
    return trashableNodes;
  }

  @Mutation("restoreNodes")
  @RetryOnLockConflict
  public @Nullable List<NodeModel> restoreNodes(
      @Name("node_ids") @Nullable List<@NonNull String> nodeIds) throws FilesGraphQLException {
    validator.checkNodeIds(nodeIds).validate();
    Map<String, String> predicted = nodeLocks.trashedParentsOf(nodeIds);
    Set<String> predictedFallback = nodeLocks.unusableRestoreParents(predicted.values());
    List<LockKey> restoreKeys = new ArrayList<>();
    for (NodeLocks.NodePlace place : nodeLocks.placesOf(nodeIds)) {
      restoreKeys.add(LockKey.node(place.nodeId()));
      String predictedParent = predicted.get(place.nodeId());
      if (place.ownerId() != null && predictedParent != null) {
        restoreKeys.add(LockKey.children(predictedParent, place.ownerId()));
        if (predictedFallback.contains(predictedParent)) {
          restoreKeys.add(LockKey.children(RootId.LOCAL_ROOT, place.ownerId()));
        }
      }
    }
    nodeLocks.lock(restoreKeys);
    if (!predicted.equals(nodeLocks.trashedParentsOf(nodeIds))
        || !predictedFallback.equals(nodeLocks.unusableRestoreParents(predicted.values()))) {
      throw new LockSetChangedException("restore destinations changed");
    }
    String me = requester.getId().getUserId();

    List<String> restorableNodeIds =
        nodeIds.stream()
            .filter(
                nodeId -> {
                  Optional<Node> rNode = nodeRepository.getNode(nodeId);
                  return rNode.isPresent()
                      && rNode.get().getNodeType()
                          != com.zextras.carbonio.files.dal.dao.ebean.NodeType.ROOT;
                })
            .filter(
                nodeId ->
                    permissionsChecker
                        .getPermissions(nodeId, me)
                        .has(SharePermission.READ_AND_WRITE))
            .filter(nodeId -> nodeRepository.getTrashedNode(nodeId).isPresent())
            .collect(Collectors.toList());

    List<NodeModel> results =
        nodeRepository
            .getNodes(restorableNodeIds, Optional.empty())
            .map(
                node -> {
                  Optional<Node> parentNode = restoreDestination(node.getId());
                  String destParentId;
                  String destAncestors;
                  if (parentNode.isEmpty()) {
                    destParentId = RootId.LOCAL_ROOT;
                    destAncestors = RootId.LOCAL_ROOT;

                    shareRepository.getShares(node.getId(), Collections.emptyList()).stream()
                        .filter(share -> !share.isDirect())
                        .forEach(
                            share -> {
                              share.setDirect(true);
                              shareRepository.updateShare(share);
                            });
                  } else {
                    destParentId = parentNode.get().getId();
                    destAncestors =
                        com.zextras.carbonio.files.dal.dao.ebean.NodeType.ROOT.equals(
                                parentNode.get().getNodeType())
                            ? destParentId
                            : parentNode.get().getAncestorIds()
                                + Node.ANCESTORS_SEPARATOR
                                + destParentId;

                    shareRepository
                        .getShares(destParentId, Collections.emptyList())
                        .forEach(
                            share -> {
                              shareRepository.upsertShare(
                                  node.getId(),
                                  share.getTargetUserId(),
                                  share.getPermissions(),
                                  false,
                                  false,
                                  share.getExpiredAt());

                              if (node.getNodeType()
                                  == com.zextras.carbonio.files.dal.dao.ebean.NodeType.FOLDER) {
                                shareCascade.cascadeUpsertShare(
                                    node.getId(),
                                    share.getTargetUserId(),
                                    share.getPermissions(),
                                    share.getExpiredAt());
                              }
                            });
                  }
                  String newName =
                      searchAlternativeName(
                          nodeRepository, node.getFullName(), destParentId, node.getOwnerId());
                  node.setParentId(destParentId);
                  node.setAncestorIds(destAncestors);
                  node.setFullName(newName);
                  nodeRepository.restoreNode(node.getId());
                  nodeRepository.updateNode(node);
                  cascadeUpdateAncestors(node);
                  return NodeModelFactory.from(node, null, me);
                })
            .collect(Collectors.toList());

    List<String> nodesInError =
        nodeIds.stream().filter(nodeId -> !restorableNodeIds.contains(nodeId)).toList();
    List<NodeModel> withErrorSlots = new ArrayList<>(results);
    nodesInError.forEach(id -> withErrorSlots.add(null));
    NodeModelFactory.requireVersions(results, withErrorSlots);
    if (!nodesInError.isEmpty()) {
      throw FilesGraphQLErrors.nodeWriteError(nodesInError.get(0))
          .withPartialResults(withErrorSlots);
    }
    return results;
  }

  @Mutation("moveNodes")
  @RetryOnLockConflict
  public @Nullable List<@NonNull NodeModel> moveNodes(
      @Name("node_ids") @Nullable List<@NonNull String> nodeIds,
      @Name("destination_id") @Id @NonNull String destinationId)
      throws FilesGraphQLException {
    validator.checkNodeIds(nodeIds).checkNodeId(destinationId).validate();
    List<NodeLocks.NodePlace> movePlaces = nodeLocks.placesOf(nodeIds);
    List<LockKey> destinationKeys =
        movePlaces.stream()
            .map(NodeLocks.NodePlace::ownerId)
            .filter(Objects::nonNull)
            .distinct()
            .map(owner -> LockKey.children(destinationId, owner))
            .toList();
    requireUnmoved(lockPlaces(movePlaces, destinationKeys), nodeIds);
    String me = requester.getId().getUserId();

    if (!permissionsChecker.getPermissions(destinationId, me).has(SharePermission.READ_AND_WRITE)) {
      throw FilesGraphQLErrors.nodeWriteError(destinationId);
    }

    Node destinationFolder =
        nodeRepository
            .getNode(destinationId)
            .filter(
                p ->
                    com.zextras.carbonio.files.dal.dao.ebean.NodeType.FOLDER.equals(p.getNodeType())
                        || com.zextras.carbonio.files.dal.dao.ebean.NodeType.ROOT.equals(
                            p.getNodeType()))
            .orElseThrow(() -> FilesGraphQLErrors.nodeWriteError(destinationId));

    List<String> rootIds =
        nodeRepository.getRootsList().stream().map(Node::getId).collect(Collectors.toList());

    List<String> movableIds =
        nodeIds.stream()
            .filter(
                nodeId ->
                    permissionsChecker
                        .getPermissions(nodeId, me)
                        .has(SharePermission.READ_AND_WRITE))
            .filter(nodeId -> !rootIds.contains(nodeId))
            .filter(nodeId -> !destinationId.equals(nodeId))
            .toList();
    List<String> nodeIdsToMove = excludeAncestorsOf(destinationFolder, movableIds);

    List<NodeModel> movedNodesResult = List.of();
    if (!nodeIdsToMove.isEmpty()) {
      List<Runnable> pendingNotifications = new ArrayList<>();
      nodeIdsToMove.forEach(
          nodeId -> {
            Node node = nodeRepository.getNode(nodeId).get();
            if (!RootId.TRASH_ROOT.equals(destinationId)
                && nodeRepository.getTrashedNode(nodeId).isPresent()) {
              nodeRepository.restoreNode(nodeId);
            }
            if (node.getParentId().isPresent() && !node.getParentId().get().equals(destinationId)) {
              String newName =
                  searchAlternativeName(
                      nodeRepository, node.getFullName(), destinationId, node.getOwnerId());
              node.setFullName(newName);
            }
            nodeRepository.updateNode(node);

            List<String> usersToNotifyRemove =
                new ArrayList<>(shareRepository.getSharesUsersIds(node.getId(), List.of()));
            usersToNotifyRemove.remove(me);

            Node parent = nodeRepository.getNode(node.getParentId().get()).get();
            if (!parent.getNodeType().equals(com.zextras.carbonio.files.dal.dao.ebean.NodeType.ROOT)
                && !me.equals(parent.getOwnerId())
                && !usersToNotifyRemove.contains(parent.getOwnerId())) {
              usersToNotifyRemove.add(parent.getOwnerId());
            }

            if (!usersToNotifyRemove.isEmpty() && filesConfig.areNotificationsEnabled()) {
              pendingNotifications.add(
                  () ->
                      notificationRepository.createRemovedNodeNotification(
                          node, parent, requester, RemovedNodeType.MOVE, usersToNotifyRemove));
            }
          });

      nodeRepository.moveNodes(nodeIdsToMove, destinationFolder);

      nodeIdsToMove.forEach(
          nodeId -> {
            Node node = nodeRepository.getNode(nodeId).get();
            cascadeUpdateAncestors(node);

            shareRepository.getShares(nodeId, Collections.emptyList()).stream()
                .filter(share -> !share.isDirect())
                .forEach(
                    share -> {
                      shareRepository.deleteShare(nodeId, share.getTargetUserId());
                      shareCascade.cascadeDeleteShare(nodeId, share.getTargetUserId());
                    });

            List<String> usersToNotifyAdd = new ArrayList<>();
            shareRepository
                .getShares(destinationId, Collections.emptyList())
                .forEach(
                    share -> {
                      Optional<Share> sourceShare =
                          shareRepository.getShare(nodeId, share.getTargetUserId());
                      usersToNotifyAdd.add(share.getTargetUserId());
                      if (!sourceShare.isPresent() || !sourceShare.get().isDirect()) {
                        shareRepository.upsertShare(
                            nodeId,
                            share.getTargetUserId(),
                            share.getPermissions(),
                            false,
                            false,
                            share.getExpiredAt());
                        shareCascade.cascadeUpsertShare(
                            nodeId,
                            share.getTargetUserId(),
                            share.getPermissions(),
                            share.getExpiredAt());
                      }
                    });

            usersToNotifyAdd.remove(me);
            if (!com.zextras.carbonio.files.dal.dao.ebean.NodeType.ROOT.equals(
                    destinationFolder.getNodeType())
                && !me.equals(destinationFolder.getOwnerId())) {
              usersToNotifyAdd.add(destinationFolder.getOwnerId());
            }

            if (!usersToNotifyAdd.isEmpty() && filesConfig.areNotificationsEnabled()) {
              pendingNotifications.add(
                  () ->
                      notificationRepository.createAddedNodeNotification(
                          node,
                          destinationFolder,
                          requester,
                          AddedNodeType.MOVE,
                          usersToNotifyAdd));
            }
          });

      movedNodesResult =
          nodeRepository
              .getNodes(nodeIdsToMove, Optional.empty())
              .map(node -> NodeModelFactory.from(node, null, me))
              .collect(Collectors.toList());

      pendingNotifications.forEach(Runnable::run);
    }

    NodeModelFactory.requireVersions(movedNodesResult, movedNodesResult);
    List<String> nodesInError =
        nodeIds.stream().filter(nodeId -> !nodeIdsToMove.contains(nodeId)).toList();

    if (!nodesInError.isEmpty()) {
      throw FilesGraphQLErrors.nodeWriteError(nodesInError.get(0))
          .withPartialResults(movedNodesResult);
    }
    return movedNodesResult;
  }

  @Mutation("deleteNodes")
  @Transactional(TxType.NOT_SUPPORTED)
  public @Nullable @Id List<@NonNull String> deleteNodes(
      @Name("node_ids") @Nullable List<@NonNull String> nodeIds) throws FilesGraphQLException {
    validator.checkNodeIds(nodeIds).validate();
    String me = requester.getId().getUserId();

    List<Node> requestedNodes =
        nodeRepository
            .getNodes(nodeIds, Optional.empty())
            .filter(Objects::nonNull)
            .filter(
                node ->
                    !node.getNodeType()
                        .equals(com.zextras.carbonio.files.dal.dao.ebean.NodeType.ROOT))
            .filter(
                node ->
                    permissionsChecker
                        .getPermissions(node.getId(), me)
                        .has(SharePermission.READ_AND_WRITE))
            .collect(Collectors.toList());

    Set<String> candidateIds =
        requestedNodes.stream().map(Node::getId).collect(Collectors.toCollection(TreeSet::new));

    TreeDeletion deletion;
    try {
      deletion = lockRetry.inNewTransaction(() -> deleteLockedTree(candidateIds, me));
    } catch (RuntimeException e) {
      logger.error("DB error during deleteNodes, rolling back: {}", e.getMessage());
      throw deleteNodesFailure(nodeIds, candidateIds);
    }
    Set<String> requestedNodeIds = deletion.deletedRootIds();
    Map<String, List<FileVersion>> fileVersionsByOwner = deletion.versionsByOwner();

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
          } catch (Exception ex) {
            logger.warn(
                "Bulk delete failed for owner {}: {}. Tombstones remain for retry.",
                ownerId,
                ex.getMessage());
            return;
          }
          if (failedItems == null) {
            logger.warn(
                "Bulk delete returned null for owner {} (treated as failure). Tombstones remain.",
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

    List<String> successIds = new ArrayList<>(requestedNodeIds);
    List<String> errorIds = nodeIds.stream().filter(id -> !requestedNodeIds.contains(id)).toList();

    if (!errorIds.isEmpty()) {
      throw FilesGraphQLErrors.nodeNotFound(errorIds.get(0)).withPartialResults(successIds);
    }
    return successIds;
  }

  // TODO: synchronous, kept alive by the GraphQLExecutionRoute heartbeat (an inactivity-timeout
  // hack): make it an asynchronous job.
  @Mutation("copyNodes")
  @Transactional(TxType.NOT_SUPPORTED)
  public @Nullable List<@NonNull NodeModel> copyNodes(
      @Name("node_ids") @Nullable List<@NonNull String> nodeIds,
      @Name("destination_id") @Id @NonNull String destinationId)
      throws FilesGraphQLException {
    validator.checkNodeIds(nodeIds).checkNodeId(destinationId).validate();
    String me = requester.getId().getUserId();

    if (!permissionsChecker.getPermissions(destinationId, me).has(SharePermission.READ_AND_WRITE)) {
      throw FilesGraphQLErrors.nodeWriteError(destinationId);
    }

    Node destinationFolder =
        nodeRepository
            .getNode(destinationId)
            .filter(
                p ->
                    com.zextras.carbonio.files.dal.dao.ebean.NodeType.FOLDER.equals(p.getNodeType())
                        || com.zextras.carbonio.files.dal.dao.ebean.NodeType.ROOT.equals(
                            p.getNodeType()))
            .orElseThrow(() -> FilesGraphQLErrors.nodeWriteError(destinationId));

    List<Node> nodes =
        nodeRepository.getNodes(nodeIds, Optional.empty()).collect(Collectors.toList());

    List<String> targetFolderChildrenNames =
        nodeRepository
            .getNodes(
                nodeRepository.getChildrenIds(
                    destinationId, Optional.empty(), Optional.empty(), false),
                Optional.empty())
            .map(Node::getFullName)
            .collect(Collectors.toList());

    List<Node> nodesToCopy =
        nodes.stream()
            .filter(
                node ->
                    node.getNodeType() != com.zextras.carbonio.files.dal.dao.ebean.NodeType.FOLDER
                        || (!node.getId().equals(destinationId)
                            && (!destinationFolder.getAncestorsList().contains(node.getId())
                                || node.getParentId().equals(Optional.of(destinationId)))))
            .filter(
                node ->
                    node.getNodeType() != com.zextras.carbonio.files.dal.dao.ebean.NodeType.ROOT)
            .filter(node -> permissionsChecker.getPermissions(node.getId(), me).canRead())
            .collect(Collectors.toList());

    List<NodeModel> copiedNodesResult = new ArrayList<>();
    List<CopyFailure> copyErrors = new ArrayList<>();

    List<Node> nodesInCopyOrder =
        Stream.concat(
                nodesToCopy.stream()
                    .filter(n -> targetFolderChildrenNames.contains(n.getFullName())),
                nodesToCopy.stream()
                    .filter(n -> !targetFolderChildrenNames.contains(n.getFullName())))
            .toList();

    String ownerId = copyOwner(destinationFolder, me);
    for (Node node : nodesInCopyOrder) {
      CopyItem plan = planCopy(node);

      Map<String, CopiedBlob> blobs = new HashMap<>();
      Optional<Throwable> blobFailure = copyBlobs(plan, ownerId, blobs);
      if (blobFailure.isPresent()) {
        discardBlobs(blobs.keySet(), ownerId);
        copyErrors.add(new CopyFailure(node, blobFailure.get()));
        continue;
      }

      Optional<NodeModel> published;
      try {
        published =
            lockRetry.inNewTransaction(() -> publishCopy(plan, blobs, destinationId, ownerId, me));
      } catch (RuntimeException e) {
        if (copiedTopNodeIsAbsent(plan.copyId())) {
          discardBlobs(blobs.keySet(), ownerId);
        }
        throw e;
      }
      if (published.isEmpty()) {
        discardBlobs(blobs.keySet(), ownerId);
        throw FilesGraphQLErrors.nodeWriteError(destinationId)
            .withPartialResults(copiedNodesResult);
      }
      copiedNodesResult.add(published.get());
    }

    List<Node> nodesWithoutPermission =
        nodes.stream().filter(node -> !nodesToCopy.contains(node)).collect(Collectors.toList());

    NodeModelFactory.requireVersions(copiedNodesResult, copiedNodesResult);
    boolean hasErrors = !nodesWithoutPermission.isEmpty() || !copyErrors.isEmpty();
    if (hasErrors) {
      throw copyFailureError(nodesWithoutPermission, copyErrors)
          .withPartialResults(copiedNodesResult);
    }
    return copiedNodesResult;
  }

  @Mutation("deleteVersions")
  @Transactional(TxType.NOT_SUPPORTED)
  public @NonNull List<Integer> deleteVersions(
      @Name("node_id") @Id @NonNull String nodeId,
      @Name("versions") @Nullable List<@NonNull Integer> versions)
      throws FilesGraphQLException {
    String me = requester.getId().getUserId();
    if (!permissionsChecker.getPermissions(nodeId, me).has(SharePermission.READ_AND_WRITE)) {
      throw FilesGraphQLErrors.nodeWriteError(nodeId);
    }

    DeletedVersions deleted;
    try {
      deleted =
          lockRetry.inNewTransaction(
              () -> {
                nodeLocks.lock(List.of(LockKey.node(nodeId)));
                Optional<Node> node = nodeRepository.getNode(nodeId);
                if (node.isEmpty()) {
                  return DeletedVersions.NODE_MISSING;
                }
                List<FileVersion> toDelete =
                    (versions != null
                            ? fileVersionRepository.getFileVersions(nodeId, versions)
                            : fileVersionRepository.getFileVersions(
                                nodeId, List.of(FileVersionSort.VERSION_DESC)))
                        .stream()
                            .filter(fv -> !fv.isKeptForever())
                            .filter(fv -> !node.get().getCurrentVersion().equals(fv.getVersion()))
                            .toList();
                String owner = node.get().getOwnerId();
                if (!toDelete.isEmpty()) {
                  List<Integer> toDeleteNumbers =
                      toDelete.stream().map(FileVersion::getVersion).toList();
                  tombstoneRepository.createTombstonesBulk(toDelete, owner);
                  fileVersionRepository.deleteFileVersions(nodeId, toDeleteNumbers);
                }
                return new DeletedVersions(toDelete, owner);
              });
    } catch (RuntimeException e) {
      logger.error("DB error during deleteVersions, rolling back: {}", e.getMessage());
      throw FilesGraphQLErrors.nodeWriteError(nodeId);
    }
    if (deleted == DeletedVersions.NODE_MISSING) {
      throw FilesGraphQLErrors.nodeNotFound(nodeId);
    }
    List<FileVersion> fileVersionsToDelete = deleted.versions();
    List<Integer> versionsToDelete =
        fileVersionsToDelete.stream().map(FileVersion::getVersion).collect(Collectors.toList());

    if (!versionsToDelete.isEmpty()) {
      String ownerId = deleted.ownerId();
      List<BulkDeleteRequestItem> deleteRequests =
          fileVersionsToDelete.stream()
              .map(fv -> BulkDeleteRequestItem.filesItem(nodeId, fv.getVersion()))
              .collect(Collectors.toList());
      List<BulkDeleteResponseItem> failedItems;
      try {
        failedItems = fileStore.bulkDelete(IdentifierType.files, ownerId, deleteRequests);
      } catch (Exception e) {
        logger.warn(
            "Bulk delete failed for node {}: {}. Tombstones remain.", nodeId, e.getMessage());
        failedItems = null;
      }
      if (failedItems == null) {
        logger.warn("Bulk delete returned null for node {} (treated as failure).", nodeId);
      } else {
        Set<Integer> failedVersionSet =
            failedItems.stream()
                .filter(item -> nodeId.equals(item.getNode()))
                .map(BulkDeleteResponseItem::getVersion)
                .filter(Optional::isPresent)
                .map(Optional::get)
                .collect(Collectors.toSet());
        fileVersionsToDelete.stream()
            .filter(fv -> !failedVersionSet.contains(fv.getVersion()))
            .forEach(
                fv ->
                    tombstoneRepository.deleteTombstonesByNodeAndVersion(
                        fv.getNodeId(), fv.getVersion()));
      }
    }

    if (versions != null) {
      List<Integer> notDeleted =
          versions.stream().filter(v -> !versionsToDelete.contains(v)).toList();
      if (!notDeleted.isEmpty()) {
        throw FilesGraphQLErrors.fileVersionNotFound(nodeId, notDeleted.get(0))
            .withPartialResults(versionsToDelete);
      }
    }
    return versionsToDelete;
  }

  @Mutation("keepVersions")
  @RetryOnLockConflict
  public @NonNull List<Integer> keepVersions(
      @Name("node_id") @Id @NonNull String nodeId,
      @Name("versions") @NonNull List<@NonNull Integer> versions,
      @Name("keep_forever") @NonNull Boolean keepForever)
      throws FilesGraphQLException {
    nodeLocks.lock(List.of(LockKey.node(nodeId)));
    String me = requester.getId().getUserId();
    if (!permissionsChecker.getPermissions(nodeId, me).has(SharePermission.READ_AND_WRITE)) {
      throw FilesGraphQLErrors.nodeWriteError(nodeId);
    }

    int maxNumberOfKeepVersions = getMaxNumberOfKeepVersions();
    List<FileVersion> fileVersionsNotUpdated = new ArrayList<>();
    List<FileVersion> allVersions =
        fileVersionRepository.getFileVersions(nodeId, List.of(FileVersionSort.VERSION_DESC));
    int keepForeverCounter = 0;
    for (FileVersion fv : allVersions) {
      keepForeverCounter = fv.isKeptForever() ? keepForeverCounter + 1 : keepForeverCounter;
    }

    List<FileVersion> fileVersions = fileVersionRepository.getFileVersions(nodeId, versions);
    for (FileVersion fv : fileVersions) {
      if (!keepForever || keepForeverCounter < maxNumberOfKeepVersions) {
        fv.keepForever(keepForever);
        fileVersionRepository.updateFileVersion(fv);
        keepForeverCounter = keepForever ? keepForeverCounter + 1 : keepForeverCounter - 1;
      } else {
        fileVersionsNotUpdated.add(fv);
      }
    }

    List<Integer> versionsUpdated =
        fileVersions.stream()
            .filter(fv -> fv.isKeptForever() == keepForever)
            .map(FileVersion::getVersion)
            .collect(Collectors.toList());

    if (!fileVersionsNotUpdated.isEmpty()) {
      throw FilesGraphQLErrors.tooManyVersionsError(nodeId).withPartialResults(versionsUpdated);
    }
    return versionsUpdated;
  }

  // TODO: synchronous, kept alive by the GraphQLExecutionRoute heartbeat (an inactivity-timeout
  // hack): make it an asynchronous job.
  @Mutation("cloneVersion")
  @Transactional(TxType.NOT_SUPPORTED)
  public @NonNull FileModel cloneVersion(
      @Name("node_id") @Id @NonNull String nodeId, @Name("version") @NonNull Integer version)
      throws FilesGraphQLException {
    String me = requester.getId().getUserId();
    if (!permissionsChecker.getPermissions(nodeId, me).has(SharePermission.READ_AND_WRITE)) {
      throw FilesGraphQLErrors.nodeWriteError(nodeId);
    }

    nodeRepository.getNode(nodeId).orElseThrow(() -> FilesGraphQLErrors.nodeNotFound(nodeId));

    int maxNumberOfVersions = getMaxNumberOfVersions();
    if (fileVersionRepository.getFileVersions(nodeId, List.of(FileVersionSort.VERSION_DESC)).size()
        >= maxNumberOfVersions) {
      logger.debug(
          "Node {} has reached max versions ({}), cannot add more", nodeId, maxNumberOfVersions);
      throw FilesGraphQLErrors.tooManyVersionsError(nodeId);
    }

    FileVersion fileVersion =
        fileVersionRepository
            .getFileVersion(nodeId, version)
            .orElseThrow(() -> FilesGraphQLErrors.fileVersionNotFound(nodeId, version));

    Reservation reservation =
        versionReservations
            .reserve(nodeId, me)
            .orElseThrow(() -> FilesGraphQLErrors.nodeNotFound(nodeId));
    int newVersion = reservation.version();

    var copyAttempt =
        Try.of(
            () ->
                fileStore.copy(
                    FilesIdentifier.of(nodeId, version, me),
                    FilesIdentifier.of(nodeId, newVersion, me),
                    false));
    if (copyAttempt.isFailure()) {
      String error =
          MessageFormat.format("Copy error with nodeId: {0} and version {1}", nodeId, version);
      logger.error(error);
      ErrorCodes code = copyFailureClassifier.classify(copyAttempt.getCause());
      if (code == ErrorCodes.NODE_COPY_ERROR) {
        throw FilesGraphQLErrors.dataFetching(gqlContext.getPath(), error);
      }
      throw FilesGraphQLErrors.cloneCopyError(code, nodeId, version);
    }
    var copiedBlobResponse = copyAttempt.get();

    try {
      QuarkusTransaction.requiringNew()
          .run(
              () -> {
                long versionDate = versionReservations.confirm(reservation);
                if (!permissionsChecker
                    .getPermissions(nodeId, me)
                    .has(SharePermission.READ_AND_WRITE)) {
                  throw new WritePermissionRevokedException();
                }
                fileVersionRepository
                    .createFileVersionAt(
                        nodeId,
                        me,
                        newVersion,
                        fileVersion.getMimeType(),
                        copiedBlobResponse.getSize(),
                        copiedBlobResponse.getDigest(),
                        versionDate)
                    .orElseThrow(() -> new NoSuchElementException("Node not found: " + nodeId));

                nodeRepository.moveCurrentVersion(nodeId, newVersion, versionDate);

                FileVersion newFv = fileVersionRepository.getFileVersion(nodeId, newVersion).get();
                newFv.setClonedFromVersion(version);
                fileVersionRepository.updateFileVersion(newFv);
              });
    } catch (WritePermissionRevokedException e) {
      throw FilesGraphQLErrors.nodeWriteError(nodeId);
    }

    logger.debug(
        MessageFormat.format(
            "Clone version completed: node {0}, new version {1}", nodeId, newVersion));

    Node refreshed = nodeRepository.getNode(nodeId).get();
    return (FileModel) NodeModelFactory.from(refreshed, newVersion, me);
  }

  // ─── Private helpers (ported from NodeDataFetcher) ────────────────────────────

  private int getMaxNumberOfVersions() {
    return filesConfig.getMaxNumberOfVersions();
  }

  private int getMaxNumberOfKeepVersions() {
    int max = getMaxNumberOfVersions();
    return max <= Config.DIFF_MAX_VERSION_AND_MAX_KEEP_VERSION
        ? 0
        : max - Config.DIFF_MAX_VERSION_AND_MAX_KEEP_VERSION;
  }

  private void cascadeUpdateAncestors(Node parentNode) {
    List<String> childrenIds =
        nodeRepository.getChildrenIds(parentNode.getId(), Optional.empty(), Optional.empty(), true);
    if (!childrenIds.isEmpty()) {
      nodeRepository.moveNodes(childrenIds, parentNode);
      List<Node> childrenNodes =
          nodeRepository.getNodes(childrenIds, Optional.empty()).collect(Collectors.toList());
      childrenNodes.stream()
          .filter(
              n -> n.getNodeType().equals(com.zextras.carbonio.files.dal.dao.ebean.NodeType.FOLDER))
          .forEach(this::cascadeUpdateAncestors);
    }
  }

  private List<String> excludeAncestorsOf(Node destinationFolder, List<String> nodeIds) {
    List<String> destinationAncestors = destinationFolder.getAncestorsList();
    return nodeIds.stream().filter(id -> !destinationAncestors.contains(id)).toList();
  }

  private Optional<Node> restoreDestination(String nodeId) {
    return nodeRepository
        .getNode(nodeRepository.getTrashedNode(nodeId).get().getParentId())
        .filter(parent -> !parent.getAncestorsList().contains(RootId.TRASH_ROOT));
  }

  private TreeDeletion deleteLockedTree(Set<String> candidateIds, String me) {
    List<String> ids = List.copyOf(candidateIds);
    requireUnmoved(lockNodesAndParents(ids, List.of()), ids);

    List<Node> roots =
        nodeRepository
            .getNodes(ids, Optional.empty())
            .filter(Objects::nonNull)
            .filter(
                node ->
                    permissionsChecker
                        .getPermissions(node.getId(), me)
                        .has(SharePermission.READ_AND_WRITE))
            .toList();
    Set<String> rootIds = roots.stream().map(Node::getId).collect(Collectors.toSet());

    Map<String, Node> tree = new LinkedHashMap<>();
    lockedTree.collect(roots).forEach(node -> tree.putIfAbsent(node.getId(), node));
    List<Node> allNodes = List.copyOf(tree.values());

    Map<String, List<FileVersion>> versionsByOwner = new HashMap<>();
    allNodes.stream()
        .filter(
            node ->
                !node.getNodeType()
                    .equals(com.zextras.carbonio.files.dal.dao.ebean.NodeType.FOLDER))
        .forEach(
            node ->
                versionsByOwner
                    .computeIfAbsent(node.getOwnerId(), k -> new ArrayList<>())
                    .addAll(
                        fileVersionRepository.getFileVersions(
                            node.getId(), List.of(FileVersionSort.VERSION_ASC))));
    versionsByOwner.forEach(
        (ownerId, versions) -> tombstoneRepository.createTombstonesBulk(versions, ownerId));

    deleteNodesFromDb(allNodes);
    List<String> folderIds =
        allNodes.stream()
            .filter(
                node ->
                    node.getNodeType()
                        .equals(com.zextras.carbonio.files.dal.dao.ebean.NodeType.FOLDER))
            .map(Node::getId)
            .toList();
    if (!folderIds.isEmpty()) {
      shareRepository.deleteSharesBulk(
          nodeRepository.getTrashedNodeIdsByOldParents(folderIds).stream().distinct().toList());
    }
    return new TreeDeletion(versionsByOwner, rootIds);
  }

  private void deleteNodesFromDb(List<Node> nodes) {
    List<String> ids = nodes.stream().map(Node::getId).collect(Collectors.toList());
    if (!ids.isEmpty()) {
      shareRepository.deleteSharesBulk(ids);
      nodeRepository.deleteNodes(ids);
    }
  }

  private static FilesGraphQLException pathNodeNotFound(String nodeId) {
    return FilesGraphQLErrors.nodeNotFound(nodeId)
        .withPartialResults(Collections.singletonList(null));
  }

  record CopyFailure(Node node, Throwable cause) {}

  FilesGraphQLException copyFailureError(
      List<Node> nodesWithoutPermission, List<CopyFailure> copyErrors) {
    if (!nodesWithoutPermission.isEmpty()) {
      return FilesGraphQLErrors.nodeWriteError(nodesWithoutPermission.get(0).getId());
    }
    CopyFailure failure = copyErrors.get(0);
    return copyError(copyFailureClassifier.classify(failure.cause()), failure);
  }

  private static FilesGraphQLException copyError(ErrorCodes code, CopyFailure failure) {
    return FilesGraphQLErrors.nodeCopyError(
        code, failure.node().getId(), failure.node().getCurrentVersion());
  }

  private FilesGraphQLException deleteNodesFailure(List<String> nodeIds, Set<String> deletableIds) {
    return nodeIds.stream()
        .filter(id -> !deletableIds.contains(id))
        .findFirst()
        .map(FilesGraphQLErrors::nodeNotFound)
        .orElseGet(() -> FilesGraphQLErrors.nodeWriteError(deletableIds.iterator().next()))
        .withPartialResults(List.of());
  }

  private record CopyItem(
      Node source, String copyId, Optional<FileVersion> sourceVersion, List<CopyItem> children) {}

  private static final class WritePermissionRevokedException extends RuntimeException {
    WritePermissionRevokedException() {
      super(null, null, false, false);
    }
  }

  private record TreeDeletion(
      Map<String, List<FileVersion>> versionsByOwner, Set<String> deletedRootIds) {}

  private record DeletedVersions(List<FileVersion> versions, String ownerId) {
    static final DeletedVersions NODE_MISSING = new DeletedVersions(List.of(), null);
  }

  private record CopiedBlob(String copyId, long size, String digest) {}

  private CopyItem planCopy(Node source) {
    if (source.getNodeType() != com.zextras.carbonio.files.dal.dao.ebean.NodeType.FOLDER) {
      return new CopyItem(
          source,
          UUID.randomUUID().toString(),
          fileVersionRepository.getFileVersion(source.getId(), source.getCurrentVersion()),
          List.of());
    }
    List<CopyItem> children =
        nodeRepository
            .getNodes(
                nodeRepository.getChildrenIds(
                    source.getId(), Optional.empty(), Optional.empty(), false),
                Optional.empty())
            .map(this::planCopy)
            .toList();
    return new CopyItem(source, UUID.randomUUID().toString(), Optional.empty(), children);
  }

  private Optional<Throwable> copyBlobs(
      CopyItem item, String ownerId, Map<String, CopiedBlob> blobs) {
    Node source = item.source();
    if (source.getNodeType() == com.zextras.carbonio.files.dal.dao.ebean.NodeType.FOLDER) {
      for (CopyItem child : item.children()) {
        Optional<Throwable> failure = copyBlobs(child, ownerId, blobs);
        if (failure.isPresent()) {
          return failure;
        }
      }
      return Optional.empty();
    }
    if (item.sourceVersion().isEmpty()) {
      return Optional.of(new NoSuchElementException("No version " + source.getCurrentVersion()));
    }
    var copyAttempt =
        Try.of(
            () ->
                fileStore.copy(
                    FilesIdentifier.of(
                        source.getId(), source.getCurrentVersion(), source.getOwnerId()),
                    FilesIdentifier.of(item.copyId(), 1, ownerId),
                    false));
    if (copyAttempt.isFailure()) {
      logger.error("Unable to copy node {}. {}", source.getId(), copyAttempt.getCause());
      return Optional.of(copyAttempt.getCause());
    }
    var response = copyAttempt.get();
    blobs.put(
        item.copyId(), new CopiedBlob(item.copyId(), response.getSize(), response.getDigest()));
    return Optional.empty();
  }

  private void discardBlobs(Set<String> copyIds, String ownerId) {
    if (copyIds.isEmpty()) {
      return;
    }
    Set<String> notDeleted = new LinkedHashSet<>();
    try {
      List<BulkDeleteResponseItem> failedItems =
          fileStore.bulkDelete(
              IdentifierType.files,
              ownerId,
              copyIds.stream().map(id -> BulkDeleteRequestItem.filesItem(id, 1)).toList());
      if (failedItems == null) {
        notDeleted.addAll(copyIds);
      } else {
        failedItems.stream()
            .map(BulkDeleteResponseItem::getNode)
            .filter(copyIds::contains)
            .forEach(notDeleted::add);
      }
      if (!notDeleted.isEmpty()) {
        logger.warn(
            "Unable to delete the blobs {} of owner {} of a failed copy", notDeleted, ownerId);
      }
    } catch (Exception e) {
      notDeleted.addAll(copyIds);
      logger.warn(
          "Unable to delete the blobs {} of owner {} of a failed copy: {}",
          copyIds,
          ownerId,
          e.getMessage());
    }
    if (!notDeleted.isEmpty()) {
      try {
        QuarkusTransaction.requiringNew()
            .run(
                () ->
                    notDeleted.forEach(
                        id -> tombstoneRepository.createNewTombstone(id, ownerId, 1)));
      } catch (RuntimeException e) {
        logger.warn(
            "Unable to record the tombstones of the blobs {} of owner {} of a failed copy: {}",
            notDeleted,
            ownerId,
            e.getMessage());
      }
    }
  }

  private boolean copiedTopNodeIsAbsent(String copyId) {
    try {
      return QuarkusTransaction.requiringNew().call(() -> nodeRepository.getNode(copyId).isEmpty());
    } catch (RuntimeException e) {
      return false;
    }
  }

  private static String copyOwner(Node destination, String me) {
    return (com.zextras.carbonio.files.dal.dao.ebean.NodeType.ROOT.equals(destination.getNodeType())
            || me.equals(destination.getOwnerId()))
        ? me
        : destination.getOwnerId();
  }

  private Optional<NodeModel> publishCopy(
      CopyItem plan,
      Map<String, CopiedBlob> blobs,
      String destinationId,
      String ownerId,
      String me) {
    nodeLocks.lock(List.of(LockKey.children(destinationId, ownerId)));
    Optional<Node> destination =
        nodeRepository
            .getNode(destinationId)
            .filter(
                p ->
                    com.zextras.carbonio.files.dal.dao.ebean.NodeType.FOLDER.equals(p.getNodeType())
                        || com.zextras.carbonio.files.dal.dao.ebean.NodeType.ROOT.equals(
                            p.getNodeType()));
    if (destination.isEmpty()
        || !permissionsChecker
            .getPermissions(destinationId, me)
            .has(SharePermission.READ_AND_WRITE)) {
      return Optional.empty();
    }
    Node destinationFolder = destination.get();
    if (!copyOwner(destinationFolder, me).equals(ownerId)) {
      return Optional.empty();
    }
    List<Share> destinationShares =
        shareRepository.getShares(destinationId, Collections.emptyList());

    List<CopyItem> created = new ArrayList<>();
    Node top = persistCopy(plan, destinationFolder, ownerId, me, true, created);
    for (CopyItem item : created) {
      var sourceVersion = item.sourceVersion();
      if (sourceVersion.isPresent()) {
        CopiedBlob blob = blobs.get(item.copyId());
        fileVersionRepository.createNewFileVersion(
            item.copyId(),
            me,
            1,
            sourceVersion.get().getMimeType(),
            blob.size(),
            blob.digest(),
            false);
      }
    }

    List<String> newIds = created.stream().map(CopyItem::copyId).toList();
    List<String> usersToNotify = new ArrayList<>();
    for (Share share : destinationShares) {
      usersToNotify.add(share.getTargetUserId());
      shareRepository.insertInheritedShares(
          newIds, share.getTargetUserId(), share.getPermissions(), share.getExpiredAt());
    }
    usersToNotify.remove(me);
    if (!com.zextras.carbonio.files.dal.dao.ebean.NodeType.ROOT.equals(
            destinationFolder.getNodeType())
        && !me.equals(destinationFolder.getOwnerId())
        && !usersToNotify.contains(destinationFolder.getOwnerId())) {
      usersToNotify.add(destinationFolder.getOwnerId());
    }
    if (!usersToNotify.isEmpty() && filesConfig.areNotificationsEnabled()) {
      notificationRepository.createAddedNodeNotification(
          top, destinationFolder, requester, AddedNodeType.COPY, usersToNotify);
    }
    entityManager.flush();
    entityManager.refresh(top);
    return Optional.of(NodeModelFactory.from(top, null, me));
  }

  private Node persistCopy(
      CopyItem item,
      Node parent,
      String ownerId,
      String me,
      boolean dedupName,
      List<CopyItem> created) {
    Node source = item.source();
    boolean folder =
        com.zextras.carbonio.files.dal.dao.ebean.NodeType.FOLDER.equals(source.getNodeType());
    String name =
        dedupName
            ? searchAlternativeName(
                nodeRepository, source.getFullName(), parent.getId(), source.getOwnerId())
            : source.getFullName();
    Node node =
        nodeRepository.persistNewNode(
            item.copyId(),
            me,
            ownerId,
            parent.getId(),
            name,
            source.getDescription().orElse(""),
            source.getNodeType(),
            com.zextras.carbonio.files.dal.dao.ebean.NodeType.ROOT.equals(parent.getNodeType())
                ? parent.getId()
                : parent.getAncestorIds() + "," + parent.getId(),
            folder ? 0L : source.getSize());
    created.add(item);
    for (CopyItem child : item.children()) {
      persistCopy(child, node, ownerId, me, false, created);
    }
    return node;
  }
}
