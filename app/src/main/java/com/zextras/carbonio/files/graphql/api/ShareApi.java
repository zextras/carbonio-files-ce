// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.graphql.api;

import com.zextras.carbonio.files.config.FilesConfig;
import com.zextras.carbonio.files.dal.dao.UserMyself;
import com.zextras.carbonio.files.dal.dao.ebean.ACL;
import com.zextras.carbonio.files.dal.dao.ebean.Node;
import com.zextras.carbonio.files.dal.dao.ebean.NodeType;
import com.zextras.carbonio.files.dal.dao.ebean.Share;
import com.zextras.carbonio.files.dal.repositories.interfaces.NodeRepository;
import com.zextras.carbonio.files.dal.repositories.interfaces.NotificationRepository;
import com.zextras.carbonio.files.dal.repositories.interfaces.ShareRepository;
import com.zextras.carbonio.files.graphql.auth.AuthenticatedUser;
import com.zextras.carbonio.files.graphql.errors.FilesGraphQLErrors;
import com.zextras.carbonio.files.graphql.errors.FilesGraphQLException;
import com.zextras.carbonio.files.graphql.model.NodeModel;
import com.zextras.carbonio.files.graphql.model.ShareModel;
import com.zextras.carbonio.files.graphql.model.SharePermission;
import com.zextras.carbonio.files.graphql.model.ShareSort;
import com.zextras.carbonio.files.graphql.model.support.NodeModelFactory;
import com.zextras.carbonio.files.graphql.support.ShareCascadeHelper;
import com.zextras.carbonio.files.graphql.validation.GraphQLInputValidator;
import com.zextras.carbonio.files.utilities.PermissionsChecker;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.security.Authenticated;
import io.smallrye.graphql.api.Nullable;
import jakarta.inject.Inject;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.eclipse.microprofile.graphql.GraphQLApi;
import org.eclipse.microprofile.graphql.Id;
import org.eclipse.microprofile.graphql.Mutation;
import org.eclipse.microprofile.graphql.Name;
import org.eclipse.microprofile.graphql.NonNull;
import org.eclipse.microprofile.graphql.Query;
import org.eclipse.microprofile.graphql.Source;

@GraphQLApi
@Authenticated
public class ShareApi {

  private static final String UNIQUE_VIOLATION_SQL_STATE = "23505";

  @Inject NodeRepository nodeRepository;
  @Inject ShareRepository shareRepository;
  @Inject PermissionsChecker permissionsChecker;
  @Inject ShareCascadeHelper shareCascade;
  @Inject NotificationRepository notificationRepository;
  @Inject FilesConfig filesConfig;
  @Inject GraphQLInputValidator validator;
  @Inject @AuthenticatedUser UserMyself requester;

  static ShareModel toModel(Share share) {
    ACL.SharePermission permission = share.getPermissions().getSharePermission();
    if (permission == ACL.SharePermission.NONE) {
      return null;
    }
    SharePermission perm = SharePermission.valueOf(permission.name());
    return new ShareModel(
        share.getCreatedAt(),
        perm,
        share.getExpiredAt().orElse(null),
        share.getNodeId(),
        share.getTargetUserId());
  }

  // ─── Single-item @Source resolvers — Share.node ──────────────────────────────

  @Name("node")
  @NonNull
  public NodeModel node(@Source ShareModel share) throws FilesGraphQLException {
    String me = requester.getId().getUserId();
    NodeModel model =
        nodeRepository
            .getNode(share.getNodeId())
            .map(n -> NodeModelFactory.from(n, null, me))
            .orElseThrow(() -> FilesGraphQLErrors.nodeNotFound(share.getNodeId()));
    NodeModelFactory.requireVersion(model, model);
    return model;
  }

  // ─── Batch @Source resolver — Node.shares ────────────────────────────────────

