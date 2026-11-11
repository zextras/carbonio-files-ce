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
import com.zextras.carbonio.files.dal.locks.NodeLockTimeoutException;
import com.zextras.carbonio.files.dal.locks.NodeLocks;
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
import com.zextras.carbonio.files.rest.services.BlobKey;
import com.zextras.carbonio.files.rest.services.BlobLifecycle;
import com.zextras.carbonio.files.rest.services.VersionReservations;
import com.zextras.carbonio.files.rest.services.VersionReservations.Reservation;
import com.zextras.carbonio.files.utilities.NodeRules;
import com.zextras.carbonio.files.utilities.PermissionsChecker;
import com.zextras.filestore.model.FilesIdentifier;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.security.Authenticated;
import io.smallrye.graphql.api.Context;
import io.smallrye.graphql.api.Nullable;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.transaction.Transactional.TxType;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
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

  private final NodeRepository nodeRepository;
  private final FileVersionRepository fileVersionRepository;
  private final PermissionsChecker permissionsChecker;
  private final ShareRepository shareRepository;
  private final FilesConfig filesConfig;
  private final CopyFailureClassifier copyFailureClassifier;
  private final GraphQLInputValidator validator;
  private final Context gqlContext;
  private final UserMyself requester;
  private final TombstoneRepository tombstoneRepository;
  private final NotificationRepository notificationRepository;
  private final ShareCascadeHelper shareCascade;
  private final NodeCreationHelper nodeCreationHelper;
  private final VersionReservations versionReservations;
  private final BlobLifecycle blobLifecycle;
  private final NodeLocks nodeLocks;

  @Inject
  public NodeApi(
      NodeRepository nodeRepository,
      FileVersionRepository fileVersionRepository,
      PermissionsChecker permissionsChecker,
      ShareRepository shareRepository,
      FilesConfig filesConfig,
      CopyFailureClassifier copyFailureClassifier,
      GraphQLInputValidator validator,
      Context gqlContext,
      @AuthenticatedUser UserMyself requester,
      TombstoneRepository tombstoneRepository,
      NotificationRepository notificationRepository,
      ShareCascadeHelper shareCascade,
      NodeCreationHelper nodeCreationHelper,
      VersionReservations versionReservations,
      BlobLifecycle blobLifecycle,
      NodeLocks nodeLocks) {
    this.nodeRepository = nodeRepository;
    this.fileVersionRepository = fileVersionRepository;
    this.permissionsChecker = permissionsChecker;
    this.shareRepository = shareRepository;
    this.filesConfig = filesConfig;
    this.copyFailureClassifier = copyFailureClassifier;
    this.validator = validator;
    this.gqlContext = gqlContext;
    this.requester = requester;
    this.tombstoneRepository = tombstoneRepository;
    this.notificationRepository = notificationRepository;
    this.shareCascade = shareCascade;
    this.nodeCreationHelper = nodeCreationHelper;
    this.versionReservations = versionReservations;
    this.blobLifecycle = blobLifecycle;
    this.nodeLocks = nodeLocks;
  }

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
  public @NonNull NodeModel updateNode(
      @Name("node_id") @NonNull String nodeId,
      @Name("name") String name,
      @Name("description") String description,
      @Name("flagged") Boolean flagged)
      throws FilesGraphQLException {
    validator.checkNodeId(nodeId).checkNodeName(name).checkNodeDescription(description).validate();
    String me = requester.getId().getUserId();
    if (!permissionsChecker.getPermissions(nodeId, me).has(SharePermission.READ_AND_WRITE)) {
      throw FilesGraphQLErrors.nodeNotFound(nodeId);
    }
    Optional<String> parentBeforeLock =
        name == null
            ? Optional.empty()
            : nodeRepository.getNode(nodeId).flatMap(Node::getParentId).map(String::trim);
    nodeLocks.lock(List.of(nodeId), parentBeforeLock.map(List::of).orElse(List.of()));
    if (!permissionsChecker.getPermissions(nodeId, me).has(SharePermission.READ_AND_WRITE)) {
      throw FilesGraphQLErrors.nodeNotFound(nodeId);
    }
    Node nodeToUpdate =
        nodeRepository
            .getNode(nodeId)
            .filter(node -> !NodeRules.isRoot(node))
            .orElseThrow(() -> FilesGraphQLErrors.nodeNotFound(nodeId));
    boolean inTrash = NodeRules.isInTrash(nodeToUpdate);
    if (inTrash && (name != null || flagged != null)) {
      throw FilesGraphQLErrors.nodeWriteError(nodeId);
    }

    String parentFolderId = nodeToUpdate.getParentId().orElse(RootId.LOCAL_ROOT);

    if (name != null) {
      if (!parentBeforeLock.equals(nodeToUpdate.getParentId().map(String::trim))) {
        throw FilesGraphQLErrors.nodeWriteError(nodeId);
      }
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
    if (inTrash) {
      nodeRepository.updateNodeKeepingDate(nodeToUpdate);
    } else {
      nodeRepository.updateNode(nodeToUpdate);
    }
    NodeModel model = NodeModelFactory.from(nodeToUpdate, null, me);
    NodeModelFactory.requireVersion(model, model);
    return model;
  }

  @Mutation("flagNodes")
  public @Nullable @Id List<@NonNull String> flagNodes(
      @Name("node_ids") @Nullable List<@NonNull String> nodeIds,
      @Name("flag") @NonNull Boolean flag)
      throws FilesGraphQLException {
    validator.requireNodeIds(nodeIds).validate();
    String me = requester.getId().getUserId();

    List<String> candidates = flaggable(nodeIds, me, false);
    nodeLocks.lockForChange(candidates);
    List<String> flaggableNodes = flaggable(candidates, me, true);

    List<String> nodesInError =
        nodeIds.stream().filter(nodeId -> !flaggableNodes.contains(nodeId)).toList();

    flaggableNodes.forEach(nodeId -> nodeRepository.flagForUser(nodeId, me, flag));

    if (!nodesInError.isEmpty()) {
      throw FilesGraphQLErrors.nodeWriteError(nodesInError.get(0))
          .withPartialResults(flaggableNodes);
    }
    return flaggableNodes;
  }

  @Mutation("trashNodes")
  public @Nullable @Id List<@NonNull String> trashNodes(
      @Name("node_ids") @Nullable List<@NonNull String> nodeIds) throws FilesGraphQLException {
    validator.checkNodeIds(nodeIds).validate();
    String me = requester.getId().getUserId();
    Set<String> lockedIds =
        writableByMe(nodeIds, me).stream()
            .filter(id -> !insideTrash(id))
            .collect(Collectors.toUnmodifiableSet());
    nodeLocks.lockForChange(lockedIds);

    List<String> trashableNodes =
        nodeIds.stream()
            .filter(nodeId -> lockedIds.contains(nodeId.trim()))
            .filter(
                nodeId -> {
                  Optional<Node> rNode = nodeRepository.getNode(nodeId);
                  return rNode.isPresent()
                      && rNode.get().getNodeType()
                          != com.zextras.carbonio.files.dal.dao.ebean.NodeType.ROOT
                      && !rNode.get().getAncestorsList().contains(RootId.TRASH_ROOT);
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
            nodeRepository.rewriteDescendantAncestors(trashedNode);
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
  public @Nullable List<NodeModel> restoreNodes(
      @Name("node_ids") @Nullable List<@NonNull String> nodeIds) throws FilesGraphQLException {
    validator.checkNodeIds(nodeIds).validate();
    String me = requester.getId().getUserId();

    List<String> writableIds = writableByMe(nodeIds, me);
    Map<String, String> parentBeforeLock = new HashMap<>();
    writableIds.forEach(
        id ->
            nodeRepository
                .getTrashedNode(id)
                .ifPresent(t -> parentBeforeLock.put(id, t.getParentId().trim())));
    nodeLocks.lock(parentBeforeLock.keySet(), parentBeforeLock.values());

    long retentionCutoff =
        NodeRules.trashRetentionCutoff(
            System.currentTimeMillis(),
            Constants.Config.PurgeService.RETENTION_TRASHED_ITEMS_IN_DAYS);
    List<String> restorableNodeIds =
        nodeIds.stream()
            .filter(
                nodeId -> {
                  Optional<Node> rNode = nodeRepository.getNode(nodeId);
                  return rNode.isPresent()
                      && rNode.get().getNodeType()
                          != com.zextras.carbonio.files.dal.dao.ebean.NodeType.ROOT
                      && !NodeRules.isPastTrashRetention(
                          rNode.get().getUpdatedAt(), retentionCutoff);
                })
            .filter(
                nodeId ->
                    permissionsChecker
                        .getPermissions(nodeId, me)
                        .has(SharePermission.READ_AND_WRITE))
            .filter(
                nodeId ->
                    nodeRepository
                        .getTrashedNode(nodeId)
                        .map(t -> t.getParentId().trim().equals(parentBeforeLock.get(nodeId)))
                        .orElse(false))
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

                    NodeRules.inheritableShares(shareRepository, destParentId)
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
                  nodeRepository.rewriteDescendantAncestors(node);
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
  public @Nullable List<@NonNull NodeModel> moveNodes(
      @Name("node_ids") @Nullable List<@NonNull String> nodeIds,
      @Name("destination_id") @Id @NonNull String destinationId)
      throws FilesGraphQLException {
    validator.checkNodeIds(nodeIds).checkNodeId(destinationId).validate();
    String me = requester.getId().getUserId();

    List<String> movable = writableByMe(nodeIds, me);
    boolean destinationWritable = !writableByMe(List.of(destinationId), me).isEmpty();
    if (!destinationWritable) {
      throw FilesGraphQLErrors.nodeWriteError(destinationId);
    }
    nodeLocks.lock(movable, List.of(destinationId));

    if (!permissionsChecker.getPermissions(destinationId, me).has(SharePermission.READ_AND_WRITE)) {
      throw FilesGraphQLErrors.nodeWriteError(destinationId);
    }

    Node destinationFolder =
        nodeRepository
            .getNode(destinationId)
            .filter(NodeRules::acceptsChildren)
            .orElseThrow(() -> FilesGraphQLErrors.nodeWriteError(destinationId));

    List<String> rootIds =
        nodeRepository.getRootsList().stream().map(Node::getId).collect(Collectors.toList());

    List<String> movableIds =
        nodeIds.stream()
            .filter(nodeId -> movable.contains(nodeId.trim()))
            .filter(
                nodeId ->
                    permissionsChecker
                        .getPermissions(nodeId, me)
                        .has(SharePermission.READ_AND_WRITE))
            .filter(nodeId -> !rootIds.contains(nodeId))
            .filter(nodeId -> !destinationId.equals(nodeId))
            .filter(
                nodeId ->
                    nodeRepository
                        .getNode(nodeId)
                        .map(n -> !n.getAncestorsList().contains(RootId.TRASH_ROOT))
                        .orElse(false))
            .toList();
    List<String> nodeIdsToMove = excludeAncestorsOf(destinationFolder, movableIds);

    List<NodeModel> movedNodesResult = List.of();
    if (!nodeIdsToMove.isEmpty()) {
      List<Runnable> pendingNotifications = new ArrayList<>();
      nodeIdsToMove.forEach(
          nodeId -> {
            Node node = nodeRepository.getNode(nodeId).get();
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
            nodeRepository.rewriteDescendantAncestors(node);

            shareRepository.getShares(nodeId, Collections.emptyList()).stream()
                .filter(share -> !share.isDirect())
                .forEach(
                    share -> {
                      shareRepository.deleteShare(nodeId, share.getTargetUserId());
                      shareCascade.cascadeDeleteShare(nodeId, share.getTargetUserId());
                    });

            List<String> usersToNotifyAdd = new ArrayList<>();
            NodeRules.inheritableShares(shareRepository, destinationId)
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

    Deletion deletion;
    try {
      deletion =
          QuarkusTransaction.requiringNew().call(() -> deleteNodesInTransaction(nodeIds, me));
    } catch (NodeLockTimeoutException e) {
      logger.warn("Node locks not acquired during deleteNodes: {}", e.getMessage());
      throw deleteNodesFailure(nodeIds);
    } catch (RuntimeException e) {
      logger.error("DB error during deleteNodes, rolling back", e);
      throw deleteNodesFailure(nodeIds);
    }

    deletion.keysByOwner().forEach(blobLifecycle::deleteAndClear);

    Set<String> requestedNodeIds = deletion.deletedIds();
    List<String> successIds = new ArrayList<>(requestedNodeIds);
    List<String> errorIds = nodeIds.stream().filter(id -> !requestedNodeIds.contains(id)).toList();

    if (!errorIds.isEmpty()) {
      throw FilesGraphQLErrors.nodeNotFound(errorIds.get(0)).withPartialResults(successIds);
    }
    return successIds;
  }

  private record Deletion(Set<String> deletedIds, Map<String, List<BlobKey>> keysByOwner) {}

  private Deletion deleteNodesInTransaction(List<String> nodeIds, String me) {
    Set<String> lockedIds = Set.copyOf(writableByMe(nodeIds, me));
    nodeLocks.lockForChange(lockedIds);

    List<Node> requestedNodes =
        nodeRepository
            .getNodes(nodeIds, Optional.empty())
            .filter(Objects::nonNull)
            .filter(
                node ->
                    !node.getNodeType()
                        .equals(com.zextras.carbonio.files.dal.dao.ebean.NodeType.ROOT))
            .filter(node -> lockedIds.contains(node.getId()))
            .filter(
                node ->
                    permissionsChecker
                        .getPermissions(node.getId(), me)
                        .has(SharePermission.READ_AND_WRITE))
            .collect(Collectors.toList());

    Set<String> deletableIds = requestedNodes.stream().map(Node::getId).collect(Collectors.toSet());

    return new Deletion(deletableIds, nodeRepository.deleteSubtrees(deletableIds));
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
            .filter(NodeRules::acceptsChildren)
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
    List<CopyFailure> nestedCopyErrors = new ArrayList<>();

    List<Node> nodesInCopyOrder =
        Stream.concat(
                nodesToCopy.stream()
                    .filter(n -> targetFolderChildrenNames.contains(n.getFullName())),
                nodesToCopy.stream()
                    .filter(n -> !targetFolderChildrenNames.contains(n.getFullName())))
            .toList();

    for (Node node : nodesInCopyOrder) {
      CopyOutcome outcome = copyTopNode(node, destinationFolder, me, nestedCopyErrors);
      outcome
          .node()
          .ifPresentOrElse(
              copied -> {
                copiedNodesResult.add(NodeModelFactory.from(copied, null, me));
                notifyCopy(copied, destinationFolder, me);
              },
              () -> copyErrors.add(new CopyFailure(node, outcome.failure())));
    }

    copyErrors.addAll(nestedCopyErrors);

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

    DeleteVersionsOutcome outcome;
    try {
      outcome =
          QuarkusTransaction.requiringNew()
              .call(() -> deleteVersionsInTransaction(nodeId, me, versions));
    } catch (NodeLockTimeoutException e) {
      logger.warn("Node locks not acquired during deleteVersions: {}", e.getMessage());
      throw FilesGraphQLErrors.nodeWriteError(nodeId);
    } catch (RuntimeException e) {
      logger.error("DB error during deleteVersions, rolling back: {}", e.getMessage());
      throw FilesGraphQLErrors.nodeWriteError(nodeId);
    }

    if (outcome.result() == DeleteVersionsResult.NOT_WRITABLE) {
      throw FilesGraphQLErrors.nodeWriteError(nodeId);
    }

    List<FileVersion> fileVersionsToDelete = outcome.deleted();
    List<Integer> versionsToDelete =
        fileVersionsToDelete.stream().map(FileVersion::getVersion).collect(Collectors.toList());

    if (!versionsToDelete.isEmpty()) {
      blobLifecycle.deleteAndClear(
          outcome.ownerId(), fileVersionsToDelete.stream().map(BlobKey::of).toList());
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

  private enum DeleteVersionsResult {
    DELETED,
    NOT_WRITABLE
  }

  private record DeleteVersionsOutcome(
      DeleteVersionsResult result, String ownerId, List<FileVersion> deleted) {}

  private DeleteVersionsOutcome deleteVersionsInTransaction(
      String nodeId, String me, List<Integer> versions) {
    boolean writable = !writableByMe(List.of(nodeId), me).isEmpty();
    if (writable) {
      nodeLocks.lockForChange(List.of(nodeId));
    }
    if (!writable
        || !permissionsChecker.getPermissions(nodeId, me).has(SharePermission.READ_AND_WRITE)) {
      return new DeleteVersionsOutcome(DeleteVersionsResult.NOT_WRITABLE, null, List.of());
    }
    Node node = nodeRepository.getNode(nodeId).orElseThrow();
    List<FileVersion> toDelete =
        (versions != null
                ? fileVersionRepository.getFileVersions(nodeId, versions)
                : fileVersionRepository.getFileVersions(
                    nodeId, List.of(FileVersionSort.VERSION_DESC)))
            .stream()
                .filter(fv -> !fv.isKeptForever())
                .filter(fv -> !node.getCurrentVersion().equals(fv.getVersion()))
                .collect(Collectors.toList());
    if (!toDelete.isEmpty()) {
      tombstoneRepository.createTombstonesBulk(toDelete, node.getOwnerId());
      fileVersionRepository.deleteFileVersions(
          nodeId, toDelete.stream().map(FileVersion::getVersion).toList());
    }
    return new DeleteVersionsOutcome(DeleteVersionsResult.DELETED, node.getOwnerId(), toDelete);
  }

  @Mutation("keepVersions")
  public @NonNull List<Integer> keepVersions(
      @Name("node_id") @Id @NonNull String nodeId,
      @Name("versions") @NonNull List<@NonNull Integer> versions,
      @Name("keep_forever") @NonNull Boolean keepForever)
      throws FilesGraphQLException {
    String me = requester.getId().getUserId();
    boolean writable = !writableByMe(List.of(nodeId), me).isEmpty();
    if (writable) {
      nodeLocks.lockForChange(List.of(nodeId));
    }
    if (!writable
        || !permissionsChecker.getPermissions(nodeId, me).has(SharePermission.READ_AND_WRITE)) {
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

    String ownerId =
        nodeRepository
            .getNode(nodeId)
            .orElseThrow(() -> FilesGraphQLErrors.nodeNotFound(nodeId))
            .getOwnerId();

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
            .reserve(nodeId, ownerId)
            .orElseThrow(() -> FilesGraphQLErrors.nodeNotFound(nodeId));
    int newVersion = reservation.version();

    var copyAttempt =
        blobLifecycle.copy(
            FilesIdentifier.of(nodeId, version, ownerId),
            FilesIdentifier.of(nodeId, newVersion, ownerId));
    if (copyAttempt.isFailure()) {
      blobLifecycle.abandon(reservation);
      throw cloneCopyFailure(nodeId, version, copyAttempt.getCause());
    }
    var copiedBlobResponse = copyAttempt.get();

    try {
      QuarkusTransaction.requiringNew()
          .run(
              () -> {
                nodeLocks.lockForChange(List.of(nodeId));
                long versionDate = versionReservations.confirm(reservation);
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
    } catch (RuntimeException e) {
      blobLifecycle.discard(reservation);
      throw e;
    }

    logger.debug(
        MessageFormat.format(
            "Clone version completed: node {0}, new version {1}", nodeId, newVersion));

    Node refreshed = nodeRepository.getNode(nodeId).get();
    return (FileModel) NodeModelFactory.from(refreshed, newVersion, me);
  }

  // ─── Private helpers (ported from NodeDataFetcher) ────────────────────────────

  private FilesGraphQLException cloneCopyFailure(String nodeId, int version, Throwable cause) {
    String error =
        MessageFormat.format("Copy error with nodeId: {0} and version {1}", nodeId, version);
    logger.error(error);
    ErrorCodes code = copyFailureClassifier.classify(cause);
    if (code == ErrorCodes.NODE_COPY_ERROR) {
      return FilesGraphQLErrors.dataFetching(gqlContext.getPath(), error);
    }
    return FilesGraphQLErrors.cloneCopyError(code, nodeId, version);
  }

  private int getMaxNumberOfVersions() {
    return filesConfig.getMaxNumberOfVersions();
  }

  private int getMaxNumberOfKeepVersions() {
    int max = getMaxNumberOfVersions();
    return max <= Config.DIFF_MAX_VERSION_AND_MAX_KEEP_VERSION
        ? 0
        : max - Config.DIFF_MAX_VERSION_AND_MAX_KEEP_VERSION;
  }

  private void createIndirectShare(String sharedParentId, Node nodeToShare) {
    NodeRules.inheritableShares(shareRepository, sharedParentId)
        .forEach(
            share -> {
              shareRepository.upsertShare(
                  nodeToShare.getId(),
                  share.getTargetUserId(),
                  share.getPermissions(),
                  false,
                  false,
                  share.getExpiredAt());
              if (nodeToShare
                  .getNodeType()
                  .equals(com.zextras.carbonio.files.dal.dao.ebean.NodeType.FOLDER)) {
                shareCascade.cascadeUpsertShare(
                    nodeToShare.getId(),
                    share.getTargetUserId(),
                    share.getPermissions(),
                    share.getExpiredAt());
              }
            });
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

  private List<String> flaggable(List<String> nodeIds, String me, boolean excludeTrash) {
    return nodeIds.stream()
        .filter(
            nodeId ->
                nodeRepository
                    .getNode(nodeId)
                    .filter(
                        node ->
                            node.getNodeType()
                                    != com.zextras.carbonio.files.dal.dao.ebean.NodeType.ROOT
                                && !(excludeTrash && NodeRules.isInTrash(node)))
                    .isPresent())
        .filter(
            nodeId ->
                permissionsChecker.getPermissions(nodeId, me).has(SharePermission.READ_AND_WRITE))
        .collect(Collectors.toList());
  }

  private boolean insideTrash(String nodeId) {
    return nodeRepository.getNode(nodeId).map(NodeRules::isInTrash).orElse(false);
  }

  private List<String> writableByMe(List<String> nodeIds, String me) {
    List<String> ids = nodeIds.stream().filter(Objects::nonNull).map(String::trim).toList();
    Map<String, ACL> permissions = permissionsChecker.getPermissions(ids, me);
    return ids.stream()
        .filter(id -> permissions.get(id).has(SharePermission.READ_AND_WRITE))
        .toList();
  }

  private static FilesGraphQLException pathNodeNotFound(String nodeId) {
    return FilesGraphQLErrors.nodeNotFound(nodeId)
        .withPartialResults(Collections.singletonList(null));
  }

  private record CopyOutcome(Optional<Node> node, Throwable failure) {}

  record CopyFailure(Node node, Throwable cause) {}

  private static final class CopyDestinationRefusedException extends NoSuchElementException {
    CopyDestinationRefusedException(String destinationId) {
      super(
          "Destination " + destinationId + " no longer accepts children (deleted or in the trash)");
    }
  }

  private void logCopyFailure(String description, RuntimeException e) {
    if (e instanceof CopyIntoItselfException || e instanceof CopyDestinationRefusedException) {
      logger.warn("{}: {}", description, e.getMessage());
    } else {
      logger.error(description, e);
    }
  }

  private static final class CopyIntoItselfException extends RuntimeException {
    CopyIntoItselfException(String folderId) {
      super("Folder " + folderId + " cannot be copied into itself or its descendants");
    }
  }

  FilesGraphQLException copyFailureError(
      List<Node> nodesWithoutPermission, List<CopyFailure> copyErrors) {
    if (!nodesWithoutPermission.isEmpty()) {
      return FilesGraphQLErrors.nodeWriteError(nodesWithoutPermission.get(0).getId());
    }
    CopyFailure failure = copyErrors.get(0);
    if (failure.cause() instanceof CopyIntoItselfException) {
      return FilesGraphQLErrors.nodeWriteError(failure.node().getId());
    }
    return copyError(copyFailureClassifier.classify(failure.cause()), failure);
  }

  private static FilesGraphQLException copyError(ErrorCodes code, CopyFailure failure) {
    return FilesGraphQLErrors.nodeCopyError(
        code, failure.node().getId(), failure.node().getCurrentVersion());
  }

  private FilesGraphQLException deleteNodesFailure(List<String> nodeIds) {
    return FilesGraphQLErrors.nodeWriteError(nodeIds.get(0)).withPartialResults(List.of());
  }

  /** Copies a requested node: a file, or a folder with everything under it. */
  private CopyOutcome copyTopNode(
      Node node, Node destinationFolder, String me, List<CopyFailure> nestedCopyErrors) {
    if (node.getNodeType() != com.zextras.carbonio.files.dal.dao.ebean.NodeType.FOLDER) {
      return copyFile(node, destinationFolder, me, true);
    }
    Node copiedFolder;
    try {
      copiedFolder = copyFolder(node, destinationFolder, me, true);
    } catch (RuntimeException e) {
      logCopyFailure("Unable to copy folder " + node.getId(), e);
      return new CopyOutcome(Optional.empty(), e);
    }
    copyFolderCascade(node.getId(), copiedFolder, me, nestedCopyErrors);
    return new CopyOutcome(Optional.of(copiedFolder), null);
  }

  private void notifyCopy(Node copied, Node destinationFolder, String me) {
    List<String> usersToNotify =
        new ArrayList<>(
            shareRepository.getShares(copied.getId(), Collections.emptyList()).stream()
                .map(Share::getTargetUserId)
                .toList());
    usersToNotify.remove(me);
    if (!com.zextras.carbonio.files.dal.dao.ebean.NodeType.ROOT.equals(
            destinationFolder.getNodeType())
        && !me.equals(destinationFolder.getOwnerId())
        && !usersToNotify.contains(destinationFolder.getOwnerId())) {
      usersToNotify.add(destinationFolder.getOwnerId());
    }
    if (!usersToNotify.isEmpty() && filesConfig.areNotificationsEnabled()) {
      notificationRepository.createAddedNodeNotification(
          copied, destinationFolder, requester, AddedNodeType.COPY, usersToNotify);
    }
  }

  private CopyOutcome copyFile(
      Node sourceNode, Node destinationFolder, String requesterId, boolean dedupName) {
    String ownerId =
        (com.zextras.carbonio.files.dal.dao.ebean.NodeType.ROOT.equals(
                    destinationFolder.getNodeType())
                || requesterId.equals(destinationFolder.getOwnerId()))
            ? requesterId
            : destinationFolder.getOwnerId();

    String newId = UUID.randomUUID().toString();
    FilesIdentifier source =
        FilesIdentifier.of(
            sourceNode.getId(), sourceNode.getCurrentVersion(), sourceNode.getOwnerId());
    FilesIdentifier destination = FilesIdentifier.of(newId, 1, ownerId);
    FileVersion sourceCurrentVersion;
    Reservation reservation;
    try {
      sourceCurrentVersion =
          fileVersionRepository
              .getFileVersion(sourceNode.getId(), sourceNode.getCurrentVersion())
              .orElseThrow(
                  () ->
                      new NoSuchElementException(
                          "Version "
                              + sourceNode.getCurrentVersion()
                              + " of node "
                              + sourceNode.getId()
                              + " not found"));
      reservation = versionReservations.reserveNew(newId, 1, ownerId);
    } catch (RuntimeException e) {
      logger.error("Unable to copy node {}: {}", sourceNode.getId(), e.getMessage(), e);
      return new CopyOutcome(Optional.empty(), e);
    }

    var copyAttempt = blobLifecycle.copy(source, destination);
    if (copyAttempt.isFailure()) {
      logger.error("Unable to copy node {}", sourceNode.getId(), copyAttempt.getCause());
      blobLifecycle.abandon(reservation);
      return new CopyOutcome(Optional.empty(), copyAttempt.getCause());
    }
    var response = copyAttempt.get();

    try {
      QuarkusTransaction.requiringNew()
          .run(
              () -> {
                nodeLocks.lock(List.of(), List.of(destinationFolder.getId()));
                Node parent =
                    nodeRepository
                        .getNode(destinationFolder.getId())
                        .filter(NodeRules::acceptsChildren)
                        .orElseThrow(
                            () -> new CopyDestinationRefusedException(destinationFolder.getId()));
                versionReservations.confirmNew(reservation);
                Node created =
                    nodeRepository.createNewNode(
                        newId,
                        requesterId,
                        ownerId,
                        parent.getId(),
                        dedupName
                            ? searchAlternativeName(
                                nodeRepository,
                                sourceNode.getFullName(),
                                parent.getId(),
                                sourceNode.getOwnerId())
                            : sourceNode.getFullName(),
                        sourceNode.getDescription().orElse(""),
                        sourceNode.getNodeType(),
                        com.zextras.carbonio.files.dal.dao.ebean.NodeType.ROOT.equals(
                                parent.getNodeType())
                            ? parent.getId()
                            : parent.getAncestorIds() + "," + parent.getId(),
                        sourceNode.getSize());
                fileVersionRepository.createNewFileVersion(
                    newId,
                    requesterId,
                    1,
                    sourceCurrentVersion.getMimeType(),
                    response.getSize(),
                    response.getDigest(),
                    false);
                createIndirectShare(parent.getId(), created);
              });
    } catch (RuntimeException e) {
      logCopyFailure("Unable to publish the copy " + newId + " of node " + sourceNode.getId(), e);
      blobLifecycle.discard(reservation);
      return new CopyOutcome(Optional.empty(), e);
    }

    return new CopyOutcome(nodeRepository.getNode(newId), null);
  }

  private Node copyFolder(
      Node sourceFolder, Node destinationFolder, String requesterId, boolean dedupName) {
    return QuarkusTransaction.requiringNew()
        .call(
            () -> {
              nodeLocks.lock(List.of(), List.of(destinationFolder.getId()));
              Node parent =
                  nodeRepository
                      .getNode(destinationFolder.getId())
                      .filter(NodeRules::acceptsChildren)
                      .orElseThrow(
                          () -> new CopyDestinationRefusedException(destinationFolder.getId()));
              if (parent.getId().equals(sourceFolder.getId())
                  || parent.getAncestorsList().contains(sourceFolder.getId())) {
                throw new CopyIntoItselfException(sourceFolder.getId());
              }
              String ownerId =
                  (com.zextras.carbonio.files.dal.dao.ebean.NodeType.ROOT.equals(
                              parent.getNodeType())
                          || requesterId.equals(parent.getOwnerId()))
                      ? requesterId
                      : parent.getOwnerId();

              Node created =
                  nodeRepository.createNewNode(
                      UUID.randomUUID().toString(),
                      requesterId,
                      ownerId,
                      parent.getId(),
                      dedupName
                          ? searchAlternativeName(
                              nodeRepository,
                              sourceFolder.getFullName(),
                              parent.getId(),
                              sourceFolder.getOwnerId())
                          : sourceFolder.getName(),
                      sourceFolder.getDescription().orElse(""),
                      com.zextras.carbonio.files.dal.dao.ebean.NodeType.FOLDER,
                      com.zextras.carbonio.files.dal.dao.ebean.NodeType.ROOT.equals(
                              parent.getNodeType())
                          ? parent.getId()
                          : parent.getAncestorIds() + "," + parent.getId(),
                      0L);
              createIndirectShare(parent.getId(), created);
              return created;
            });
  }

  private void copyFolderCascade(
      String sourceFolderId,
      Node destinationFolder,
      String requesterId,
      List<CopyFailure> failures) {
    List<Node> folderChildren =
        nodeRepository
            .getNodes(
                nodeRepository.getChildrenIds(
                    sourceFolderId, Optional.empty(), Optional.empty(), false),
                Optional.empty())
            .collect(Collectors.toList());

    List<Node> filesToCopy =
        folderChildren.stream()
            .filter(
                node ->
                    !node.getNodeType()
                        .equals(com.zextras.carbonio.files.dal.dao.ebean.NodeType.FOLDER))
            .collect(Collectors.toList());

    List<Node> foldersToCopy =
        folderChildren.stream()
            .filter(
                node ->
                    node.getNodeType()
                        .equals(com.zextras.carbonio.files.dal.dao.ebean.NodeType.FOLDER))
            .collect(Collectors.toList());

    filesToCopy.forEach(
        file -> {
          CopyOutcome outcome = copyFile(file, destinationFolder, requesterId, false);
          if (outcome.node().isEmpty()) {
            failures.add(new CopyFailure(file, outcome.failure()));
          }
        });

    foldersToCopy.forEach(
        folder -> {
          Node copiedFolder;
          try {
            copiedFolder = copyFolder(folder, destinationFolder, requesterId, false);
          } catch (RuntimeException e) {
            logCopyFailure("Unable to copy folder " + folder.getId(), e);
            failures.add(new CopyFailure(folder, e));
            return;
          }
          copyFolderCascade(folder.getId(), copiedFolder, requesterId, failures);
        });
  }
}
