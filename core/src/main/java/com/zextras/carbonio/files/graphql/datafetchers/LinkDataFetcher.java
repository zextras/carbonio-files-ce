// SPDX-FileCopyrightText: 2022 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.graphql.datafetchers;

import com.google.inject.Inject;
import com.zextras.carbonio.files.Files;
import com.zextras.carbonio.files.Files.API.Endpoints;
import com.zextras.carbonio.files.Files.GraphQL;
import com.zextras.carbonio.files.Files.GraphQL.InputParameters;
import com.zextras.carbonio.files.Files.GraphQL.Node;
import com.zextras.carbonio.files.dal.dao.User;
import com.zextras.carbonio.files.dal.dao.ebean.ACL.SharePermission;
import com.zextras.carbonio.files.dal.dao.ebean.Link;
import com.zextras.carbonio.files.dal.dao.ebean.NodeType;
import com.zextras.carbonio.files.dal.repositories.impl.ebean.utilities.LinkSort;
import com.zextras.carbonio.files.dal.repositories.interfaces.LinkRepository;
import com.zextras.carbonio.files.dal.repositories.interfaces.NodeRepository;
import com.zextras.carbonio.files.graphql.GraphQLProvider;
import com.zextras.carbonio.files.graphql.errors.GraphQLResultErrors;
import com.zextras.carbonio.files.utilities.PermissionsChecker;
import graphql.execution.DataFetcherResult;
import graphql.execution.ResultPath;
import graphql.schema.DataFetcher;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import org.apache.commons.lang3.RandomStringUtils;

/**
 * <p>Contains all the implementations of {@link DataFetcher}s for all the queries and mutations
 * defined in the GraphQL schema that are related to the {@link Files.GraphQL.Link} type.</p>
 * <p>Each {@link DataFetcher} implementation is asynchronous and returns an {@link HashMap}
 * containing the data fetched from the database. Each key of the resulting map must match the name
 * of the related Link's attribute defined in the GraphQL schema.</p>
 * <p>These {@link DataFetcher}s will be used in the {@link GraphQLProvider} where they are bound
 * with the related queries, mutations and composed attributes.</p>
 * <p><strong>GraphQL behaviour:</strong> When a {@link DataFetcher} returns an empty {@link Map}
 * the GraphQL library has two distinct behaviours:
 * <ul>
 *   <li>
 *     if the related attribute was defined not <code>null</code> in the schema, it returns an error because it cannot
 *     find the mandatory attributes related to the Link inside the {@link Map}.
 *   </li>
 *   <li>
 *     it associates <code>null</code> to the attribute specified, if it was defined that can be <code>null</code>.
 *   </li>
 * </ul>
 */
public class LinkDataFetcher {

  private final LinkRepository     linkRepository;
  private final NodeRepository     nodeRepository;
  private final PermissionsChecker permissionsChecker;

  @Inject
  public LinkDataFetcher(
    LinkRepository linkRepository,
    NodeRepository nodeRepository,
    PermissionsChecker permissionsChecker
  ) {
    this.linkRepository = linkRepository;
    this.nodeRepository = nodeRepository;
    this.permissionsChecker = permissionsChecker;
  }

  private DataFetcherResult<Map<String, Object>> convertLinkToGraphQLMap(
    Link link,
    String requesterDomain
  ) {
    Map<String, Object> result = new HashMap<>();
    Map<String, String> linkContext = new HashMap<>();

    result.put(Files.GraphQL.Link.ID, link.getLinkId());
    result.put(
      Files.GraphQL.Link.URL,
      requesterDomain + Endpoints.PUBLIC_LINK_URL + link.getPublicId()
    );
    result.put(Files.GraphQL.Link.CREATED_AT, link.getCreatedAt());

    link
      .getExpiresAt()
      .ifPresent(expiration -> result.put(Files.GraphQL.Link.EXPIRES_AT, expiration));

    link
      .getDescription()
      .ifPresent(description -> result.put(Files.GraphQL.Link.DESCRIPTION, description));

    linkContext.put(GraphQL.Link.NODE, link.getNodeId());

    return DataFetcherResult
      .<Map<String, Object>>newResult()
      .localContext(linkContext)
      .data(result)
      .build();
  }

