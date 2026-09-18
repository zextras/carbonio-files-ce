// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.graphql.api;

import com.zextras.carbonio.files.Constants.API.Endpoints;
import com.zextras.carbonio.files.dal.dao.UserMyself;
import com.zextras.carbonio.files.dal.dao.ebean.ACL;
import com.zextras.carbonio.files.dal.dao.ebean.CollaborationLink;
import com.zextras.carbonio.files.dal.repositories.interfaces.CollaborationLinkRepository;
import com.zextras.carbonio.files.dal.repositories.interfaces.NodeRepository;
import com.zextras.carbonio.files.graphql.auth.AuthenticatedUser;
import com.zextras.carbonio.files.graphql.errors.ErrorCodes;
import com.zextras.carbonio.files.graphql.errors.FilesGraphQLException;
import com.zextras.carbonio.files.graphql.model.CollaborationLinkModel;
import com.zextras.carbonio.files.graphql.model.NodeModel;
import com.zextras.carbonio.files.graphql.model.SharePermission;
import com.zextras.carbonio.files.graphql.model.support.NodeModelFactory;
import com.zextras.carbonio.files.graphql.validation.GraphQLInputValidator;
import com.zextras.carbonio.files.utilities.PermissionsChecker;
import io.quarkus.security.Authenticated;
import jakarta.inject.Inject;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.apache.commons.lang3.RandomStringUtils;
import org.eclipse.microprofile.graphql.GraphQLApi;
import org.eclipse.microprofile.graphql.Id;
import org.eclipse.microprofile.graphql.Mutation;
import org.eclipse.microprofile.graphql.Name;
import org.eclipse.microprofile.graphql.NonNull;
import org.eclipse.microprofile.graphql.Query;
import org.eclipse.microprofile.graphql.Source;

@GraphQLApi
@Authenticated
public class CollaborationLinkApi {

  @Inject CollaborationLinkRepository collaborationLinkRepository;
  @Inject NodeRepository nodeRepository;
  @Inject PermissionsChecker permissionsChecker;
  @Inject GraphQLInputValidator validator;
  @Inject @AuthenticatedUser UserMyself requester;

  private static CollaborationLinkModel toModel(CollaborationLink link, String domain) {
    String url =
        MessageFormat.format(
            "{0}{1}{2}", domain, Endpoints.COLLABORATION_LINK_URL, link.getInvitationId());
    SharePermission perm = SharePermission.valueOf(link.getPermissions().name());
    return new CollaborationLinkModel(
        link.getId().toString(), url, link.getCreatedAt().toEpochMilli(), perm, link.getNodeId());
  }

  // ─── Single-item @Source resolvers ────────────────────────────────────────────

  @Name("collaboration_links")
  @NonNull
  public List<CollaborationLinkModel> collaborationLinks(@Source NodeModel node) {
    String me = requester.getId().getUserId();
    ACL acl = permissionsChecker.getPermissions(node.getId(), me);
    if (!acl.has(ACL.SharePermission.READ_AND_SHARE)
        && !acl.has(ACL.SharePermission.READ_WRITE_AND_SHARE)) {
      return List.of();
    }
    String domain = requester.getDomain();
    return collaborationLinkRepository
        .getLinksByNodeId(node.getId())
        .filter(link -> acl.has(link.getPermissions()))
        .map(link -> toModel(link, domain))
        .collect(Collectors.toList());
  }

  @Name("node")
  @NonNull
  public NodeModel node(@Source CollaborationLinkModel cl) throws FilesGraphQLException {
    String me = requester.getId().getUserId();
    return nodeRepository
        .getNode(cl.getNodeId())
        .map(n -> NodeModelFactory.from(n, null, me))
        .orElseThrow(
            () -> FilesGraphQLException.of(ErrorCodes.NODE_NOT_FOUND, "node_id", cl.getNodeId()));
  }

  @Query("getCollaborationLinks")
  public @NonNull List<CollaborationLinkModel> getCollaborationLinks(
      @Name("node_id") @Id @NonNull String nodeId) throws FilesGraphQLException {
    validator.checkNodeId(nodeId).validate();
    String me = requester.getId().getUserId();
    ACL acl = permissionsChecker.getPermissions(nodeId, me);
    if (!acl.has(ACL.SharePermission.READ_AND_SHARE)
        && !acl.has(ACL.SharePermission.READ_WRITE_AND_SHARE)) {
      return List.of();
    }
    String domain = requester.getDomain();
    return collaborationLinkRepository
        .getLinksByNodeId(nodeId)
        .filter(link -> acl.has(link.getPermissions()))
        .map(link -> toModel(link, domain))
        .collect(Collectors.toList());
  }

  @Mutation("createCollaborationLink")
  public @NonNull CollaborationLinkModel createCollaborationLink(
      @Name("node_id") @Id @NonNull String nodeId,
      @Name("permission") @NonNull SharePermission permission)
      throws FilesGraphQLException {
    validator.checkNodeId(nodeId).validate();
    String me = requester.getId().getUserId();
    ACL.SharePermission aclPerm = ACL.SharePermission.valueOf(permission.name());
    if (!permissionsChecker.getPermissions(nodeId, me).has(aclPerm)) {
      throw FilesGraphQLException.of(ErrorCodes.NODE_WRITE_ERROR, "node_id", nodeId);
    }
    CollaborationLink link =
        collaborationLinkRepository
            .getLinksByNodeId(nodeId)
            .filter(existing -> aclPerm.equals(existing.getPermissions()))
            .findFirst()
            .orElseGet(
                () ->
                    collaborationLinkRepository.createLink(
                        UUID.randomUUID(),
                        nodeId,
                        RandomStringUtils.randomAlphanumeric(8),
                        aclPerm));
    return toModel(link, requester.getDomain());
  }

  @Mutation("deleteCollaborationLinks")
  public @NonNull @Id List<String> deleteCollaborationLinks(
      @Name("collaboration_link_ids") @NonNull @Id List<@NonNull String> collaborationLinkIds)
      throws FilesGraphQLException {
    validator.checkLinkIds(collaborationLinkIds).validate();
    String me = requester.getId().getUserId();
    List<UUID> toDelete = new ArrayList<>();
    List<String> failed = new ArrayList<>();
    for (String id : collaborationLinkIds) {
      Optional<CollaborationLink> optLink =
          collaborationLinkRepository.getLinkById(UUID.fromString(id));
      boolean authorized =
          optLink
              .filter(
                  link -> {
                    ACL acl = permissionsChecker.getPermissions(link.getNodeId(), me);
                    return acl.has(ACL.SharePermission.READ_WRITE_AND_SHARE)
                        || acl.has(ACL.SharePermission.READ_AND_SHARE);
                  })
              .isPresent();
      if (authorized) {
        toDelete.add(UUID.fromString(id));
      } else {
        failed.add(id);
      }
    }
    collaborationLinkRepository.deleteLinks(toDelete);
    List<String> deleted = toDelete.stream().map(UUID::toString).collect(Collectors.toList());
    if (!failed.isEmpty()) {
      throw new FilesGraphQLException(
          ErrorCodes.MISSING_FIELD,
          ErrorCodes.MISSING_FIELD.name(),
          deleted,
          Map.of("deleteCollaborationLinks", deleted));
    }
    return deleted;
  }
}
