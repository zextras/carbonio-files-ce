// SPDX-FileCopyrightText: 2022 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.graphql.datafetchers;
import com.zextras.carbonio.files.graphql.SyncCompletableFuture;

import com.zextras.carbonio.files.Constants;
import com.zextras.carbonio.files.Constants.API.Endpoints;
import com.zextras.carbonio.files.Constants.GraphQL;
import com.zextras.carbonio.files.Constants.GraphQL.InputParameters;
import com.zextras.carbonio.files.dal.dao.ebean.ACL.SharePermission;
import com.zextras.carbonio.files.dal.dao.ebean.Link;
import com.zextras.carbonio.files.dal.dao.ebean.Node;
import com.zextras.carbonio.files.dal.dao.ebean.NodeType;
import com.zextras.carbonio.files.dal.repositories.impl.ebean.utilities.LinkSort;
import com.zextras.carbonio.files.dal.repositories.interfaces.LinkRepository;
import com.zextras.carbonio.files.dal.repositories.interfaces.NodeRepository;
import com.zextras.carbonio.files.graphql.errors.GraphQLResultErrors;
import com.zextras.carbonio.files.utilities.PermissionsChecker;
import com.zextras.carbonio.files.dal.dao.UserMyself;
import graphql.execution.DataFetcherResult;
import graphql.execution.ResultPath;
import graphql.schema.DataFetcher;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import org.apache.commons.lang3.RandomStringUtils;

import static com.zextras.carbonio.files.Constants.Config.Link.MAX_LINKS_PER_NODE;

