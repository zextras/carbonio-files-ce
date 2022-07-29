// SPDX-FileCopyrightText: 2022 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.graphql.datafetchers;

import com.google.inject.Inject;
import com.zextras.carbonio.files.Files;
import com.zextras.carbonio.files.Files.API.Endpoints;
import com.zextras.carbonio.files.Files.GraphQL.Context;
import com.zextras.carbonio.files.Files.GraphQL.Node;
import com.zextras.carbonio.files.dal.dao.User;
import com.zextras.carbonio.files.dal.dao.ebean.ACL;
import com.zextras.carbonio.files.dal.dao.ebean.ACL.SharePermission;
import com.zextras.carbonio.files.dal.dao.ebean.InvitationLink;
import com.zextras.carbonio.files.dal.repositories.interfaces.InvitationLinkRepository;
import com.zextras.carbonio.files.dal.repositories.interfaces.NodeRepository;
import com.zextras.carbonio.files.graphql.errors.GraphQLResultErrors;
import com.zextras.carbonio.files.utilities.PermissionsChecker;
import graphql.execution.DataFetcherResult;
import graphql.execution.ResultPath;
import graphql.schema.DataFetcher;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import org.apache.commons.lang3.RandomStringUtils;

public class InvitationLinkDataFetcher {

  private final InvitationLinkRepository invitationLinkRepository;
  private final NodeRepository           nodeRepository;
  private final PermissionsChecker       permissionsChecker;

  @Inject
  public InvitationLinkDataFetcher(
    InvitationLinkRepository invitationLinkRepository,
    NodeRepository nodeRepository,
    PermissionsChecker permissionsChecker
  ) {
    this.invitationLinkRepository = invitationLinkRepository;
    this.nodeRepository = nodeRepository;
    this.permissionsChecker = permissionsChecker;
  }

  private DataFetcherResult<Map<String, Object>> convertInvitationLinkToDataFetcherResult(
    InvitationLink invitationLink,
    String requesterDomain
  ) {

    String invitationURL = MessageFormat.format(
      "{0}{1}{2}",
      requesterDomain,
      Endpoints.INVITATION_LINK_URL,
      invitationLink.getInvitationId()
    );

    Map<String, Object> linkMap = new HashMap<>();
    Map<String, String> localContext = new HashMap<>();

    linkMap.put("id", invitationLink.getId().toString());
    linkMap.put("url", invitationURL);
    linkMap.put("created_at", invitationLink.getCreatedAt().toEpochMilli());
    linkMap.put("permission", invitationLink.getPermissions());

    localContext.put("node", invitationLink.getNodeId());

    return DataFetcherResult
      .<Map<String, Object>>newResult()
      .data(linkMap)
      .localContext(localContext)
      .build();
  }

  public DataFetcher<CompletableFuture<DataFetcherResult<Map<String, Object>>>> createInvitationLink() {
    return environment -> CompletableFuture.supplyAsync(() -> {
      ResultPath path = environment.getExecutionStepInfo().getPath();
      User requester = environment.getGraphQlContext().get(Files.GraphQL.Context.REQUESTER);
      String nodeId = environment.getArgument("node_id");
      SharePermission permissions = environment.getArgument("permission");

      if (permissionsChecker.getPermissions(nodeId, requester.getUuid()).has(permissions)) {

        // If there is an existing invitation link having the same permission then it returns it,
        // otherwise it creates a new invitation link
        InvitationLink link = invitationLinkRepository
          .getLinksByNodeId(nodeId)
          .filter(invitationLink -> permissions.equals(invitationLink.getPermissions()))
          .findFirst()
          .orElseGet(() ->
            invitationLinkRepository.createLink(
              UUID.randomUUID(),
              nodeId,
              RandomStringUtils.randomAlphanumeric(8),
              permissions
            ));

        return convertInvitationLinkToDataFetcherResult(link, requester.getDomain());
      }

      return DataFetcherResult
        .<Map<String, Object>>newResult()
        .error(GraphQLResultErrors.nodeWriteError(nodeId, path))
        .build();
    });
  }

  public DataFetcher<CompletableFuture<List<DataFetcherResult<Map<String, Object>>>>> getInvitationLinksByNodeId() {
    return environment -> CompletableFuture.supplyAsync(() -> {
      ResultPath path = environment.getExecutionStepInfo().getPath();
      User requester = environment.getGraphQlContext().get(Context.REQUESTER);
      Optional<Map<String, String>> optLocalContext =
        Optional.ofNullable(environment.getLocalContext());

      String nodeId = (optLocalContext.isPresent())
        ? optLocalContext.get().get(Node.ID)
        : environment.getArgument("node_id");

      ACL permissions = permissionsChecker.getPermissions(nodeId, requester.getUuid());

      if (permissions.has(SharePermission.READ_AND_SHARE)
        || permissions.has(SharePermission.READ_WRITE_AND_SHARE)
      ) {
        return invitationLinkRepository
          .getLinksByNodeId(nodeId)
          .map(invitationLink ->
            convertInvitationLinkToDataFetcherResult(invitationLink, requester.getDomain())
          )
          .collect(Collectors.toList());
      }

      return Collections.singletonList(
        DataFetcherResult
          .<Map<String, Object>>newResult()
          .error(GraphQLResultErrors.nodeWriteError(nodeId, path))
          .build()
      );
    });
  }

  public DataFetcher<CompletableFuture<DataFetcherResult<List<String>>>> deleteInvitationLinks() {
    return environment -> CompletableFuture.supplyAsync(() -> {
      ResultPath path = environment.getExecutionStepInfo().getPath();
      User requester = environment.getGraphQlContext().get(Context.REQUESTER);
      List<String> invitationIds = environment.getArgument("invitation_link_ids");

      List<String> invitationIdForbidden = new ArrayList<>();
      invitationIds.forEach(invitationId -> {
        boolean isForbidden = invitationLinkRepository
          .getLinkById(UUID.fromString(invitationId))
          .filter(invitationLink -> {
            ACL requesterPermission = permissionsChecker
              .getPermissions(invitationLink.getNodeId(), requester.getUuid());

            return requesterPermission.has(SharePermission.READ_WRITE_AND_SHARE) ||
              requesterPermission.has(SharePermission.READ_AND_SHARE);
          })
          .isEmpty();

        if (isForbidden) {
          invitationIdForbidden.add(invitationId);
        }
      });

      List<UUID> invitationIdsToDelete = invitationIds
        .stream()
        .filter(invitationId -> !invitationIdForbidden.contains(invitationId))
        .map(UUID::fromString)
        .collect(Collectors.toList());

      invitationLinkRepository.deleteLinks(invitationIdsToDelete);

      return DataFetcherResult
        .<List<String>>newResult()
        .data(invitationIdsToDelete.stream().map(UUID::toString).collect(Collectors.toList()))
        .errors(
          invitationIdForbidden
            .stream()
            .map(invitationId -> GraphQLResultErrors.missingField(path))
            .collect(Collectors.toList())
        )
        .build();
    });
  }
}