  /**
   * TODO: update javadoc
   * <p>This {@link DataFetcher} must be used for the {@link Files.GraphQL.Mutations#CREATE_LINK} mutation.</p>
   * <p>The request must have the following parameters in input:</p>
   * <ul>
   * <li>{@link Files.GraphQL.InputParameters.Link#NODE_ID}: a {@link String} representing the id of the node (this is
   * mandatory).</li>
   * <li>{@link Files.GraphQL.InputParameters.Link#DESCRIPTION}: a {@link String} representing the description of the
   * link to create (this is optional).</li>
   * <li>{@link Files.GraphQL.InputParameters.Link#EXPIRES_AT}: a long representing the expiration timestamp.</li>
   * </ul>
   * <h2>Behaviour:</h2>
   * <p>It creates the link with the values specified in input, it saves the mandatory parameter necessary to fetch
   * the related {@link Files.GraphQL.Node} object, then it creates the GraphQL map of the new link.</p>
   *
   * @return an asynchronous {@link DataFetcher} containing a {@link Map} of all the attributes
   * values of the created link.
   */
  public DataFetcher<CompletableFuture<DataFetcherResult<Map<String, Object>>>> createLink() {
    return environment -> CompletableFuture.supplyAsync(() -> {
      ResultPath path = environment.getExecutionStepInfo().getPath();
      User requester = environment.getGraphQlContext().get(Files.GraphQL.Context.REQUESTER);
      String nodeId = environment.getArgument(Files.GraphQL.InputParameters.Link.NODE_ID);

      if (permissionsChecker
        .getPermissions(nodeId, requester.getId())
        .has(SharePermission.READ_AND_SHARE)
        && nodeRepository.getNode(nodeId).get().getNodeType() != NodeType.FOLDER
      ) {
        String publicId = RandomStringUtils.randomAlphanumeric(8);

        Link createdLink = linkRepository.createLink(
          UUID.randomUUID().toString(),
          nodeId,
          publicId,
          Optional.ofNullable(environment.getArgument(InputParameters.Link.EXPIRES_AT)),
          Optional.ofNullable(environment.getArgument(InputParameters.Link.DESCRIPTION))
        );

        return convertLinkToGraphQLMap(createdLink, requester.getDomain());
      }

      return DataFetcherResult.<Map<String, Object>>newResult()
        .error(GraphQLResultErrors.nodeWriteError(nodeId, path))
        .build();
    });
  }

  public DataFetcher<CompletableFuture<List<DataFetcherResult<Map<String, Object>>>>> getLinks() {
    return environment -> CompletableFuture.supplyAsync(() -> {
      User requester = environment.getGraphQlContext().get(Files.GraphQL.Context.REQUESTER);
      Optional<Map<String, String>> optLocalContext = Optional
        .ofNullable(environment.getLocalContext());

      String nodeId = (optLocalContext.isPresent())
        ? optLocalContext.get().get(Node.ID)
        : environment.getArgument(InputParameters.Link.NODE_ID);

      return permissionsChecker
        .getPermissions(nodeId, requester.getId())
        .has(SharePermission.READ_AND_SHARE)
        ? linkRepository
        .getLinksByNodeId(nodeId, LinkSort.CREATED_AT_DESC)
        .map(link -> convertLinkToGraphQLMap(link, requester.getDomain()))
        .collect(Collectors.toList())
        : Collections.singletonList(DataFetcherResult
          .<Map<String, Object>>newResult()
          //.error(GraphQLResultErrors.nodeWriteError(nodeId, path))
          .build()
        );
    });
  }

  public DataFetcher<CompletableFuture<DataFetcherResult<Map<String, Object>>>> updateLink() {
    return environment -> CompletableFuture.supplyAsync(() -> {
      ResultPath path = environment.getExecutionStepInfo().getPath();
      User requester = environment.getGraphQlContext().get(Files.GraphQL.Context.REQUESTER);
      String linkId = environment.getArgument(Files.GraphQL.InputParameters.Link.LINK_ID);

      return linkRepository.getLinkById(linkId)
        .filter(link -> permissionsChecker
          .getPermissions(link.getNodeId(), requester.getId())
          .has(SharePermission.READ_AND_SHARE)
        )
        .map(link -> {
            Optional<Long> optNewExpirationTimestamp = Optional.ofNullable(
              environment.getArgument(InputParameters.Link.EXPIRES_AT)
            );
            Optional<String> optNewDescription = Optional.ofNullable(
              environment.getArgument(InputParameters.Link.DESCRIPTION)
            );

            optNewExpirationTimestamp.ifPresent(link::setExpiresAt);
            optNewDescription.ifPresent(link::setDescription);

            Link updatedLink = linkRepository.updateLink(link);
            return convertLinkToGraphQLMap(updatedLink, requester.getDomain());
          }
        )
        .orElse(DataFetcherResult
          .<Map<String, Object>>newResult()
          .error(GraphQLResultErrors.linkNotFound(linkId, path))
          .build());
    });
  }

  public DataFetcher<CompletableFuture<DataFetcherResult<List<String>>>> deleteLinks() {
    return environment -> CompletableFuture.supplyAsync(() ->
    {
      ResultPath path = environment.getExecutionStepInfo().getPath();
      String requesterId = ((User) environment
        .getGraphQlContext()
        .get(Files.GraphQL.Context.REQUESTER)).getId();
      List<String> linkIds = environment.getArgument(InputParameters.Link.LINK_IDS);

      List<String> linkIdsToDelete = linkIds
        .stream()
        .map(linkRepository::getLinkById)
        .filter(Optional::isPresent)
        .map(Optional::get)
        .filter(link -> permissionsChecker
          .getPermissions(link.getNodeId(), requesterId)
          .has(SharePermission.READ_AND_SHARE))
        .map(Link::getLinkId)
        .collect(Collectors.toList());

      linkRepository.deleteLinksBulk(linkIdsToDelete);

      return DataFetcherResult
        .<List<String>>newResult()
        .data(linkIdsToDelete)
        .errors(linkIds.stream()
          .filter(linkId -> !linkIdsToDelete.contains(linkId))
          .map(linkIdError -> GraphQLResultErrors.linkNotFound(linkIdError, path))
          .collect(Collectors.toList()))
        .build();
    });
  }
}
