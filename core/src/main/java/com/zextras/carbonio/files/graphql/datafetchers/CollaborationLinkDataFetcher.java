// SPDX-FileCopyrightText: 2022 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.graphql.datafetchers;

import com.google.inject.Inject;
import com.zextras.carbonio.files.Files;
import com.zextras.carbonio.files.Files.API.Endpoints;
import com.zextras.carbonio.files.Files.GraphQL;
import com.zextras.carbonio.files.Files.GraphQL.Context;
import com.zextras.carbonio.files.Files.GraphQL.InputParameters.CreateCollaborationLink;
import com.zextras.carbonio.files.Files.GraphQL.InputParameters.DeleteCollaborationLinks;
import com.zextras.carbonio.files.Files.GraphQL.InputParameters.GetCollaborationLink;
import com.zextras.carbonio.files.Files.GraphQL.Node;
import com.zextras.carbonio.files.dal.dao.User;
import com.zextras.carbonio.files.dal.dao.ebean.ACL;
import com.zextras.carbonio.files.dal.dao.ebean.ACL.SharePermission;
import com.zextras.carbonio.files.dal.dao.ebean.CollaborationLink;
import com.zextras.carbonio.files.dal.repositories.interfaces.CollaborationLinkRepository;
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

public class CollaborationLinkDataFetcher {

  private final CollaborationLinkRepository collaborationLinkRepository;
  private final PermissionsChecker          permissionsChecker;

  @Inject
  public CollaborationLinkDataFetcher(
    CollaborationLinkRepository collaborationLinkRepository,
    PermissionsChecker permissionsChecker
  ) {
    this.collaborationLinkRepository = collaborationLinkRepository;
    this.permissionsChecker = permissionsChecker;
  }

  private DataFetcherResult<Map<String, Object>> convertCollaborationLinkToDataFetcherResult(
    CollaborationLink collaborationLink,
    String requesterDomain
  ) {

    String invitationURL = MessageFormat.format(
      "{0}{1}{2}",
      requesterDomain,
      Endpoints.COLLABORATION_LINK_URL,
      collaborationLink.getInvitationId()
    );

    Map<String, Object> linkMap = new HashMap<>();
    Map<String, String> localContext = new HashMap<>();

    linkMap.put(GraphQL.CollaborationLink.ID, collaborationLink.getId().toString());
    linkMap.put(GraphQL.CollaborationLink.FULL_URL, invitationURL);
    linkMap.put(
      GraphQL.CollaborationLink.CREATED_AT,
      collaborationLink.getCreatedAt().toEpochMilli()
    );
    linkMap.put(GraphQL.CollaborationLink.PERMISSION, collaborationLink.getPermissions());

    localContext.put(GraphQL.CollaborationLink.NODE, collaborationLink.getNodeId());

    return DataFetcherResult
      .<Map<String, Object>>newResult()
      .data(linkMap)
      .localContext(localContext)
      .build();
  }

  public DataFetcher<CompletableFuture<DataFetcherResult<Map<String, Object>>>> createCollaborationLink() {
    return environment -> CompletableFuture.supplyAsync(() -> {
      ResultPath path = environment.getExecutionStepInfo().getPath();
      User requester = environment.getGraphQlContext().get(Files.GraphQL.Context.REQUESTER);
      String nodeId = environment.getArgument(CreateCollaborationLink.NODE_ID);
      SharePermission permissions = environment.getArgument(CreateCollaborationLink.PERMISSION);

      if (permissionsChecker.getPermissions(nodeId, requester.getId()).has(permissions)) {

        // If there is an existing collaboration link having the same permission then the system
        // returns it, otherwise it creates a new collaboration link
        CollaborationLink link = collaborationLinkRepository
          .getLinksByNodeId(nodeId)
          .filter(collaborationLink -> permissions.equals(collaborationLink.getPermissions()))
          .findFirst()
          .orElseGet(() ->
            collaborationLinkRepository.createLink(
              UUID.randomUUID(),
              nodeId,
              RandomStringUtils.randomAlphanumeric(8),
              permissions
            ));

        return convertCollaborationLinkToDataFetcherResult(link, requester.getDomain());
      }

      return DataFetcherResult
        .<Map<String, Object>>newResult()
        .error(GraphQLResultErrors.nodeWriteError(nodeId, path))
        .build();
    });
  }

  public DataFetcher<CompletableFuture<List<DataFetcherResult<Map<String, Object>>>>> getCollaborationLinksByNodeId() {
    return environment -> CompletableFuture.supplyAsync(() -> {
      ResultPath path = environment.getExecutionStepInfo().getPath();
      User requester = environment.getGraphQlContext().get(Context.REQUESTER);
      Optional<Map<String, String>> optLocalContext =
        Optional.ofNullable(environment.getLocalContext());

      String nodeId = (optLocalContext.isPresent())
        ? optLocalContext.get().get(Node.ID)
        : environment.getArgument(GetCollaborationLink.NODE_ID);

      ACL permissions = permissionsChecker.getPermissions(nodeId, requester.getId());

      if (permissions.has(SharePermission.READ_AND_SHARE)
        || permissions.has(SharePermission.READ_WRITE_AND_SHARE)
      ) {
        // Before returning the list, the system filters the collaborationLinks the user has no
        // permission to see
        return collaborationLinkRepository
          .getLinksByNodeId(nodeId)
          .filter(collaborationLink -> permissions.has(collaborationLink.getPermissions()))
          .map(collaborationLink ->
            convertCollaborationLinkToDataFetcherResult(collaborationLink, requester.getDomain())
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

  public DataFetcher<CompletableFuture<DataFetcherResult<List<String>>>> deleteCollaborationLinks() {
    return environment -> CompletableFuture.supplyAsync(() -> {
      ResultPath path = environment.getExecutionStepInfo().getPath();
      User requester = environment.getGraphQlContext().get(Context.REQUESTER);
      List<String> collaborationIds =
        environment.getArgument(DeleteCollaborationLinks.COLLABORATION_LINK_IDS);

      List<String> collaborationIdsForbidden = new ArrayList<>();
      collaborationIds.forEach(collaborationId -> {
        boolean isForbidden = collaborationLinkRepository
          .getLinkById(UUID.fromString(collaborationId))
          .filter(collaborationLink -> {
            ACL requesterPermission = permissionsChecker
              .getPermissions(collaborationLink.getNodeId(), requester.getId());

            return requesterPermission.has(SharePermission.READ_WRITE_AND_SHARE) ||
              requesterPermission.has(SharePermission.READ_AND_SHARE);
          })
          .isEmpty();

        if (isForbidden) {
          collaborationIdsForbidden.add(collaborationId);
        }
      });

      List<UUID> collaborationIdsToDelete = collaborationIds
        .stream()
        .filter(collaborationId -> !collaborationIdsForbidden.contains(collaborationId))
        .map(UUID::fromString)
        .collect(Collectors.toList());

      collaborationLinkRepository.deleteLinks(collaborationIdsToDelete);

      return DataFetcherResult
        .<List<String>>newResult()
        .data(collaborationIdsToDelete.stream().map(UUID::toString).collect(Collectors.toList()))
        .errors(
          collaborationIdsForbidden
            .stream()
            .map(collaborationId -> GraphQLResultErrors.missingField(path))
            .collect(Collectors.toList())
        )
        .build();
    });
  }
}