  @Name("shares")
  @NonNull
  public List<List<ShareModel>> shares(
      @Source List<NodeModel> nodes,
      @Name("limit") @NonNull int limit,
      @Name("cursor") String cursor,
      @Name("sorts") @Nullable List<@NonNull ShareSort> sorts)
      throws FilesGraphQLException {
    validator.checkPositiveLimit(limit).validate();
    List<String> nodeIds = nodes.stream().map(NodeModel::getId).toList();
    List<Share> all = shareRepository.getShares(nodeIds);
    Map<String, List<Share>> byNode = all.stream().collect(Collectors.groupingBy(Share::getNodeId));
    return nodeIds.stream()
        .map(
            id -> {
              List<Share> sharesForNode = byNode.getOrDefault(id, Collections.emptyList());
              int skip = cursorIndex(sharesForNode, cursor);
              return sharesForNode.stream().skip(skip).limit(limit).map(ShareApi::toModel).toList();
            })
        .toList();
  }

  @Name("share")
  public ShareModel share(
      @Source NodeModel node, @Name("share_target_id") @Id @NonNull String shareTargetId) {
    String me = requester.getId().getUserId();
    if (!permissionsChecker.getPermissions(node.getId(), me).has(ACL.SharePermission.READ_ONLY)) {
      return null;
    }
    return shareRepository
        .getShare(node.getId(), shareTargetId)
        .map(ShareApi::toModel)
        .orElse(null);
  }

  private static int cursorIndex(List<Share> shares, String cursor) {
    if (cursor == null) return 0;
    int idx =
        shares.stream().map(Share::getTargetUserId).collect(Collectors.toList()).indexOf(cursor);
    return idx < 0 ? 0 : idx + 1;
  }

  @Query("getShare")
  public ShareModel getShare(
      @Name("node_id") @Id @NonNull String nodeId,
      @Name("share_target_id") @Id @NonNull String shareTargetId)
      throws FilesGraphQLException {
    validator.checkNodeId(nodeId).checkUserId(shareTargetId).validate();
    String me = requester.getId().getUserId();
    if (!permissionsChecker.getPermissions(nodeId, me).has(ACL.SharePermission.READ_ONLY)) {
      throw FilesGraphQLErrors.shareNotFound(nodeId, shareTargetId);
    }
    return shareRepository
        .getShare(nodeId, shareTargetId)
        .map(ShareApi::toModel)
        .orElseThrow(() -> FilesGraphQLErrors.shareNotFound(nodeId, shareTargetId));
  }

  @Mutation("createShare")
  public @NonNull ShareModel createShare(
      @Name("node_id") @Id @NonNull String nodeId,
      @Name("share_target_id") @Id @NonNull String shareTargetId,
      @Name("permission") @NonNull SharePermission permission,
      @Name("expires_at") Long expiresAt,
      @Name("custom_message") String customMessage)
      throws FilesGraphQLException {
    validator.checkNodeId(nodeId).checkUserId(shareTargetId).validate();
    String me = requester.getId().getUserId();
    if (!permissionsChecker.getPermissions(nodeId, me).has(ACL.SharePermission.READ_AND_SHARE)) {
      throw FilesGraphQLErrors.shareCreationError(nodeId, shareTargetId);
    }

    Node sharedNode =
        nodeRepository
            .getNode(nodeId)
            .orElseThrow(() -> FilesGraphQLErrors.shareCreationError(nodeId, shareTargetId));
    if (shareTargetId.equals(sharedNode.getOwnerId())) {
      throw FilesGraphQLErrors.shareCreationError(nodeId, shareTargetId);
    }

    Optional<Long> optExpiresAt = Optional.ofNullable(expiresAt);
    ACL acl = ACL.decode(mapToAclPermission(permission));

    Optional<Share> optCreatedShare;
    try {
      optCreatedShare =
          QuarkusTransaction.requiringNew()
              .call(
                  () -> {
                    Optional<Share> upserted =
                        shareRepository.upsertShare(
                            nodeId, shareTargetId, acl, true, false, optExpiresAt);
                    upserted.ifPresent(
                        s ->
                            shareCascade.cascadeUpsertShare(
                                nodeId, shareTargetId, acl, optExpiresAt));
                    shareRepository.flush();
                    return upserted;
                  });
    } catch (RuntimeException e) {
      if (isUniqueViolation(e)) {
        throw FilesGraphQLErrors.shareCreationError(nodeId, shareTargetId);
      }
      throw e;
    }

    return optCreatedShare
        .map(
            share -> {
              if (filesConfig.areNotificationsEnabled()) {
                notificationRepository.createNewShareNotification(
                    sharedNode, requester, List.of(shareTargetId));
              }
              return toModel(share);
            })
        .orElseThrow(() -> FilesGraphQLErrors.shareCreationError(nodeId, shareTargetId));
  }

