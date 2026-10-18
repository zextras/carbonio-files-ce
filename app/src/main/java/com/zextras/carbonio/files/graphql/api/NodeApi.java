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
import jakarta.transaction.Transactional;
import jakarta.transaction.Transactional.TxType;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
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
    Set<ImmutablePair<String, String>> lockedNames = new HashSet<>();
    if (name != null) {
      nodeRepository
          .getNode(nodeId)
          .ifPresent(
              node ->
                  lockChildNames(
                      List.of(
                          ImmutablePair.of(
                              node.getParentId().orElse(RootId.LOCAL_ROOT), node.getOwnerId())),
                      lockedNames));
    }
    Node nodeToUpdate =
        nodeRepository
            .getNodeForUpdate(nodeId)
            .orElseThrow(() -> FilesGraphQLErrors.nodeNotFound(nodeId));

    String parentFolderId = nodeToUpdate.getParentId().orElse(RootId.LOCAL_ROOT);

    if (name != null) {
      lockChildNames(
          List.of(ImmutablePair.of(parentFolderId, nodeToUpdate.getOwnerId())), lockedNames);
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
  public @Nullable @Id List<@NonNull String> flagNodes(
      @Name("node_ids") @Nullable List<@NonNull String> nodeIds,
      @Name("flag") @NonNull Boolean flag)
      throws FilesGraphQLException {
    validator.requireNodeIds(nodeIds).validate();
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

    nodeRepository.getNodesForUpdate(flaggableNodes);
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
      List<Node> lockedNodes = nodeRepository.getNodesForUpdate(trashableNodes);
      List<String> alreadyTrashedIds =
          nodeRepository.getTrashedNodeIds(lockedNodes.stream().map(Node::getId).toList());
      List<Node> nodesToTrash =
          lockedNodes.stream().filter(node -> !alreadyTrashedIds.contains(node.getId())).toList();
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
  public @Nullable List<NodeModel> restoreNodes(
      @Name("node_ids") @Nullable List<@NonNull String> nodeIds) throws FilesGraphQLException {
    validator.checkNodeIds(nodeIds).validate();
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

    Map<String, Optional<Node>> destinations = lockForRestore(restorableNodeIds);
    restorableNodeIds.retainAll(destinations.keySet());
    List<NodeModel> results =
        nodeRepository
            .getNodes(restorableNodeIds, Optional.empty())
            .map(
                node -> {
                  Optional<Node> parentNode = destinations.get(node.getId());
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
  public @Nullable List<@NonNull NodeModel> moveNodes(
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
    List<String> nodeIdsToMove = lockForMove(movableIds, destinationFolder, destinationId, rootIds);

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

    Set<String> requestedNodeIds =
        requestedNodes.stream().map(Node::getId).collect(Collectors.toSet());

    List<Node> allNodes = collectAllDescendants(requestedNodes);

    List<Node> fileNodes =
        allNodes.stream()
            .filter(
                node ->
                    !node.getNodeType()
                        .equals(com.zextras.carbonio.files.dal.dao.ebean.NodeType.FOLDER))
            .toList();

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

    try {
      QuarkusTransaction.requiringNew()
          .run(
              () -> {
                fileVersionsByOwner.forEach(
                    (ownerId, versions) ->
                        tombstoneRepository.createTombstonesBulk(versions, ownerId));
                deleteNodesFromDb(allNodes);
                allNodes.stream()
                    .filter(
                        node ->
                            node.getNodeType()
                                .equals(com.zextras.carbonio.files.dal.dao.ebean.NodeType.FOLDER))
                    .map(Node::getId)
                    .forEach(this::cascadeDeleteNodeHelper);
              });
    } catch (RuntimeException e) {
      logger.error("DB error during deleteNodes, rolling back: {}", e.getMessage());
      throw deleteNodesFailure(nodeIds, requestedNodeIds);
    }

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

    nodesInCopyOrder.forEach(
        node -> {
          if (node.getNodeType() == com.zextras.carbonio.files.dal.dao.ebean.NodeType.FOLDER) {
            Node copiedFolder = copyFolder(node, destinationFolder, me, true);
            copiedNodesResult.add(NodeModelFactory.from(copiedFolder, null, me));
            copyFolderCascade(node.getId(), copiedFolder, me);

            List<String> usersToNotify = createIndirectShare(destinationId, copiedFolder);
            usersToNotify.remove(me);
            if (!com.zextras.carbonio.files.dal.dao.ebean.NodeType.ROOT.equals(
                    destinationFolder.getNodeType())
                && !me.equals(destinationFolder.getOwnerId())
                && !usersToNotify.contains(destinationFolder.getOwnerId())) {
              usersToNotify.add(destinationFolder.getOwnerId());
            }
            if (!usersToNotify.isEmpty() && filesConfig.areNotificationsEnabled()) {
              notificationRepository.createAddedNodeNotification(
                  copiedFolder, destinationFolder, requester, AddedNodeType.COPY, usersToNotify);
            }
          } else {
            CopyOutcome outcome = copyFile(node, destinationFolder, me, true);
            if (outcome.node().isPresent()) {
              Node copiedNode = outcome.node().get();
              copiedNodesResult.add(NodeModelFactory.from(copiedNode, null, me));
              List<String> usersToNotify = createIndirectShare(destinationId, copiedNode);
              usersToNotify.remove(me);
              if (!com.zextras.carbonio.files.dal.dao.ebean.NodeType.ROOT.equals(
                      destinationFolder.getNodeType())
                  && !me.equals(destinationFolder.getOwnerId())
                  && !usersToNotify.contains(destinationFolder.getOwnerId())) {
                usersToNotify.add(destinationFolder.getOwnerId());
              }
              if (!usersToNotify.isEmpty() && filesConfig.areNotificationsEnabled()) {
                notificationRepository.createAddedNodeNotification(
                    copiedNode, destinationFolder, requester, AddedNodeType.COPY, usersToNotify);
              }
            } else {
              copyErrors.add(new CopyFailure(node, outcome.failure()));
            }
          }
        });

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

    Node node =
        nodeRepository.getNode(nodeId).orElseThrow(() -> FilesGraphQLErrors.nodeNotFound(nodeId));

    List<FileVersion> fileVersionsToDelete =
        (versions != null
                ? fileVersionRepository.getFileVersions(nodeId, versions)
                : fileVersionRepository.getFileVersions(
                    nodeId, List.of(FileVersionSort.VERSION_DESC)))
            .stream()
                .filter(fv -> !fv.isKeptForever())
                .filter(fv -> !node.getCurrentVersion().equals(fv.getVersion()))
                .collect(Collectors.toList());

    List<Integer> versionsToDelete =
        fileVersionsToDelete.stream().map(FileVersion::getVersion).collect(Collectors.toList());

    if (!versionsToDelete.isEmpty()) {
      String ownerId = node.getOwnerId();
      try {
        QuarkusTransaction.requiringNew()
            .run(
                () -> {
                  tombstoneRepository.createTombstonesBulk(fileVersionsToDelete, ownerId);
                  fileVersionRepository.deleteFileVersions(nodeId, versionsToDelete);
                });
      } catch (RuntimeException e) {
        logger.error("DB error during deleteVersions, rolling back: {}", e.getMessage());
        throw FilesGraphQLErrors.nodeWriteError(nodeId);
      }

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
  public @NonNull List<Integer> keepVersions(
      @Name("node_id") @Id @NonNull String nodeId,
      @Name("versions") @NonNull List<@NonNull Integer> versions,
      @Name("keep_forever") @NonNull Boolean keepForever)
      throws FilesGraphQLException {
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

  @Mutation("cloneVersion")
  @Transactional(TxType.NOT_SUPPORTED)
  public @NonNull FileModel cloneVersion(
      @Name("node_id") @Id @NonNull String nodeId, @Name("version") @NonNull Integer version)
      throws FilesGraphQLException {
    String me = requester.getId().getUserId();
    if (!permissionsChecker.getPermissions(nodeId, me).has(SharePermission.READ_AND_WRITE)) {
      throw FilesGraphQLErrors.nodeWriteError(nodeId);
    }

    Node node =
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

    Integer newVersion = node.getCurrentVersion() + 1;

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

    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              Node lockedNode = nodeRepository.getNodeForUpdate(nodeId).get();
              fileVersionRepository.createNewFileVersion(
                  nodeId,
                  me,
                  newVersion,
                  fileVersion.getMimeType(),
                  copiedBlobResponse.getSize(),
                  copiedBlobResponse.getDigest(),
                  false);

              lockedNode.setCurrentVersion(newVersion);
              nodeRepository.updateNode(lockedNode);

              FileVersion newFv = fileVersionRepository.getFileVersion(nodeId, newVersion).get();
              newFv.setClonedFromVersion(version);
              fileVersionRepository.updateFileVersion(newFv);
            });

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
    return targetUserIds;
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

  // Name locks go before row locks (see MoveNodesDeadlockApiIT), except a rare follow-up lock.
  private void lockChildNames(
      Collection<ImmutablePair<String, String>> folderOwnerKeys,
      Set<ImmutablePair<String, String>> locked) {
    for (ImmutablePair<String, String> key : new TreeSet<>(folderOwnerKeys)) {
      if (locked.add(key)) {
        nodeRepository.lockChildNames(key.getLeft(), key.getRight());
      }
    }
  }

  private List<ImmutablePair<String, String>> destinationKeys(
      List<Node> nodes, String destinationId) {
    return nodes.stream().map(node -> ImmutablePair.of(destinationId, node.getOwnerId())).toList();
  }

  private List<String> lockForMove(
      List<String> movableIds, Node destinationFolder, String destinationId, List<String> rootIds)
      throws FilesGraphQLException {
    if (movableIds.isEmpty()) {
      return movableIds;
    }
    Set<ImmutablePair<String, String>> lockedNames = new HashSet<>();
    lockChildNames(
        destinationKeys(
            nodeRepository.getNodes(movableIds, Optional.empty()).toList(), destinationId),
        lockedNames);
    boolean destinationIsRoot = rootIds.contains(destinationId);
    List<String> idsToLock = new ArrayList<>(movableIds);
    if (!destinationIsRoot) {
      idsToLock.add(destinationId);
    }
    List<Node> lockedNodes = nodeRepository.getNodesForUpdate(idsToLock);
    List<String> lockedIds = lockedNodes.stream().map(Node::getId).toList();
    if (!destinationIsRoot && !lockedIds.contains(destinationId)) {
      throw FilesGraphQLErrors.nodeWriteError(destinationId);
    }
    List<String> destinationAncestors = destinationFolder.getAncestorsList();
    List<String> idsToMove =
        movableIds.stream()
            .filter(lockedIds::contains)
            .filter(id -> !destinationAncestors.contains(id))
            .toList();
    lockChildNames(
        destinationKeys(
            lockedNodes.stream().filter(node -> idsToMove.contains(node.getId())).toList(),
            destinationId),
        lockedNames);
    return idsToMove;
  }

  private Map<String, Optional<Node>> lockForRestore(List<String> nodeIds) {
    Set<ImmutablePair<String, String>> locked = new HashSet<>();
    List<Node> preRead = nodeRepository.getNodes(nodeIds, Optional.empty()).toList();
    lockChildNames(restoreKeys(preRead, restoreDestinations(preRead)), locked);
    List<Node> lockedNodes = nodeRepository.getNodesForUpdate(nodeIds);
    List<String> trashedIds = nodeRepository.getTrashedNodeIds(nodeIds);
    List<Node> stillTrashed =
        lockedNodes.stream().filter(node -> trashedIds.contains(node.getId())).toList();
    Map<String, Optional<Node>> destinations = restoreDestinations(stillTrashed);
    lockChildNames(restoreKeys(stillTrashed, destinations), locked);
    return destinations;
  }

  private Map<String, Optional<Node>> restoreDestinations(List<Node> nodes) {
    Map<String, Optional<Node>> destinations = new HashMap<>();
    nodes.forEach(node -> destinations.put(node.getId(), restoreDestination(node.getId())));
    return destinations;
  }

  private List<ImmutablePair<String, String>> restoreKeys(
      List<Node> nodes, Map<String, Optional<Node>> destinations) {
    return nodes.stream()
        .map(
            node ->
                ImmutablePair.of(
                    destinations.get(node.getId()).map(Node::getId).orElse(RootId.LOCAL_ROOT),
                    node.getOwnerId()))
        .toList();
  }

  private Optional<Node> restoreDestination(String nodeId) {
    return nodeRepository
        .getNode(nodeRepository.getTrashedNode(nodeId).get().getParentId())
        .filter(parent -> !parent.getAncestorsList().contains(RootId.TRASH_ROOT));
  }

  private List<Node> collectAllDescendants(List<Node> rootNodes) {
    List<Node> result = new ArrayList<>(rootNodes);
    for (Node node : rootNodes) {
      if (node.getNodeType().equals(com.zextras.carbonio.files.dal.dao.ebean.NodeType.FOLDER)) {
        List<String> childrenIds =
            nodeRepository.getChildrenIds(node.getId(), Optional.empty(), Optional.empty(), true);
        if (!childrenIds.isEmpty()) {
          List<Node> children =
              nodeRepository
                  .getNodes(childrenIds, Optional.empty())
                  .filter(Objects::nonNull)
                  .collect(Collectors.toList());
          result.addAll(collectAllDescendants(children));
        }
      }
    }
    return result;
  }

  private void cascadeDeleteNodeHelper(String nodeId) {
    List<String> childrenIds =
        nodeRepository.getChildrenIds(nodeId, Optional.empty(), Optional.empty(), true);
    if (!childrenIds.isEmpty()) {
      List<Node> children =
          nodeRepository.getNodes(childrenIds, Optional.empty()).collect(Collectors.toList());
      deleteNodesFromDb(children);
      children.stream()
          .filter(
              node ->
                  node.getNodeType()
                      .equals(com.zextras.carbonio.files.dal.dao.ebean.NodeType.FOLDER))
          .forEach(node -> cascadeDeleteNodeHelper(node.getId()));
    }
    shareRepository.deleteSharesBulk(nodeRepository.getTrashedNodeIdsByOldParent(nodeId));
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

  private record CopyOutcome(Optional<Node> node, Throwable failure) {}

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

  private CopyOutcome copyFile(
      Node sourceNode, Node destinationFolder, String requesterId, boolean dedupName) {
    String ownerId =
        (com.zextras.carbonio.files.dal.dao.ebean.NodeType.ROOT.equals(
                    destinationFolder.getNodeType())
                || requesterId.equals(destinationFolder.getOwnerId()))
            ? requesterId
            : destinationFolder.getOwnerId();

    Node createdNode =
        QuarkusTransaction.requiringNew()
            .call(
                () ->
                    nodeRepository.createNewNode(
                        UUID.randomUUID().toString(),
                        requesterId,
                        ownerId,
                        destinationFolder.getId(),
                        dedupName
                            ? allocateCopyName(sourceNode, destinationFolder)
                            : sourceNode.getFullName(),
                        sourceNode.getDescription().orElse(""),
                        sourceNode.getNodeType(),
                        com.zextras.carbonio.files.dal.dao.ebean.NodeType.ROOT.equals(
                                destinationFolder.getNodeType())
                            ? destinationFolder.getId()
                            : destinationFolder.getAncestorIds() + "," + destinationFolder.getId(),
                        sourceNode.getSize()));

    boolean blobCopied = false;
    try {
      FileVersion sourceCurrentVersion =
          fileVersionRepository
              .getFileVersion(sourceNode.getId(), sourceNode.getCurrentVersion())
              .get();

      var copyAttempt =
          Try.of(
              () ->
                  fileStore.copy(
                      FilesIdentifier.of(
                          sourceNode.getId(),
                          sourceNode.getCurrentVersion(),
                          sourceNode.getOwnerId()),
                      FilesIdentifier.of(createdNode.getId(), 1, requesterId),
                      false));
      if (copyAttempt.isFailure()) {
        logger.error(
            MessageFormat.format(
                "Unable to copy node {0}. {1}", sourceNode.getId(), copyAttempt.getCause()));
        nodeRepository.deleteNode(createdNode.getId());
        return new CopyOutcome(nodeRepository.getNode(createdNode.getId()), copyAttempt.getCause());
      }
      blobCopied = true;
      var response = copyAttempt.get();

      QuarkusTransaction.requiringNew()
          .run(
              () -> {
                Node lockedNode = nodeRepository.getNodeForUpdate(createdNode.getId()).get();
                fileVersionRepository.createNewFileVersion(
                    createdNode.getId(),
                    requesterId,
                    1,
                    sourceCurrentVersion.getMimeType(),
                    response.getSize(),
                    response.getDigest(),
                    false);
                lockedNode.setCurrentVersion(1);
                nodeRepository.updateNode(lockedNode);
              });
    } catch (RuntimeException e) {
      discardFailedCopy(createdNode.getId(), requesterId, blobCopied);
      throw e;
    }

    return new CopyOutcome(nodeRepository.getNode(createdNode.getId()), null);
  }

  private void discardFailedCopy(String nodeId, String blobOwnerId, boolean blobCopied) {
    try {
      nodeRepository.deleteNode(nodeId);
    } catch (RuntimeException e) {
      logger.warn("Unable to delete node {} of a failed copy: {}", nodeId, e.getMessage());
    }
    if (!blobCopied) {
      return;
    }
    try {
      List<BulkDeleteResponseItem> failedItems =
          fileStore.bulkDelete(
              IdentifierType.files,
              blobOwnerId,
              List.of(BulkDeleteRequestItem.filesItem(nodeId, 1)));
      if (failedItems == null || !failedItems.isEmpty()) {
        logger.warn("Unable to delete the blob of node {} of a failed copy", nodeId);
      }
    } catch (Exception e) {
      logger.warn(
          "Unable to delete the blob of node {} of a failed copy: {}", nodeId, e.getMessage());
    }
  }

  private String allocateCopyName(Node sourceNode, Node destinationFolder) {
    nodeRepository.lockChildNames(destinationFolder.getId(), sourceNode.getOwnerId());
    return searchAlternativeName(
        nodeRepository,
        sourceNode.getFullName(),
        destinationFolder.getId(),
        sourceNode.getOwnerId());
  }

  private Node copyFolder(
      Node sourceFolder, Node destinationFolder, String requesterId, boolean dedupName) {
    String ownerId =
        (com.zextras.carbonio.files.dal.dao.ebean.NodeType.ROOT.equals(
                    destinationFolder.getNodeType())
                || requesterId.equals(destinationFolder.getOwnerId()))
            ? requesterId
            : destinationFolder.getOwnerId();

    return QuarkusTransaction.requiringNew()
        .call(
            () ->
                nodeRepository.createNewNode(
                    UUID.randomUUID().toString(),
                    requesterId,
                    ownerId,
                    destinationFolder.getId(),
                    dedupName
                        ? allocateCopyName(sourceFolder, destinationFolder)
                        : sourceFolder.getName(),
                    sourceFolder.getDescription().orElse(""),
                    com.zextras.carbonio.files.dal.dao.ebean.NodeType.FOLDER,
                    com.zextras.carbonio.files.dal.dao.ebean.NodeType.ROOT.equals(
                            destinationFolder.getNodeType())
                        ? destinationFolder.getId()
                        : destinationFolder.getAncestorIds() + "," + destinationFolder.getId(),
                    0L));
  }

  private void copyFolderCascade(
      String sourceFolderId, Node destinationFolder, String requesterId) {
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

    filesToCopy.forEach(file -> copyFile(file, destinationFolder, requesterId, false));

    foldersToCopy.forEach(
        folder -> {
          Node copiedFolder = copyFolder(folder, destinationFolder, requesterId, false);
          copyFolderCascade(folder.getId(), copiedFolder, requesterId);
        });
  }
}