/**
 * <p>Contains all the implementations of {@link DataFetcher}s for all the queries and mutations
 * defined in the GraphQL schema that are related to the {@link Constants.GraphQL.Link} type.</p>
 * <p>Each {@link DataFetcher} implementation is asynchronous and returns an {@link HashMap}
 * containing the data fetched from the database. Each key of the resulting map must match the name
 * of the related Link's attribute defined in the GraphQL schema.</p>
 * <p>These {@link DataFetcher}s will be used by the GraphQL provider (wired in a later phase)
 * where they are bound with the related queries, mutations and composed attributes.</p>
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
@ApplicationScoped
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

  /**
   * Builds the public URL of a {@link Link}, exactly as the GraphQL {@code createLink}/{@code
   * getLinks} responses do: {@code <domain>/<access-or-download-endpoint>/<publicId>}. Extracted so
   * both the GraphQL layer and the trusted-caller REST {@code POST /internal/links} endpoint
   * ({@code InternalNodeResource}) format the URL identically, from a single source of truth.
   *
   * @param link the created/loaded link
   * @param requesterDomain the requester's domain (URL prefix)
   * @param isNodeAFolder whether the linked node is a folder (access URL) or a file (download URL)
   * @return the fully-formatted public link URL
   */
  public String buildPublicLinkUrl(Link link, String requesterDomain, boolean isNodeAFolder) {
    return (isNodeAFolder)
      ? requesterDomain + Endpoints.PUBLIC_LINK_ACCESS_URL + link.getPublicId()
      : requesterDomain + Endpoints.PUBLIC_LINK_DOWNLOAD_URL + link.getPublicId();
  }

  private DataFetcherResult<Map<String, Object>> convertLinkToGraphQLMap(
    Link link,
    String requesterDomain,
    boolean isNodeAFolder
  ) {
    Map<String, Object> result = new HashMap<>();
    Map<String, String> linkContext = new HashMap<>();

    result.put(Constants.GraphQL.Link.ID, link.getLinkId());

    String publicLinkUrl = buildPublicLinkUrl(link, requesterDomain, isNodeAFolder);

    result.put(Constants.GraphQL.Link.URL, publicLinkUrl);
    result.put(Constants.GraphQL.Link.CREATED_AT, link.getCreatedAt());

    link
      .getExpiresAt()
      .ifPresent(expiration -> result.put(Constants.GraphQL.Link.EXPIRES_AT, expiration));

    link
      .getDescription()
      .ifPresent(description -> result.put(Constants.GraphQL.Link.DESCRIPTION, description));

    link
      .getAccessCode()
      .ifPresent(accessCode -> result.put(GraphQL.Link.ACCESS_CODE, accessCode));

    linkContext.put(GraphQL.Link.NODE, link.getNodeId());

    return DataFetcherResult
      .<Map<String, Object>>newResult()
      .localContext(linkContext)
      .data(result)
      .build();
  }

  /**
   * <p>This {@link DataFetcher} must be used for the {@link Constants.GraphQL.Mutations#CREATE_LINK} mutation.</p>
   * <p>The request must have the following parameters in input:</p>
   * <ul>
   * <li>{@link Constants.GraphQL.InputParameters.Link#NODE_ID}: a {@link String} representing the id of the node (this is
   * mandatory).</li>
   * <li>{@link Constants.GraphQL.InputParameters.Link#DESCRIPTION}: a {@link String} representing the description of the
   * link to create (this is optional).</li>
   * <li>{@link Constants.GraphQL.InputParameters.Link#EXPIRES_AT}: a long representing the expiration timestamp.</li>
   * </ul>
   * <h2>Behaviour:</h2>
   * <p>It creates the link with the values specified in input, it saves the mandatory parameter necessary to fetch
   * the related {@link Constants.GraphQL.Node} object, then it creates the GraphQL map of the new link.</p>
   *
   * @return an asynchronous {@link DataFetcher} containing a {@link Map} of all the attributes
   * values of the created link.
   */
  /**
   * Reusable core of the {@code createLink} mutation: enforces the READ_AND_SHARE permission,
   * checks the node exists and is not a {@link NodeType#ROOT}, enforces the per-node link cap, then
   * creates the public link and returns it. Shared verbatim by the GraphQL {@link #createLink()}
   * DataFetcher and the trusted-caller REST {@code POST /internal/links} endpoint ({@code
   * InternalNodeResource}), so the link-creation business logic lives in one place.
   *
   * <p>The three optional link attributes (expiration, description, access code) are always empty
   * here — the GraphQL mutation supplies them via the overload below; the gRPC RPC never sets them.
   *
   * @param requesterId the id of the user creating the link
   * @param nodeId the id of the node to link
   * @param optExpiresAt optional expiration timestamp
   * @param optDescription optional link description
   * @param optAccessCode optional access code
   * @return the created {@link Link}
   * @throws LinkLimitReachedException if the node already has {@link Constants.Config.Link#MAX_LINKS_PER_NODE} links
   * @throws NodeAccessException if the requester lacks READ_AND_SHARE, or the node is missing / a root
   */
  public Link createPublicLink(
    String requesterId,
    String nodeId,
    Optional<Long> optExpiresAt,
    Optional<String> optDescription,
    Optional<String> optAccessCode
  ) {
    Optional<Node> optNode = nodeRepository.getNode(nodeId);
    if (permissionsChecker
      .getPermissions(nodeId, requesterId)
      .has(SharePermission.READ_AND_SHARE)
      && optNode.isPresent() && optNode.get().getNodeType() != NodeType.ROOT
    ) {
      if (linkRepository.getLinkCountByNode(optNode.get()) >= MAX_LINKS_PER_NODE) {
        throw new LinkLimitReachedException(nodeId);
      }

      String publicId = RandomStringUtils.secure().nextAlphanumeric(50);

      return linkRepository.createLink(
        UUID.randomUUID().toString(),
        nodeId,
        publicId,
        optExpiresAt,
        optDescription,
        optAccessCode
      );
    }

    throw new NodeAccessException(nodeId);
  }

  public DataFetcher<CompletableFuture<DataFetcherResult<Map<String, Object>>>> createLink() {
    return environment -> SyncCompletableFuture.supplyAsync(() -> {
      ResultPath path = environment.getExecutionStepInfo().getPath();
      UserMyself requester = environment.getGraphQlContext().get(Constants.GraphQL.Context.REQUESTER);
      String nodeId = environment.getArgument(Constants.GraphQL.InputParameters.Link.NODE_ID);

      try {
        Link createdLink = createPublicLink(
          requester.getId().getUserId(),
          nodeId,
          Optional.ofNullable(environment.getArgument(InputParameters.Link.EXPIRES_AT)),
          Optional.ofNullable(environment.getArgument(InputParameters.Link.DESCRIPTION)),
          Optional.ofNullable(environment.getArgument(InputParameters.Link.ACCESS_CODE))
        );

        boolean isNodeAFolder = nodeRepository.getNode(nodeId)
          .map(node -> node.getNodeType().equals(NodeType.FOLDER))
          .orElse(false);

        return convertLinkToGraphQLMap(createdLink, requester.getDomain(), isNodeAFolder);
      } catch (LinkLimitReachedException e) {
        return DataFetcherResult.<Map<String, Object>>newResult()
          .error(GraphQLResultErrors.linkLimitExceeded(nodeId, path))
          .build();
      } catch (NodeAccessException e) {
        return DataFetcherResult.<Map<String, Object>>newResult()
          .error(GraphQLResultErrors.nodeWriteError(nodeId, path))
          .build();
      }
    });
  }

  /** Thrown by {@link #createPublicLink} when a node already has the maximum number of links. */
  public static class LinkLimitReachedException extends RuntimeException {
    public LinkLimitReachedException(String nodeId) {
      super("Link limit reached for node " + nodeId);
    }
  }

  /**
   * Thrown by {@link #createPublicLink} when the requester lacks the READ_AND_SHARE permission, or
   * the target node does not exist / is a root (mirrors the GraphQL {@code nodeWriteError}).
   */
  public static class NodeAccessException extends RuntimeException {
    public NodeAccessException(String nodeId) {
      super("Cannot create link for node " + nodeId
        + ": missing READ_AND_SHARE permission, node not found, or node is a root");
    }
  }

  public DataFetcher<CompletableFuture<List<DataFetcherResult<Map<String, Object>>>>> getLinks() {
    return environment -> SyncCompletableFuture.supplyAsync(() -> {
      UserMyself requester = environment.getGraphQlContext().get(Constants.GraphQL.Context.REQUESTER);
      Optional<Map<String, String>> optLocalContext = Optional
        .ofNullable(environment.getLocalContext());

      String nodeId = (optLocalContext.isPresent())
        ? optLocalContext.get().get(GraphQL.Node.ID)
        : environment.getArgument(InputParameters.Link.NODE_ID);

      Optional<Node> optNode = nodeRepository.getNode(nodeId);

      return permissionsChecker
        .getPermissions(nodeId, requester.getId().getUserId())
        .has(SharePermission.READ_AND_SHARE) && optNode.isPresent()
        ? linkRepository
        .getLinksByNodeId(nodeId, LinkSort.CREATED_AT_DESC)
        .map(link ->
          convertLinkToGraphQLMap(
            link,
            requester.getDomain(),
            optNode.get().getNodeType().equals(NodeType.FOLDER)))
        .collect(Collectors.toList())
        : Collections.singletonList(DataFetcherResult
          .<Map<String, Object>>newResult()
          //.error(GraphQLResultErrors.nodeWriteError(nodeId, path))
          .build()
        );
    });
  }

  public DataFetcher<CompletableFuture<DataFetcherResult<Map<String, Object>>>> updateLink() {
    return environment -> SyncCompletableFuture.supplyAsync(() -> {
      ResultPath path = environment.getExecutionStepInfo().getPath();
      UserMyself requester = environment.getGraphQlContext().get(Constants.GraphQL.Context.REQUESTER);
      String linkId = environment.getArgument(Constants.GraphQL.InputParameters.Link.LINK_ID);

      return linkRepository.getLinkById(linkId)
        .filter(link -> permissionsChecker
          .getPermissions(link.getNodeId(), requester.getId().getUserId())
          .has(SharePermission.READ_AND_SHARE)
        )
        .map(link -> {
            Optional<Node> optNode = nodeRepository.getNode(link.getNodeId());

            if (optNode.isEmpty()) {
              return DataFetcherResult
                .<Map<String, Object>>newResult()
                .error(GraphQLResultErrors.nodeNotFound(link.getNodeId(), path))
                .build();
            }

            Optional<Long> optNewExpirationTimestamp = Optional.ofNullable(
              environment.getArgument(InputParameters.Link.EXPIRES_AT)
            );
            Optional<String> optNewDescription = Optional.ofNullable(
              environment.getArgument(InputParameters.Link.DESCRIPTION)
            );
            Optional<String> optNewAccessCode = Optional.ofNullable(
              environment.getArgument(InputParameters.Link.ACCESS_CODE)
            );

            optNewExpirationTimestamp.ifPresent(link::setExpiresAt);
            optNewDescription.ifPresent(link::setDescription);
            optNewAccessCode.ifPresent(link::setAccessCode);

            Link updatedLink = linkRepository.updateLink(link);
            return convertLinkToGraphQLMap(
              updatedLink,
              requester.getDomain(),
              optNode.get().getNodeType().equals(NodeType.FOLDER));
          }
        )
        .orElse(DataFetcherResult
          .<Map<String, Object>>newResult()
          .error(GraphQLResultErrors.linkNotFound(linkId, path))
          .build());
    });
  }

  public DataFetcher<CompletableFuture<DataFetcherResult<List<String>>>> deleteLinks() {
    return environment -> SyncCompletableFuture.supplyAsync(() ->
    {
      ResultPath path = environment.getExecutionStepInfo().getPath();
      String requesterId = ((UserMyself) environment
        .getGraphQlContext()
        .get(Constants.GraphQL.Context.REQUESTER)).getId().getUserId();
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