  private static boolean isUniqueViolation(Throwable e) {
    return ExceptionUtils.getThrowableList(e).stream()
        .anyMatch(
            t ->
                t instanceof SQLException sql
                    && UNIQUE_VIOLATION_SQL_STATE.equals(sql.getSQLState()));
  }

  @Mutation("updateShares")
  public @NonNull List<ShareModel> updateShares(
      @Name("node_id") @Id @NonNull String nodeId,
      @Name("share_target_ids") @NonNull List<@NonNull String> shareTargetIds,
      @Name("permission") SharePermission permission,
      @Name("expires_at") Long expiresAt)
      throws FilesGraphQLException {
    validator.checkNodeId(nodeId).checkUserIds(shareTargetIds).validate();
    String me = requester.getId().getUserId();
    boolean hasPermission =
        permissionsChecker.getPermissions(nodeId, me).has(ACL.SharePermission.READ_AND_SHARE);

    List<ShareModel> updated = new ArrayList<>();
    List<String> failed = new ArrayList<>();

    Optional<ACL.SharePermission> optNewPermission =
        Optional.ofNullable(permission).map(ShareApi::mapToAclPermission);
    Optional<Long> optNewExpiresAt = Optional.ofNullable(expiresAt);

    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              for (String targetId : shareTargetIds) {
                if (!hasPermission) {
                  failed.add(targetId);
                  continue;
                }
                Optional<Share> optShare = shareRepository.getShare(nodeId, targetId);
                if (optShare.isPresent()) {
                  Share share = optShare.get();
                  optNewPermission.ifPresent(p -> share.setPermissions(ACL.decode(p)));
                  optNewExpiresAt.ifPresent(share::setExpiredAt);
                  Share saved = shareRepository.updateShare(share);
                  optNewPermission.ifPresent(
                      p ->
                          shareCascade.cascadeUpsertShare(
                              nodeId, targetId, ACL.decode(p), optNewExpiresAt));
                  updated.add(toModel(saved));
                } else {
                  failed.add(targetId);
                }
              }
            });

    if (!failed.isEmpty()) {
      throw FilesGraphQLErrors.shareNotFound(nodeId, failed.get(0)).withPartialResults(updated);
    }
    return updated;
  }

  @Mutation("deleteShares")
  public @NonNull @Id List<String> deleteShares(
      @Name("node_id") @Id @NonNull String nodeId,
      @Name("share_target_ids") @NonNull List<@NonNull String> shareTargetIds)
      throws FilesGraphQLException {
    validator.checkNodeId(nodeId).checkUserIds(shareTargetIds).validate();
    String me = requester.getId().getUserId();

    List<String> deleted = new ArrayList<>();
    List<String> failed = new ArrayList<>();

    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              for (String targetId : shareTargetIds) {
                boolean hasPermission =
                    permissionsChecker
                            .getPermissions(nodeId, me)
                            .has(ACL.SharePermission.READ_AND_SHARE)
                        || me.equals(targetId);
                if (!hasPermission) {
                  failed.add(targetId);
                  continue;
                }
                Optional<Share> optShare = shareRepository.getShare(nodeId, targetId);
                if (optShare.isPresent()) {
                  Share share = optShare.get();
                  shareRepository.deleteShare(share.getNodeId(), share.getTargetUserId());
                  if (nodeRepository.getNode(nodeId).get().getNodeType() == NodeType.FOLDER) {
                    shareCascade.cascadeDeleteShare(nodeId, targetId);
                  }
                  deleted.add(targetId);
                } else {
                  failed.add(targetId);
                }
              }
            });

    if (!failed.isEmpty()) {
      throw FilesGraphQLErrors.shareNotFound(nodeId, failed.get(0)).withPartialResults(deleted);
    }
    return deleted;
  }

  private static ACL.SharePermission mapToAclPermission(SharePermission perm) {
    return ACL.SharePermission.valueOf(perm.name());
  }
}
