// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.graphql.api;

import com.zextras.carbonio.files.config.FilesConfig;
import com.zextras.carbonio.files.dal.dao.UserMyself;
import com.zextras.carbonio.files.dal.dao.ebean.ACL.SharePermission;
import com.zextras.carbonio.files.dal.dao.ebean.FileVersion;
import com.zextras.carbonio.files.dal.dao.ebean.Node;
import com.zextras.carbonio.files.dal.dao.ebean.Share;
import com.zextras.carbonio.files.dal.repositories.impl.ebean.utilities.FileVersionSort;
import com.zextras.carbonio.files.dal.repositories.interfaces.FileVersionRepository;
import com.zextras.carbonio.files.dal.repositories.interfaces.NodeRepository;
import com.zextras.carbonio.files.dal.repositories.interfaces.ShareRepository;
import com.zextras.carbonio.files.graphql.auth.AuthenticatedUser;
import com.zextras.carbonio.files.graphql.errors.ErrorCodes;
import com.zextras.carbonio.files.graphql.errors.FilesGraphQLException;
import com.zextras.carbonio.files.graphql.model.FileModel;
import com.zextras.carbonio.files.graphql.model.NodeModel;
import com.zextras.carbonio.files.graphql.model.NodePageModel;
import com.zextras.carbonio.files.graphql.model.NodeSort;
import com.zextras.carbonio.files.graphql.model.NodeType;
import com.zextras.carbonio.files.graphql.model.RootModel;
import com.zextras.carbonio.files.graphql.model.support.NodeModelFactory;
import com.zextras.carbonio.files.graphql.validation.GraphQLInputValidator;
import com.zextras.carbonio.files.utilities.PermissionsChecker;
import io.quarkus.security.Authenticated;
import io.smallrye.graphql.api.Nullable;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import org.eclipse.microprofile.graphql.GraphQLApi;
import org.eclipse.microprofile.graphql.Id;
import org.eclipse.microprofile.graphql.Name;
import org.eclipse.microprofile.graphql.NonNull;
import org.eclipse.microprofile.graphql.Query;

@GraphQLApi
@Authenticated
public class NodeApi {

  @Inject NodeRepository nodeRepository;
  @Inject FileVersionRepository fileVersionRepository;
  @Inject PermissionsChecker permissionsChecker;
  @Inject ShareRepository shareRepository;
  @Inject FilesConfig filesConfig;
  @Inject GraphQLInputValidator validator;
  @Inject @AuthenticatedUser UserMyself requester;

  @Query("getNode")
  public NodeModel getNode(
      @Name("node_id") @Id @NonNull String nodeId, @Name("version") Integer version)
      throws FilesGraphQLException {
    validator.checkNodeId(nodeId).validate();
    String me = requester.getId().getUserId();
    if (!permissionsChecker.getPermissions(nodeId, me).has(SharePermission.READ_ONLY)) {
      throw FilesGraphQLException.of(ErrorCodes.NODE_NOT_FOUND, "node_id", nodeId);
    }
    Node node =
        nodeRepository
            .getNode(nodeId)
            .orElseThrow(
                () -> FilesGraphQLException.of(ErrorCodes.NODE_NOT_FOUND, "node_id", nodeId));
    return NodeModelFactory.from(node, version, me);
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
      @Name("sort") NodeSort sort) {
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

    var findResult =
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

    List<NodeModel> nodes =
        findResult.getLeft().stream()
            .map(n -> NodeModelFactory.from(n, null, me))
            .collect(Collectors.toList());

    return new NodePageModel(nodes, findResult.getRight());
  }

  @Query("getVersions")
  public @NonNull List<FileModel> getVersions(
      @Name("node_id") @Id @NonNull String nodeId,
      @Name("versions") @Nullable List<@NonNull Integer> versions)
      throws FilesGraphQLException {
    String me = requester.getId().getUserId();
    if (!permissionsChecker.getPermissions(nodeId, me).has(SharePermission.READ_ONLY)) {
      throw FilesGraphQLException.of(ErrorCodes.NODE_NOT_FOUND, "node_id", nodeId);
    }

    List<Integer> versionNumbers;
    if (versions == null || versions.isEmpty()) {
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
        nodeRepository
            .getNode(nodeId)
            .orElseThrow(
                () -> FilesGraphQLException.of(ErrorCodes.NODE_NOT_FOUND, "node_id", nodeId));

    return versionNumbers.stream()
        .map(ver -> (FileModel) NodeModelFactory.from(node, ver, me))
        .collect(Collectors.toList());
  }

  @Query("getPath")
  public @NonNull List<NodeModel> getPath(@Name("node_id") @Id @NonNull String nodeId)
      throws FilesGraphQLException {
    String me = requester.getId().getUserId();
    if (!permissionsChecker.getPermissions(nodeId, me).has(SharePermission.READ_ONLY)) {
      throw FilesGraphQLException.of(ErrorCodes.NODE_NOT_FOUND, "node_id", nodeId);
    }

    Node node =
        nodeRepository
            .getNode(nodeId)
            .orElseThrow(
                () -> FilesGraphQLException.of(ErrorCodes.NODE_NOT_FOUND, "node_id", nodeId));

    List<String> pathNodeIds = new ArrayList<>(node.getAncestorsList());
    pathNodeIds.add(nodeId);

    List<Node> treeNodes =
        nodeRepository
            .getNodes(pathNodeIds, Optional.empty())
            .sorted(Comparator.comparing(n -> pathNodeIds.indexOf(n.getId())))
            .collect(Collectors.toList());

    if (node.getNodeType().equals(com.zextras.carbonio.files.dal.dao.ebean.NodeType.ROOT)
        || node.getOwnerId().equals(me)) {
      return treeNodes.stream()
          .map(n -> NodeModelFactory.from(n, null, me))
          .collect(Collectors.toList());
    }

    List<Share> shares =
        shareRepository.getShares(
            treeNodes.stream().map(Node::getId).collect(Collectors.toList()), me);

    List<Node> sharedNodes =
        treeNodes.stream()
            .filter(
                treeNode -> shares.stream().anyMatch(s -> s.getNodeId().equals(treeNode.getId())))
            .collect(Collectors.toList());

    return treeNodes.subList(treeNodes.indexOf(sharedNodes.get(0)), treeNodes.size()).stream()
        .map(n -> NodeModelFactory.from(n, null, me))
        .collect(Collectors.toList());
  }

  @Query("getRootsList")
  public @NonNull List<RootModel> getRootsList() {
    return nodeRepository.getRootsList().stream()
        .map(root -> new RootModel(root.getId(), root.getName()))
        .collect(Collectors.toList());
  }
}
