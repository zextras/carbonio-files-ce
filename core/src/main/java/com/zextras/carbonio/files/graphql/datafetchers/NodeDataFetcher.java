// SPDX-FileCopyrightText: 2022 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.graphql.datafetchers;

import com.google.inject.Inject;
import com.zextras.carbonio.files.Files;
import com.zextras.carbonio.files.Files.Db.RootId;
import com.zextras.carbonio.files.Files.GraphQL;
import com.zextras.carbonio.files.Files.GraphQL.Context;
import com.zextras.carbonio.files.Files.GraphQL.InputParameters;
import com.zextras.carbonio.files.Files.GraphQL.InputParameters.FlagNodes;
import com.zextras.carbonio.files.Files.GraphQL.InputParameters.GetVersions;
import com.zextras.carbonio.files.Files.GraphQL.InputParameters.KeepVersions;
import com.zextras.carbonio.files.Files.GraphQL.InputParameters.RestoreNodes;
import com.zextras.carbonio.files.Files.GraphQL.NodePage;
import com.zextras.carbonio.files.Files.ServiceDiscover;
import com.zextras.carbonio.files.Files.ServiceDiscover.Config;
import com.zextras.carbonio.files.clients.ServiceDiscoverHttpClient;
import com.zextras.carbonio.files.config.FilesConfig;
import com.zextras.carbonio.files.dal.dao.User;
import com.zextras.carbonio.files.dal.dao.ebean.ACL;
import com.zextras.carbonio.files.dal.dao.ebean.ACL.SharePermission;
import com.zextras.carbonio.files.dal.dao.ebean.FileVersion;
import com.zextras.carbonio.files.dal.dao.ebean.Node;
import com.zextras.carbonio.files.dal.dao.ebean.NodeCustomAttributes;
import com.zextras.carbonio.files.dal.dao.ebean.NodeType;
import com.zextras.carbonio.files.dal.dao.ebean.Share;
import com.zextras.carbonio.files.dal.dao.ebean.TrashedNode;
import com.zextras.carbonio.files.dal.repositories.impl.ebean.utilities.NodeSort;
import com.zextras.carbonio.files.dal.repositories.interfaces.FileVersionRepository;
import com.zextras.carbonio.files.dal.repositories.interfaces.NodeRepository;
import com.zextras.carbonio.files.dal.repositories.interfaces.ShareRepository;
import com.zextras.carbonio.files.dal.repositories.interfaces.TombstoneRepository;
import com.zextras.carbonio.files.graphql.GraphQLProvider;
import com.zextras.carbonio.files.graphql.errors.GraphQLResultErrors;
import com.zextras.carbonio.files.graphql.types.Permissions;
import com.zextras.carbonio.files.utilities.PermissionsChecker;
import com.zextras.filestore.model.FilesIdentifier;
import graphql.GraphQLError;
import graphql.execution.AbortExecutionException;
import graphql.execution.DataFetcherResult;
import graphql.execution.DataFetcherResult.Builder;
import graphql.execution.ResultPath;
import graphql.schema.DataFetcher;
import graphql.schema.GraphQLObjectType;
import graphql.schema.TypeResolver;
import graphql.schema.idl.EnumValuesProvider;
import io.ebean.annotation.Transactional;
import io.vavr.control.Try;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.Vector;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Contains all the implementations of the {@link DataFetcher}s for all the queries and mutations
 * related to the Node, File and Folder types defined in the GraphQL schema.
 * <p>
 * The implementation of each {@link DataFetcher} is asynchronous and returns an {@link HashMap}
 * containing the data fetched from the database. Each key of the resulting map must match the
 * attribute of the related type defined in the GraphQL schema.
 * <p>
 * These {@link DataFetcher}s will be used in the {@link GraphQLProvider} where they are bound with
 * the related queries, mutations and the composed attributes.
 * <p>
 * <strong>GraphQL behaviour:</strong> When a {@link DataFetcher} returns an empty {@link Map} the
 * GraphQL library:
 * <ul>
 *   <li>
 *     returns an error, if the related attribute was defined not <code>null</code> in the schema,
 *     because it cannot find the mandatory attributes related to the Node inside the {@link Map}
 *   </li>
 *   <li>
 *     associates <code>null</code> to the attribute specified if it was defined that can be
 *     <code>null</code> in the schema
 *   </li>
 * </ul>
 */
public class NodeDataFetcher {

  private static final Logger logger =
    LoggerFactory.getLogger(NodeDataFetcher.class);

  private final NodeRepository        nodeRepository;
  private final FileVersionRepository fileVersionRepository;
  private final PermissionsChecker    permissionsChecker;
  private final ShareRepository       shareRepository;
  private final TombstoneRepository   tombstoneRepository;
  private final ShareDataFetcher      shareDataFetcher;
  private final FilesConfig           filesConfig;
  private final int                   maxNumberOfVersions;
  private final int                   maxNumberOfKeepVersions;

  @Inject
  NodeDataFetcher(
    NodeRepository nodeRepository,
    FileVersionRepository fileVersionRepository,
    PermissionsChecker permissionsChecker,
    ShareRepository shareRepository,
    TombstoneRepository tombstoneRepository,
    ShareDataFetcher shareDataFetcher,
    FilesConfig filesConfig
  ) {
    this.nodeRepository = nodeRepository;
    this.fileVersionRepository = fileVersionRepository;
    this.shareRepository = shareRepository;
    this.permissionsChecker = permissionsChecker;
    this.tombstoneRepository = tombstoneRepository;
    this.shareDataFetcher = shareDataFetcher;
    this.filesConfig = filesConfig;

    this.maxNumberOfVersions = Integer.parseInt(ServiceDiscoverHttpClient
      .defaultURL(ServiceDiscover.SERVICE_NAME)
      .getConfig(ServiceDiscover.Config.MAX_VERSIONS)
      .getOrElse(String.valueOf(ServiceDiscover.Config.DEFAULT_MAX_VERSIONS)));

    this.maxNumberOfKeepVersions =
      this.maxNumberOfVersions <= Config.DIFF_MAX_VERSION_AND_MAX_KEEP_VERSION
        ? 0
        : this.maxNumberOfVersions - Config.DIFF_MAX_VERSION_AND_MAX_KEEP_VERSION;
  }

  private DataFetcherResult<Map<String, Object>> convertNodeToDataFetcherResult(
    Node node,
    String requesterId,
    ResultPath path
  ) {
    return convertNodeToDataFetcherResult(node, node.getCurrentVersion(), requesterId, path);
  }

  private DataFetcherResult<Map<String, Object>> convertNodeToDataFetcherResult(
    Node node,
    Integer version,
    String requesterId,
    ResultPath path
  ) {
    Map<String, Object> result = new HashMap<>();
    Map<String, String> nodeContext = new HashMap<>();
    Optional<GraphQLError> versionError = Optional.empty();

    result.put(Files.GraphQL.Node.ID, node.getId());
    result.put(Files.GraphQL.Node.CREATED_AT, node.getCreatedAt());
    result.put(Files.GraphQL.Node.UPDATED_AT, node.getUpdatedAt());
    result.put(Files.GraphQL.Node.NAME, node.getName());
    result.put(Files.GraphQL.Node.TYPE, node.getNodeType().name());

    result.put(
      Files.GraphQL.Node.ROOT_ID,
      node.getNodeType().equals(NodeType.ROOT)
        ? node.getId()
        : node.getAncestorsList().get(0)
    );

    result.put(
      GraphQL.Node.FLAGGED,
      node
        .getCustomAttributes()
        .stream()
        .filter(attributes -> requesterId.equals(attributes.getUserId()))
        .findFirst()
        .map(NodeCustomAttributes::getFlag)
        .orElse(false)
    );

    node
      .getDescription()
      .ifPresent(description -> result.put(Files.GraphQL.Node.DESCRIPTION, description));

    node
      .getParentId()
      .ifPresent(parentId -> nodeContext.put(Files.GraphQL.Node.PARENT, parentId));

    if (!node.getNodeType().equals(NodeType.FOLDER) && !node.getNodeType().equals(NodeType.ROOT)) {
      node
        .getExtension()
        .ifPresent(extension -> result.put(Files.GraphQL.Node.EXTENSION, extension));

      Optional<FileVersion> optFileVersion = node
        .getFileVersions()
        .stream()
        .filter(fileVersion -> version.equals(fileVersion.getVersion()))
        .findFirst();

      if (optFileVersion.isPresent()) {
        result.putAll(convertFileVersionToGraphQLMap(optFileVersion.get()));
      } else {
        versionError = Optional.of(
          GraphQLResultErrors.fileVersionNotFound(node.getId(), version, path));
      }
    }

    nodeContext.put(Files.GraphQL.Node.OWNER, node.getOwnerId());
    nodeContext.put(Files.GraphQL.Node.CREATOR, node.getCreatorId());
    nodeContext.put(Files.GraphQL.Node.ID, node.getId());

    // TODO Move up when the last_editor coherent between node and file version will be coherent
    Optional
      .ofNullable((String) result.get(GraphQL.Node.LAST_EDITOR))
      .ifPresent(lastEditorId -> nodeContext.put(Files.GraphQL.Node.LAST_EDITOR, lastEditorId));

    DataFetcherResult.Builder<Map<String, Object>> resultBuilder = new DataFetcherResult
      .Builder<Map<String, Object>>()
      .data(result)
      .localContext(nodeContext);

    return versionError
      .map(error -> resultBuilder.error(error).build())
      .orElse(resultBuilder.build());
  }

  private Map<String, Object> convertFileVersionToGraphQLMap(FileVersion fileVersion) {

    Map<String, Object> fileVersionMap = new HashMap<>();
    fileVersionMap.put(Files.GraphQL.FileVersion.UPDATED_AT, fileVersion.getUpdatedAt());
    fileVersionMap.put(GraphQL.FileVersion.LAST_EDITOR, fileVersion.getLastEditorId());
    fileVersionMap.put(Files.GraphQL.FileVersion.VERSION, fileVersion.getVersion());
    fileVersionMap.put(Files.GraphQL.FileVersion.MIME_TYPE, fileVersion.getMimeType());
    fileVersionMap.put(Files.GraphQL.FileVersion.SIZE, fileVersion.getSize());
    fileVersionMap.put(Files.GraphQL.FileVersion.KEEP_FOREVER, fileVersion.isKeptForever());
    fileVersionMap.put(Files.GraphQL.FileVersion.DIGEST, fileVersion.getDigest());
    fileVersion
      .getClonedFromVersion()
      .ifPresent(clonedFromVersion -> fileVersionMap.put(
        GraphQL.FileVersion.CLONED_FROM_VERSION,
        clonedFromVersion)
      );
    return fileVersionMap;
  }


  /**
   * This {@link DataFetcher} must be used for the <code>getNode</code> query. In particular:
   * <ul>
   *  <li>It fetches the node by the id specified in the GraphQL request.</li>
   *  <li>First it checks if the nodeId is in the environment, otherwise it checks if the node is in the localContext
   *  with the key equal to the name of the field who called this dataFetcher.
   *  If we can't retrieve a node id it will return null.</li>
   *  <li>
   *    It converts the {@link Node} to a {@link HashMap} containing all the GraphQL attributes of the Node
   *    defined in the schema. If the node does not exist it returns null.
   *  </li>
   * </ul>
   *
   * @return an asynchronous {@link DataFetcher} containing a {@link Map} of all the attributes
   * values of the node.
   */
  public DataFetcher<CompletableFuture<DataFetcherResult<Map<String, Object>>>> getNodeFetcher() {
    return environment -> {
      ResultPath path = environment.getExecutionStepInfo().getPath();
      AtomicBoolean isParent = new AtomicBoolean(false);
      String nodeId = Optional
        .ofNullable((String) environment.getArgument(InputParameters.NODE_ID))
        .orElseGet(() -> Optional
          .ofNullable(environment.getLocalContext())
          .map(context -> {
            String fieldName = environment.getField().getName();
            if (fieldName.equals(Files.GraphQL.Node.PARENT)) {
              isParent.set(true);
            }
            return ((Map<String, String>) context).get(fieldName);
          })
          .orElse(null));

      return Optional
        .ofNullable(nodeId)
        .map(nId ->
          environment
            .getDataLoader("NodeBatchLoader")
            .load(nId)
            .thenApply(node -> {
              String requesterId =
                ((User) environment.getGraphQlContext().get(Context.REQUESTER)).getId();

              Integer version = Optional
                .ofNullable((Integer) environment.getArgument(Files.GraphQL.FileVersion.VERSION))
                .orElse(((Node) node).getCurrentVersion());

              return permissionsChecker
                .getPermissions(nId, requesterId)
                .has(SharePermission.READ_ONLY)
                ? convertNodeToDataFetcherResult((Node) node, version, requesterId, path)
                : (isParent.get())
                  ? new DataFetcherResult.Builder<Map<String, Object>>().build()
                  : new DataFetcherResult
                    .Builder<Map<String, Object>>()
                    .error(GraphQLResultErrors.nodeNotFound(nId, path))
                    .build();
            }).exceptionally((e) ->
              (isParent.get())
                ? new DataFetcherResult.Builder<Map<String, Object>>().build()
                : new DataFetcherResult
                  .Builder<Map<String, Object>>()
                  .error(GraphQLResultErrors.nodeNotFound(nId, path))
                  .build()
            ))
        .orElse(CompletableFuture.supplyAsync(() ->
          new DataFetcherResult.Builder<Map<String, Object>>().build()
        ));
    };
  }

  /**
   * This {@link TypeResolver} checks which type of Node was requested. The type can be a File or a
   * Folder.
   *
   * @return a {@link TypeResolver} that resolve the type of Node requested.
   */
  public TypeResolver getNodeInterfaceResolver() {
    return environment ->
    {
      Map<String, Object> result = environment.getObject();
      return (result.get(Files.GraphQL.Node.TYPE).equals(NodeType.FOLDER.toString())
        || result.get(Files.GraphQL.Node.TYPE).equals(NodeType.ROOT.toString())
      )
        ? (GraphQLObjectType) environment.getSchema().getType(Files.GraphQL.Types.FOLDER)
        : (GraphQLObjectType) environment.getSchema().getType(Files.GraphQL.Types.FILE);
    };
  }

  public EnumValuesProvider getNodeSortResolver() {
    return NodeSort::valueOf;
  }

  public EnumValuesProvider getNodeTypeResolver() {
    return NodeType::valueOf;
  }

  /**
   * <p>This {@link DataFetcher} must be used to fetch the child nodes of a specific folder. It
   * works only if it is used to resolve attributes that accepts the following parameters in input:
   * <ul>
   *   <li>
   *     {@link Files.GraphQL.InputParameters#LIMIT}: an {@link Integer} of how many elements you want to fetch
   *   </li>
   *   <li>
   *     {@link Files.GraphQL.InputParameters#CURSOR}: a {@link String} of the last element fetched (this is optional,
   *     and it is useful for pagination)
   *   </li>
   *   <li>
   *     {@link Files.GraphQL.InputParameters#SORT}: a {@link NodeSort} representing a specific sort
   *     (optional). If it is not specified a {@link NodeSort#NAME_ASC} is applied by default.
   *   </li>
   * </ul>
   * </p>
   * In particular:
   * <ul>
   *  <li>It fetches all the children ids of the folder with the specified sort order</li>
   *  <li>
   *    It filters only the interested children applying the {@link Files.GraphQL.InputParameters#LIMIT} and the
   *    {@link Files.GraphQL.InputParameters#CURSOR} parameters
   *  </li>
   *  <li>
   *    It fetches all the interested nodes, and it converts each of them into a {@link HashMap} containing all the
   *    GraphQL attributes of the Node defined in the schema.
   *  </li>
   *  <li>
   *    It adds all the children into a {@link List} that can be empty if the folder doesn't have children.
   *  </li>
   * </ul>
   *
   * @return an asynchronous {@link DataFetcher} containing a {@link List} of all child nodes of the
   * folder.
   */
  public DataFetcher<CompletableFuture<List<DataFetcherResult<Map<String, Object>>>>> getChildNodesFetcher() {
    return environment -> CompletableFuture.supplyAsync(() -> {
        Map<String, Object> partialResult = environment.getSource();
        String requesterId = ((User) environment.getGraphQlContext()
          .get(Files.GraphQL.Context.REQUESTER)).getId();
        String folderNodeId = (String) partialResult.get(Files.GraphQL.Node.ID);
        /*
         * If the execution is arrived in this data fetcher then the partialResult contains the folderId
         * otherwise the execution would have stopped in the previous data fetcher.
         * This is only a double check if something goes wrong or if this data fetcher is
         * used improperly.
         */
        if (folderNodeId == null) {
          return Collections.emptyList();
        }

        return nodeRepository.getNode(folderNodeId)
          .map(node -> {
            int limit = environment.getArgument(Files.GraphQL.InputParameters.LIMIT);
            Optional<String> optCursor = Optional.ofNullable(
              environment.getArgument(Files.GraphQL.InputParameters.CURSOR)
            );
            Optional<NodeSort> optSort = Optional.ofNullable(
              environment.getArgument(Files.GraphQL.InputParameters.SORT)
            );

            List<String> childrenIds = nodeRepository
              .getChildrenIds(node.getId(), optSort, Optional.of(requesterId), false)
              .stream()
              .filter(nodeId -> permissionsChecker.getPermissions(nodeId, requesterId)
                .has(SharePermission.READ_ONLY))
              .collect(Collectors.toList());

        /*
          Why I need to add 1 to the result of indexOf?
          Example: list = [4, 1, 6, 3, 5]; cursor = 6
          Result expected: [3, 5] => I need to skip the first three elements

          list.indexOf(6) returns the index 2 because the array starts to 0, so I need to add 1
          to skip the first three elements.
         */
            int numberNodesToSkip = optCursor.map(cursor -> childrenIds.indexOf(cursor) + 1)
              .orElse(0);

            return Optional.of(nodeRepository.getNodes(childrenIds.stream()
                .skip(numberNodesToSkip)
                .limit(limit)
                .collect(Collectors.toList()), optSort)
              .map(childNode -> this.convertNodeToDataFetcherResult(
                childNode,
                requesterId,
                environment.getExecutionStepInfo().getPath())
              )
              .collect(Collectors.toList()));

          })
          .orElse(Optional.of(new ArrayList<>()))
          .get();
      }
    );
  }

  @Transactional
  public DataFetcher<CompletableFuture<DataFetcherResult<Map<String, String>>>> getChildNodesFetcherFast() {
    return environment -> CompletableFuture.supplyAsync(() -> {
        Map<String, Object> partialResult = environment.getSource();
        String requesterId = ((User) environment.getGraphQlContext()
          .get(Files.GraphQL.Context.REQUESTER)).getId();
        String folderNodeId = (String) partialResult.get(Files.GraphQL.Node.ID);
        /*
         * If the execution is arrived in this data fetcher then the partialResult contains the folderId
         * otherwise the execution would have stopped in the previous data fetcher.
         * This is only a double check if something goes wrong or if this data fetcher is
         * used improperly.
         */
        if (folderNodeId == null) {
          return new DataFetcherResult.Builder<Map<String, String>>().build();
        }

        int limit = environment.getArgument(InputParameters.LIMIT);

        Optional<NodeSort> optSort = Optional.ofNullable(
          environment.getArgument(InputParameters.SORT)
        );

        Optional<String> optPageToken = Optional.ofNullable(
          environment.getArgument(Files.GraphQL.InputParameters.PAGE_TOKEN)
        );

        // The LOCAL_ROOT is shared to all the users so in the root potentially can be nodes owned
        // by someone else and shared with the requester. The system must not return these type of
        // nodes when the folder id is LOCAL_ROOT.
        // Optional.empty() option considers all nodes which the requester has permission
        // Optional.of(false) option considers only nodes owned by the requester
        Optional<Boolean> optSharedWithMe = RootId.LOCAL_ROOT.equals(folderNodeId)
          ? Optional.of(false)
          : Optional.empty();

        ImmutablePair<List<Node>, String> findResult = nodeRepository.findNodes(
          requesterId,
          optSort,
          Optional.empty(),
          Optional.of(folderNodeId),
          Optional.of(false),
          optSharedWithMe,
          Optional.empty(),
          Optional.empty(),
          Optional.of(limit),
         Optional.empty(),
          Optional.empty(),
          Collections.emptyList(),
          optPageToken
        );

        Map<String, List<Node>> localContext = new HashMap<>();
        localContext.put(NodePage.NODES, findResult.getLeft());

        Map<String, String> results = new HashMap<>();
        results.put(NodePage.PAGE_TOKEN, findResult.getRight());

        return new DataFetcherResult
          .Builder<Map<String, String>>()
          .data(results)
          .localContext(localContext)
          .build();
      }
    );
  }

  /**
   * <p>This {@link DataFetcher} must be used for the {@link Files.GraphQL.Mutations#CREATE_FOLDER}
   * mutation.</p>
   * <p>The request must have the following parameters in input:</p>
   * <ul>
   *   <li>
   *     {@link Files.GraphQL.InputParameters.CreateFolder#PARENT_ID}: a {@link String} representing the id of the
   *     parent folder for the new folder that needs to be created
   *   </li>
   *   <li>
   *     {@link Files.GraphQL.InputParameters.CreateFolder#NAME}: a {@link String} representing the name of the
   *     new folder
   *   </li>
   * </ul>
   * <h2>Behaviour:</h2>
   * <ul>
   *  <li>
   *    It creates the folder with the specified inputs, and it associates the id of the requester {@link } to
   *    the creator id and owner id of the new folder
   *  </li>
   *  <li>
   *    It converts the {@link Node} to a {@link HashMap} containing all the GraphQL attributes of the Node
   *    defined in the schema.
   *  </li>
   * </ul>
   *
   * @return an asynchronous {@link DataFetcher} containing a {@link Map} of all the attributes
   * values of the new folder.
   */
  public DataFetcher<CompletableFuture<DataFetcherResult<Map<String, Object>>>> createFolderFetcher() {
    return (environment) -> CompletableFuture.supplyAsync(() -> {
        ResultPath resultPath = environment.getExecutionStepInfo().getPath();
        String parentId = environment.getArgument(InputParameters.CreateFolder.PARENT_ID);
        String requesterId = ((User) environment
          .getGraphQlContext()
          .get(Files.GraphQL.Context.REQUESTER)
        ).getId();

        if (permissionsChecker
          .getPermissions(parentId, requesterId)
          .has(SharePermission.READ_AND_WRITE)
        ) {
          return nodeRepository
            .getNode(parentId)
            .filter(parent -> NodeType.FOLDER.equals(parent.getNodeType())
              || NodeType.ROOT.equals(parent.getNodeType())
            )
            .map(parent -> {
              String ownerId = (
                NodeType.ROOT.equals(parent.getNodeType())
                  || requesterId.equals(parent.getOwnerId())
              )
                ? requesterId
                : parent.getOwnerId();

              String folderName = searchAlternativeName(
                ((String) environment.getArgument(InputParameters.CreateFolder.NAME)).trim(),
                parent.getId(),
                ownerId
              );

              final Node createdFolder = nodeRepository.createNewNode(
                UUID.randomUUID().toString(),
                requesterId,
                ownerId,
                parent.getId(),
                folderName,
                "",
                NodeType.FOLDER,
                NodeType.ROOT.equals(parent.getNodeType())
                  ? parentId
                  : parent.getAncestorIds() + "," + parentId,
                0L
              );

              // Add new inherited shares for the new folder.
              // Create share also for the requester if it is not the owner of the parent folder
              createIndirectShare(parentId, createdFolder);

              return convertNodeToDataFetcherResult(
                createdFolder,
                requesterId,
                resultPath
              );
            })
            .orElse(new DataFetcherResult
              .Builder<Map<String, Object>>()
              .error(GraphQLResultErrors.nodeNotFound(parentId.trim(), resultPath))
              .build()
            );
        }
        return new DataFetcherResult
          .Builder<Map<String, Object>>()
          .error(GraphQLResultErrors.nodeWriteError(parentId.trim(), resultPath))
          .build();
      }
    );
  }

  /**
   * <p>This {@link DataFetcher} must be used to fetch the permissions of the requester {@link } on
   * the specified node. It works only if the previous data fetcher creates a
   * {@link Files.GraphQL.Types#NODE_INTERFACE} and if it is bound to resolve attributes that have
   * type {@link Files.GraphQL.Types#PERMISSIONS}.</p>
   * <p>In particular:
   * <ul>
   *  <li>
   *    It extrapolates the node id from the {@link Map} that represents the GraphQL Node created by the previous
   *    {@link DataFetcher}.
   *  </li>
   *  <li>It calculates the {@link ACL} via {@link PermissionsChecker}</li>
   *  <li>It converts the {@link ACL} into a GraphQL {@link Permissions} object</li>
   * </ul>
   * </p>
   *
   * @return an asynchronous {@link DataFetcher} containing a GraphQL {@link Permissions}.
   */
  public DataFetcher<CompletableFuture<DataFetcherResult<Permissions>>> getPermissionsNodeFetcher() {
    return environment -> CompletableFuture.supplyAsync(() -> {
      Map<String, Object> partialResult = environment.getSource();
      String requesterId = ((User) environment.getGraphQlContext()
        .get(Files.GraphQL.Context.REQUESTER)).getId();
      String nodeId = (String) partialResult.get(Files.GraphQL.Node.ID);

      /*
       * If the execution has arrived to this data fetcher then the partialResult contains the nodeId
       * This is only a double check if something goes wrong or if this data fetcher is used improperly.
       */
      Optional.ofNullable(nodeId)
        .orElseThrow(AbortExecutionException::new);

      return new DataFetcherResult.Builder<Permissions>()
        .data(Permissions.build(permissionsChecker.getPermissions(nodeId, requesterId)))
        .build();
    });
  }

  /**
   * <p>This {@link DataFetcher} must be used for the {@link Files.GraphQL.Mutations#UPDATE_NODE}
   * mutation or when it is necessary to update an existing node.</p>
   * <p>The request must have the following parameters in input:</p>
   * <ul>
   *  <li>
   *    {@link Files.GraphQL.InputParameters.UpdateNode#NODE_ID}: a {@link String} representing the id of the node to
   *    update (this is mandatory).
   *  </li>
   *  <li>
   *    {@link Files.GraphQL.InputParameters.UpdateNode#NAME}: a {@link String} representing the new name of the node
   *  </li>
   *  <li>
   *    {@link Files.GraphQL.InputParameters.UpdateNode#DESCRIPTION}: a {@link String} representing the new description
   *    of the node
   *  </li>
   *  <li>
   *    {@link Files.GraphQL.InputParameters.UpdateNode#FLAGGED}: a {@link boolean} to flag or un-flag the node
   *  </li>
   *  <li>
   *    {@link Files.GraphQL.InputParameters.UpdateNode#MARKED_FOR_DELETION}: a {@link boolean} to marked or un-mark for
   *    deletion the node
   *  </li>
   * </ul>
   * <h2>Behaviour:</h2>
   * <p>It retrieves the node, it updates that with the new values specified in input and then it updates the last
   * editor with the id of the requester.</li>
   *
   * @return an asynchronous {@link DataFetcher} containing a {@link Map} of all the attributes
   * values of the updated node.
   */
  public DataFetcher<CompletableFuture<DataFetcherResult<Map<String, Object>>>> updateNodeFetcher() {
    return (environment -> CompletableFuture.supplyAsync(() -> {
      ResultPath path = environment.getExecutionStepInfo()
        .getPath();
      String requesterId = ((User) environment.getGraphQlContext()
        .get(Files.GraphQL.Context.REQUESTER)).getId();
      String nodeId = environment.getArgument(Files.GraphQL.InputParameters.UpdateNode.NODE_ID);

      if (permissionsChecker.getPermissions(nodeId, requesterId)
        .has(SharePermission.READ_AND_WRITE)) {
        Node nodeToUpdate = nodeRepository.getNode(nodeId)
          .get();
        String parentFolderId = nodeToUpdate.getParentId()
          .orElse(RootId.LOCAL_ROOT);
        Optional<String> optName = Optional.ofNullable(
          environment.getArgument(Files.GraphQL.InputParameters.UpdateNode.NAME)
        );
        Optional<String> optDescription = Optional.ofNullable(
          environment.getArgument(Files.GraphQL.InputParameters.UpdateNode.DESCRIPTION)
        );
        Optional<Boolean> optFlagged = Optional.ofNullable(
          environment.getArgument(Files.GraphQL.InputParameters.UpdateNode.FLAGGED)
        );

        if (optName.isPresent()) {
          // In input the system receives only the name without its extension.
          // Here we create the fullName to check if there are duplicates in the destination folder
          String nodeFullName = nodeToUpdate
            .getExtension()
            .map(extension -> optName.get() + "." + extension)
            .orElse(optName.get());

          if (searchAlternativeName(nodeFullName, parentFolderId, nodeToUpdate.getOwnerId()).equals(
            nodeFullName)) {
            nodeToUpdate.setName(optName.get());
          } else {
            return new DataFetcherResult.Builder<Map<String, Object>>()
              .error(GraphQLResultErrors.duplicateNode(nodeId, parentFolderId, path))
              .build();
          }
        }
        optDescription.ifPresent(nodeToUpdate::setDescription);
        optFlagged.ifPresent(flag -> nodeRepository.flagForUser(nodeId, requesterId, flag));
        nodeToUpdate.setLastEditorId(requesterId);
        return convertNodeToDataFetcherResult(
          nodeRepository.updateNode(nodeToUpdate),
          requesterId,
          environment.getExecutionStepInfo().getPath()
        );
      }

      return new DataFetcherResult.Builder<Map<String, Object>>()
        .error(GraphQLResultErrors.nodeNotFound(nodeId, path))
        .build();
    }));
  }

  public DataFetcher<CompletableFuture<List<String>>> flagNodes() {
    return environment -> CompletableFuture.supplyAsync(() -> {
      String requesterId = ((User) environment.getGraphQlContext()
        .get(Files.GraphQL.Context.REQUESTER)).getId();
      List<String> nodesIds = environment.getArgument(FlagNodes.NODE_IDS);
      boolean starNodes = environment.getArgument(FlagNodes.FLAG);

      return nodesIds
        .stream()
        .map(nodeId -> {
          nodeRepository.flagForUser(nodeId, requesterId, starNodes);
          return nodeId;
        })
        .collect(Collectors.toList());
    });
  }

  /**
   * This service is used to trash a batch of {@link Node}. The service will return the list of ids
   * of the nodes it was able to trash and in the error array the list of nodes it was unable to
   * trash.
   *
   * @return a {@link List<String>} containing the list of marked nodes
   */
  public DataFetcher<CompletableFuture<DataFetcherResult<List<String>>>> trashNodes() {
    return environment -> CompletableFuture.supplyAsync(() ->
    {
      String requesterId = ((User) environment.getGraphQlContext()
        .get(Files.GraphQL.Context.REQUESTER)).getId();
      List<String> nodesIds = environment.getArgument(
        Files.GraphQL.InputParameters.TrashNodes.NODE_IDS);

      List<String> trashableNodes = nodesIds.stream()
        .filter(nodeId -> {
          Optional<Node> rNode = nodeRepository.getNode(nodeId);
          return rNode.isPresent() && rNode.get()
            .getNodeType() != NodeType.ROOT;
        })
        .filter(nodeId -> {
          return permissionsChecker.getPermissions(nodeId, requesterId)
            .has(SharePermission.READ_AND_WRITE);
        })
        .collect(Collectors.toList());

      List<String> nodesInError = nodesIds.stream()
        .filter(nodeId -> !trashableNodes.contains(nodeId))
        .collect(Collectors.toList());

      if (!trashableNodes.isEmpty()) {
        nodeRepository.getNodes(trashableNodes, Optional.empty())
          .forEach(trashedNode -> {
            String nodeParentId = trashedNode.getParentId()
              .get();
            trashedNode.setAncestorIds(Files.Db.RootId.TRASH_ROOT);
            trashedNode.setParentId(RootId.TRASH_ROOT);
            nodeRepository.trashNode(trashedNode.getId(), nodeParentId);
            nodeRepository.updateNode(trashedNode);
            cascadeUpdateAncestors(trashedNode);
          });
      }

      return new DataFetcherResult.Builder<List<String>>()
        .data(trashableNodes)
        .errors(nodesInError.stream()
          .map(nodeId -> GraphQLResultErrors.nodeWriteError(nodeId,
            environment.getExecutionStepInfo()
              .getPath()))
          .collect(Collectors.toList()))
        .build();
    });
  }

  /**
   * This service is used to restore a batch of {@link Node}. The service will return the list of
   * {@link Node}s it was able to restore and in the error array the list of node ids it was unable
   * to restore.
   *
   * @return a {@link List<Node>} containing the list of restored nodes
   */
  public DataFetcher<CompletableFuture<List<DataFetcherResult<Map<String, Object>>>>> restoreNodes() {
    return environment -> CompletableFuture.supplyAsync(() ->
    {
      String requesterId = ((User) environment.getGraphQlContext()
        .get(Files.GraphQL.Context.REQUESTER)).getId();
      List<String> nodesIds = environment.getArgument(RestoreNodes.NODE_IDS);

      List<String> restorableNodeIds = nodesIds.stream()
        .filter(nodeId -> {
          Optional<Node> rNode = nodeRepository.getNode(nodeId);
          return rNode.isPresent() && rNode.get()
            .getNodeType() != NodeType.ROOT;
        })
        .filter(nodeId -> permissionsChecker.getPermissions(nodeId, requesterId)
          .has(SharePermission.READ_AND_WRITE))
        .filter(nodeId -> nodeRepository.getTrashedNode(nodeId)
          .isPresent())
        .collect(Collectors.toList());

      List<String> nodesInError = nodesIds.stream()
        .filter(nodeId -> !restorableNodeIds.contains(nodeId))
        .collect(Collectors.toList());

      List<Node> restoredNodes = nodeRepository.getNodes(restorableNodeIds, Optional.empty())
        .map(node -> {
            TrashedNode trashedNode = nodeRepository.getTrashedNode(node.getId())
              .get();
            Optional<Node> parentNode = nodeRepository.getNode(trashedNode.getParentId());
            if (!parentNode.isPresent() || parentNode.get()
              .getAncestorsList()
              .contains(RootId.TRASH_ROOT)) {
              node.setParentId(RootId.LOCAL_ROOT);
              node.setAncestorIds(RootId.LOCAL_ROOT);

              // If the fatherless node has indirect shares then they should become direct
              shareRepository
                .getShares(node.getId(), Collections.emptyList())
                .stream()
                .filter(share -> !share.isDirect())
                .forEach(share -> {
                  share.setDirect(true);
                  shareRepository.updateShare(share); // This can be optimized
                });

            } else {
              String parentId = parentNode.get().getId();
              node.setParentId(parentId);

              String newAncestors = NodeType.ROOT.equals(parentNode.get().getNodeType())
                ? parentId
                : parentNode.get().getAncestorIds() + Node.ANCESTORS_SEPARATOR + parentId;

              node.setAncestorIds(newAncestors);

              shareRepository
                .getShares(parentId, Collections.emptyList())
                .forEach(share -> {
                  shareRepository.upsertShare(
                    node.getId(),
                    share.getTargetUserId(),
                    share.getPermissions(),
                    false,
                    false,
                    share.getExpiredAt()
                  );

                  if (node.getNodeType() == NodeType.FOLDER) {
                    shareDataFetcher.cascadeUpsertShare(
                      node.getId(),
                      share.getTargetUserId(),
                      share.getPermissions(),
                      share.getExpiredAt()
                    );
                  }
                });
            }

            // check if node needs an alternative name
            List<String> targetFolderChildrenFilesName = nodeRepository.getNodes(
                  nodeRepository.getChildrenIds(
                      node.getParentId().get(),
                      Optional.empty(),
                      Optional.empty(),
                      false),
                  Optional.empty()
              )
              .map(Node::getFullName)
              .toList();

            if(targetFolderChildrenFilesName.contains(node.getFullName())) {
              String newName = searchAlternativeName(
                  node.getFullName(), node.getParentId().get(), node.getOwnerId()
              );
              node.setFullName(newName);
            }
            nodeRepository.restoreNode(node.getId());
            nodeRepository.updateNode(node);
            cascadeUpdateAncestors(node);
            return node;
          }
        )
        .collect(Collectors.toList());

      ResultPath path = environment.getExecutionStepInfo()
        .getPath();

      List<DataFetcherResult<Map<String, Object>>> results = restoredNodes
        .stream()
        .map(node -> convertNodeToDataFetcherResult(node, requesterId, path))
        .collect(Collectors.toList());

      results.addAll(nodesInError
        .stream()
        .map(nodeId -> new Builder<Map<String, Object>>()
          .error(GraphQLResultErrors.nodeWriteError(nodeId, path))
          .build()
        )
        .collect(Collectors.toList()));
      return results;
    });
  }

  /**
   * <p>This {@link DataFetcher} retrieves a shared {@link Node} and it creates the related {@link
   * Map}.</p>
   * <p>It <strong>must</strong> be bound to a Share query and used only to retrieve a node that
   * represents the attribute {@link Files.GraphQL.Share#NODE} in a GraphQL Share object. It works
   * only if the localContext exists and if the previous data fetcher saves the
   * {@link Files.GraphQL.InputParameters#NODE_ID} in the context: if one of these pre-conditions
   * are not satisfied then the execution will be aborted with an {@link AbortExecutionException}.
   * </p>
   *
   * @return an asynchronous {@link DataFetcher} containing a {@link Map} of all the attributes
   * values of a shared node.
   */
  public DataFetcher<CompletableFuture<DataFetcherResult<Map<String, Object>>>> sharedNodeFetcher() {
    return environment -> {
      String nodeIdField = environment.getField().getName();

      return Optional
        .ofNullable(((Map<String, String>) environment.getLocalContext()).get(nodeIdField))
        .map(nodeId ->
          environment
            .getDataLoader("NodeBatchLoader")
            .load(nodeId)
            .thenApply(node -> convertNodeToDataFetcherResult(
              (Node) node,
              ((User) environment.getGraphQlContext().get(Context.REQUESTER)).getId(),
              environment.getExecutionStepInfo().getPath())
            )
            .exceptionally((e) -> new DataFetcherResult.Builder<Map<String, Object>>().build())
        )
        .orElse(CompletableFuture.supplyAsync(() ->
          new DataFetcherResult.Builder<Map<String, Object>>().build())
        );

    };
  }

  /**
   * <p>This {@link DataFetcher} is used to fetch the ancestor nodes of a specific one.</p>
   * <p> It will return an ordered list composed of the nodes the user can see,
   * starting from the one nearest the root till the requested node. If the path is requested by the
   * owner the array will start from the root instead of the highest visible shared node.</p>
   *
   * @return an asynchronous {@link DataFetcher} containing a {@link List} of all the nodes
   * composing the path
   */
  public DataFetcher<CompletableFuture<List<DataFetcherResult<Map<String, Object>>>>> getPathFetcher() {
    return environment -> CompletableFuture.supplyAsync(() -> {
      ResultPath path = environment.getExecutionStepInfo().getPath();
      String requesterId = ((User) environment
        .getGraphQlContext()
        .get(Files.GraphQL.Context.REQUESTER))
        .getId();
      String nodeId = environment.getArgument(InputParameters.NODE_ID);

      if (permissionsChecker.getPermissions(nodeId, requesterId).has(SharePermission.READ_ONLY)) {
        return nodeRepository
          .getNode(nodeId)
          .map(node -> {
            List<String> pathNodeIds = new ArrayList<>(node.getAncestorsList());
            pathNodeIds.add(nodeId);
            List<Node> treeNodes = nodeRepository
              .getNodes(pathNodeIds, Optional.empty())
              // The sort is necessary to reflect the order of the nodes in the path
              .sorted(Comparator.comparing(nodeToSort -> pathNodeIds.indexOf(nodeToSort.getId())))
              .collect(Collectors.toList());

            if (node.getNodeType().equals(NodeType.ROOT) || node.getOwnerId().equals(requesterId)) {
              return treeNodes
                .stream()
                .map(currentNode -> convertNodeToDataFetcherResult(currentNode, requesterId, path))
                .collect(Collectors.toList());
            } else {
              List<Share> shares = shareRepository.getShares(
                treeNodes.stream().map(Node::getId).collect(Collectors.toList()),
                requesterId
              );
              List<Node> sharedNodes = treeNodes
                .stream()
                .filter(treeNode ->
                  shares.stream().anyMatch(share -> share.getNodeId().equals(treeNode.getId()))
                )
                .collect(Collectors.toList());

              return treeNodes
                .subList(treeNodes.indexOf(sharedNodes.get(0)), treeNodes.size())
                .stream()
                .map(treeNode -> convertNodeToDataFetcherResult(treeNode, requesterId, path))
                .collect(Collectors.toList());
            }
          })
          .orElse(Collections.singletonList(
            DataFetcherResult
              .<Map<String, Object>>newResult()
              .error(GraphQLResultErrors.nodeNotFound(nodeId, path))
              .build()
          ));
      }
      return Collections.singletonList(
        DataFetcherResult
          .<Map<String, Object>>newResult()
          .error(GraphQLResultErrors.nodeNotFound(nodeId, path))
          .build()
      );
    });
  }

  /**
   * <p>This {@link DataFetcher} retrieves the list of all the root folders.</p>
   * <p>Every object in the list is a root, that is a special kind of {@link Node} which doesn't
   * have owner or creator: in fact, the only meaningful pieces of information for this type are
   * "ID" and "Name".</p>
   *
   * @return an asynchronous {@link DataFetcher} containing a {@link List} of all the root folders.
   */
  public DataFetcher<CompletableFuture<DataFetcherResult<List<Map<String, Object>>>>> getRootsListFetcher() {
    return environment -> CompletableFuture.supplyAsync(() -> {

      List<Node> rootList = nodeRepository.getRootsList();

      List<Map<String, Object>> result = new ArrayList<>();

      rootList.forEach(root -> {

        Map<String, Object> mappedRoot = new HashMap<>();
        mappedRoot.put(Files.GraphQL.Node.ID, root.getId());
        mappedRoot.put(Files.GraphQL.Node.NAME, root.getName());

        result.add(mappedRoot);

      });

      return new DataFetcherResult.Builder<List<Map<String, Object>>>()
        .data(result)
        .build();
    });
  }

  /**
   * <p>This {@link DataFetcher} must be used for the {@link Files.GraphQL.Queries#FIND_NODES}
   * query.</p>
   * <p>The request could have the following optional parameters in input:</p>
   * <ul>
   *  <li>
   *    {@link Files.GraphQL.InputParameters.FindNodes#SORT}: a {@link NodeSort} representing the chosen sort method
   *    for ordering the found nodes.
   *  </li>
   *  <li>
   *    {@link Files.GraphQL.InputParameters.FindNodes#FLAGGED}: a {@link Boolean} representing the value of the flag
   *    to search.
   *  </li>
   *  <li>
   *    {@link Files.GraphQL.InputParameters.FindNodes#SHARED_BY_ME}: a {@link Boolean} for searching only nodes
   *    i shared or not shared by me.
   *  </li>
   *  <li>
   *    {@link Files.GraphQL.InputParameters.FindNodes#SHARED_WITH_ME}: a {@link Boolean} for searching only in nodes
   *    shared with me or not.
   *  </li>
   *  <li>
   *    {@link Files.GraphQL.InputParameters.FindNodes#SKIP}: an {@link Integer} used for starting search from an
   *    offset rather than from the start of the list.
   *  </li>
   *  <li>
   *    {@link Files.GraphQL.InputParameters.FindNodes#LIMIT}: a {@link Integer} to limit the number of returned
   *    results.
   *  </li>
   *  <li>
   *    {@link Files.GraphQL.InputParameters.FindNodes#CURSOR}: a {@link String} containing the page token given
   *    by a previous findNodes call, it's used to keep on the pagination of the dataset, if this is used all other
   *    params will be ignored since all necessary params needed for pagination are saved with the cursor.
   *  </li>
   * </ul>
   *
   * @return an asynchronous {@link DataFetcher} containing the pageToken to use as a cursor for
   * requesting the next page of data.
   */
  public DataFetcher<CompletableFuture<DataFetcherResult<Map<String, String>>>> findNodesFetcher() {
    return environment -> CompletableFuture.supplyAsync(() -> {
      String requesterId = ((User) environment.getGraphQlContext()
        .get(Files.GraphQL.Context.REQUESTER)).getId();
      Optional<Boolean> optFlagged = Optional.ofNullable(
        environment.getArgument(Files.GraphQL.InputParameters.FindNodes.FLAGGED)
      );
      Optional<Boolean> optSharedByMe = Optional.ofNullable(
        environment.getArgument(Files.GraphQL.InputParameters.FindNodes.SHARED_BY_ME)
      );
      Optional<Boolean> optSharedWithMe = Optional.ofNullable(
        environment.getArgument(Files.GraphQL.InputParameters.FindNodes.SHARED_WITH_ME)
      );
      Optional<Boolean> optDirectShare = Optional.ofNullable(
        environment.getArgument(Files.GraphQL.InputParameters.FindNodes.DIRECT_SHARE)
      );
      Optional<String> optFolderId = Optional.ofNullable(
        environment.getArgument(Files.GraphQL.InputParameters.FindNodes.FOLDER_ID)
      );
      Optional<Boolean> optCascade = Optional.ofNullable(
        environment.getArgument(Files.GraphQL.InputParameters.FindNodes.CASCADE)
      );
      Optional<Integer> optLimit = Optional.ofNullable(
        environment.getArgument(Files.GraphQL.InputParameters.FindNodes.LIMIT)
      );
      Optional<NodeSort> optSort = Optional.ofNullable(
        environment.getArgument(Files.GraphQL.InputParameters.FindNodes.SORT)
      );
      Optional<String> optPageToken = Optional.ofNullable(
        environment.getArgument(Files.GraphQL.InputParameters.FindNodes.PAGE_TOKEN)
      );
      Optional<List<String>> optKeywords = Optional.ofNullable(
        environment.getArgument(Files.GraphQL.InputParameters.FindNodes.KEYWORDS)
      );

      Optional<NodeType> optNodeType = Optional.ofNullable(
        environment.getArgument(Files.GraphQL.InputParameters.FindNodes.NODE_TYPE)
      );

      Optional<String> optOwnerId = Optional.ofNullable(
        environment.getArgument(Files.GraphQL.InputParameters.FindNodes.OWNER_ID)
      );

      Map<String, List<Node>> nodeContext = new HashMap<>();
      Map<String, String> result = new HashMap<>();
      ImmutablePair<List<Node>, String> findResult = null;
      findResult = nodeRepository.findNodes(requesterId,
        optSort,
        optFlagged,
        optFolderId,
        optCascade,
        optSharedWithMe,
        optSharedByMe,
        optDirectShare,
        optLimit,
        optNodeType,
        optOwnerId,
        optKeywords.orElse(Collections.emptyList()),
        optPageToken);
      result.put(Files.GraphQL.NodePage.PAGE_TOKEN, findResult.getRight());

      nodeContext.put(Files.GraphQL.NodePage.NODES, findResult.getLeft());
      return new DataFetcherResult.Builder<Map<String, String>>()
        .data(result)
        .localContext(nodeContext)
        .build();

    });
  }

  /**
   * <p>This {@link DataFetcher} must be used for the retrieving the nodes attribute of the
   * {@link Files.GraphQL.Queries#FIND_NODES} query.</p>
   * <p>The necessary condition for this datafetcher to work is that the localContext of graphql
   * already contains the list of nodes i have to return, calculated in the previous
   * findNodesFetcher, this is necessary since we do elaborate together the nodes to return and the
   * pageToken, but for a more clean code it's better to create a new dataFetcher if we have to
   * return non scalar types in a response </p>
   *
   * @return an asynchronous {@link DataFetcher} containing a {@link List} of the nodes found in the
   * local context.
   */
  public DataFetcher<CompletableFuture<List<DataFetcherResult<Map<String, Object>>>>> nodePageFetcher() {
    return environment -> CompletableFuture.supplyAsync(() -> {
      return Optional.ofNullable(environment.getLocalContext())
        .map(context -> {
          return ((Map<String, List<Node>>) context).get(Files.GraphQL.NodePage.NODES)
            .stream()
            .map(node ->
              convertNodeToDataFetcherResult(
                node,
                ((User) environment.getGraphQlContext().get(Context.REQUESTER)).getId(),
                environment.getExecutionStepInfo().getPath()
              )
            )
            .collect(Collectors.toList());
        })
        .orElse(Collections.emptyList());
    });
  }

  /**
   * <p>This {@link DataFetcher} moves one or more nodes into a destination folder. This is bound
   * to the {@link Files.GraphQL.Mutations#MOVE_NODES} mutation.</p>
   * <p>The requester must specify the following input parameters:</p>
   * <ul>
   *   <li>{@link Files.GraphQL.InputParameters.MoveNodes#NODE_IDS}</li> containing a list of nodes id to move.</li
   *   <li>{@link Files.GraphQL.InputParameters.MoveNodes#DESTINATION_ID}</li> containing the folder id where every node
   *   is moved.</li>
   * </ul>
   * <p>The requester must have the {@link SharePermission#READ_AND_WRITE} permission on every nodes that should be
   * moved and on the destination folder.</p>
   *
   * @return an asynchronous {@link DataFetcher} containing a {@link List} of the moved nodes.
   **/
  public DataFetcher<CompletableFuture<List<DataFetcherResult<Map<String, Object>>>>> moveNodesFetcher() {
    return environment -> CompletableFuture.supplyAsync(() -> {
      ResultPath resultPath = environment.getExecutionStepInfo()
        .getPath();
      String requesterId = ((User) environment.getGraphQlContext()
        .get(Files.GraphQL.Context.REQUESTER)).getId();
      List<String> nodeIds = environment.getArgument(
        Files.GraphQL.InputParameters.MoveNodes.NODE_IDS);
      String destinationFolderId = environment.getArgument(
        Files.GraphQL.InputParameters.MoveNodes.DESTINATION_ID);

      if (permissionsChecker.getPermissions(destinationFolderId, requesterId)
        .has(SharePermission.READ_AND_WRITE)) {

        Optional<Node> optDestinationFolder = nodeRepository.getNode(destinationFolderId);
        if (optDestinationFolder.isPresent() &&
          (optDestinationFolder.get()
            .getNodeType()
            .equals(NodeType.FOLDER)
            || optDestinationFolder.get()
            .getNodeType()
            .equals(NodeType.ROOT))
        ) {

          List<String> rootIds = nodeRepository.getRootsList()
            .stream()
            .map(Node::getId)
            .collect(Collectors.toList());

          List<String> nodeIdsToMove = nodeIds
            .stream()
            .filter(nodeId ->
              permissionsChecker.getPermissions(nodeId, requesterId)
                .has(SharePermission.READ_AND_WRITE)
            )
            .filter(nodeId -> !rootIds.contains(nodeId))
            .filter(nodeId -> !destinationFolderId.equals(nodeId))
            .collect(Collectors.toList());

          List<DataFetcherResult<Map<String, Object>>> movedNodesResult = new ArrayList<>();
          if (!nodeIdsToMove.isEmpty()) {
            nodeRepository.moveNodes(nodeIdsToMove, optDestinationFolder.get());

        /*
          We must align ancestors of moved nodes
          We must align shares of moved nodes and it can be done in two steps:
          1. Remove every share of the node to move (and its child up to the leaves, if it is a folder)
          2. Create all the shares of the destination folder (if it has at least one) for the node to move
             (and for its child up to the leaves, if it is a folder)
         */

            nodeIdsToMove
              .forEach(nodeId -> {
                cascadeUpdateAncestors(nodeRepository.getNode(nodeId)
                  .get());
                // Remove inherited shares on source node
                shareRepository
                  .getShares(nodeId, Collections.emptyList())
                  .stream()
                  .filter(share -> !share.isDirect())
                  .forEach(share -> {
                    shareRepository.deleteShare(nodeId, share.getTargetUserId());
                    shareDataFetcher.cascadeDeleteShare(nodeId, share.getTargetUserId());
                  });
                // Add new inherited shares from destination node
                shareRepository
                  .getShares(destinationFolderId, Collections.emptyList())
                  .forEach(share -> {
                    Optional<Share> sourceShare = shareRepository.getShare(nodeId,
                      share.getTargetUserId());
                    // If there's a share on source node is one of the direct shares i did not delete on previous step
                    // I still added the second condition because of safety reasons and to be sure i only operate on
                    // inherited share if other operations in future make it so shares are still present
                    if (!sourceShare.isPresent() || !sourceShare.get()
                      .isDirect()) {
                      shareRepository.upsertShare(
                        nodeId,
                        share.getTargetUserId(),
                        share.getPermissions(),
                        false,
                        false,
                        share.getExpiredAt()
                      );
                      shareDataFetcher.cascadeUpsertShare(
                        nodeId,
                        share.getTargetUserId(),
                        share.getPermissions(),
                        share.getExpiredAt());
                    }
                  });
              });

            movedNodesResult.addAll(nodeRepository
              .getNodes(nodeIdsToMove, Optional.empty())
              .map(node -> convertNodeToDataFetcherResult(node, requesterId, resultPath))
              .collect(Collectors.toList()));
          }

          // List containing every node id that cannot be moved because:
          //  * The requester does not have the write permission
          //  * The requester wants to move a node on itself (nodeId == destinationId)
          //  * The requester wants to move a ROOT
          List<DataFetcherResult<Map<String, Object>>> errorsOfNodesWithoutPermission = nodeIds.stream()
            .filter(nodeId -> !nodeIdsToMove.contains(nodeId))
            .map(nodeId -> new Builder<Map<String, Object>>()
              .error(GraphQLResultErrors.nodeWriteError(nodeId, resultPath))
              .build()
            )
            .collect(Collectors.toList());

          movedNodesResult.addAll(errorsOfNodesWithoutPermission);
          return movedNodesResult;
        }
      }

      return Collections.singletonList(new Builder<Map<String, Object>>()
        .error(GraphQLResultErrors.nodeWriteError(destinationFolderId, resultPath))
        .build());
    });
  }


  /**
   * This method updates the ancestors of all the subnodes of a moved node and recursively navigates
   * the tree for propagating the change
   *
   * @param parentNode
   */
  void cascadeUpdateAncestors(Node parentNode) {
    List<String> childrenIds = nodeRepository.getChildrenIds(parentNode.getId(), Optional.empty(),
      Optional.empty(), true);
    if (!childrenIds.isEmpty()) {
      nodeRepository.moveNodes(childrenIds, parentNode);
      List<Node> childrenNodes = nodeRepository
        .getNodes(childrenIds, Optional.empty())
        .collect(Collectors.toList());
      childrenNodes
        .stream()
        .filter(n -> n.getNodeType()
          .equals(NodeType.FOLDER))
        .forEach(this::cascadeUpdateAncestors);
    }
  }


  public DataFetcher<CompletableFuture<DataFetcherResult<List<String>>>> deleteNodesFetcher() {

    return environment -> CompletableFuture.supplyAsync(() -> {
      ResultPath resultPath = environment.getExecutionStepInfo()
        .getPath();
      String requesterId = ((User) environment.getGraphQlContext()
        .get(Files.GraphQL.Context.REQUESTER)).getId();
      List<String> nodeIds = environment.getArgument(
        Files.GraphQL.InputParameters.DeleteNodes.NODE_IDS);

      List<Node> nodesToDelete = nodeRepository.getNodes(nodeIds, Optional.empty())
        .filter(Objects::nonNull)
        .filter(node -> !node.getNodeType()
          .equals(NodeType.ROOT))
        .filter(node -> permissionsChecker
          .getPermissions(node.getId(), requesterId)
          .has(SharePermission.READ_AND_WRITE)
        )
        .collect(Collectors.toList());

      deleteNodes(nodesToDelete);

      List<String> nodeIdsToDelete = nodesToDelete
        .stream()
        .map(Node::getId)
        .collect(Collectors.toList());

      nodeIdsToDelete.forEach(this::cascadeDeleteNode);

      return new Builder<List<String>>()
        .data(nodeIdsToDelete)
        .errors(nodeIds.stream()
          .filter(nodeId -> !nodeIdsToDelete.contains(nodeId))
          .map(nodeId -> GraphQLResultErrors.nodeNotFound(nodeId, resultPath))
          .collect(Collectors.toList())
        )
        .build();
    });
  }

  private void cascadeDeleteNode(String nodeId) {
    List<String> childrenIds = nodeRepository.getChildrenIds(nodeId, Optional.empty(),
      Optional.empty(), true);

    if (!childrenIds.isEmpty()) {
      List<Node> children = nodeRepository.getNodes(childrenIds, Optional.empty())
        .collect(Collectors.toList());
      deleteNodes(children);
      children
        .stream()
        .filter(node -> node.getNodeType()
          .equals(NodeType.FOLDER))
        .forEach(node -> cascadeDeleteNode(node.getId()));
    }

    // Delete all shares of nodes that are in trash, but not in the hierarchy of the nodeId.
    // This means that these nodes were children of nodeId, but they were trashed before it was trashed.
    shareRepository.deleteSharesBulk(nodeRepository.getTrashedNodeIdsByOldParent(nodeId));
  }

  private void deleteNodes(List<Node> nodes) {
    List<String> nodeIds = nodes.stream()
      .map(Node::getId)
      .collect(Collectors.toList());

    if (!nodeIds.isEmpty()) {
      shareRepository.deleteSharesBulk(nodeIds);

      nodes.stream()
        .filter(node -> !node.getNodeType()
          .equals(NodeType.FOLDER))
        .forEach(node ->
          tombstoneRepository.createTombstonesBulk(
            fileVersionRepository.getFileVersions(node.getId()),
            node.getOwnerId()
          )
        );

      nodeRepository.deleteNodes(nodeIds);
    }
  }

  /**
   * Copies a {@link Node} of type File into a destination folder.
   *
   * @param sourceNode the file that must be copied.
   * @param destinationFolder the {@link Node} representing the destination folder.
   * @param requesterId requester identifier.
   * @param newFileName is an {@link Optional<String>} if we want to give a new name to the copied
   * file, used for name clashes
   *
   * @return the number of copied nodes.
   */
  private Optional<Node> copyFile(
    Node sourceNode,
    Node destinationFolder,
    String requesterId,
    Optional<String> newFileName
  ) {
    String effectiveName = newFileName.orElse(sourceNode.getFullName());
    String ownerId =
      (destinationFolder.getNodeType()
        .equals(NodeType.ROOT) || requesterId.equals(destinationFolder.getOwnerId()))
        ? requesterId
        : destinationFolder.getOwnerId();

    Node createdNode = nodeRepository.createNewNode(
      UUID.randomUUID().toString(),
      requesterId,
      ownerId,
      destinationFolder.getId(),
      effectiveName,
      sourceNode.getDescription().orElse(""),
      sourceNode.getNodeType(),
      NodeType.ROOT.equals(destinationFolder.getNodeType())
        ? destinationFolder.getId()
        : destinationFolder.getAncestorIds() + "," + destinationFolder.getId(),
      sourceNode.getSize()
    );

    // TODO: handle the corner-case when the current version does not exists.
    // It should never happened but we should handle anyways
    FileVersion sourceCurrentFileVersion = fileVersionRepository
      .getFileVersion(sourceNode.getId(), sourceNode.getCurrentVersion())
      .get();

    // TODO: make the copy async
    Try
      .of(() -> filesConfig
        .getFileStoreClient()
        .copy(
          FilesIdentifier.of(sourceNode.getId(), sourceNode.getCurrentVersion(),
            sourceNode.getOwnerId()),
          FilesIdentifier.of(createdNode.getId(), 1, requesterId),
          false
        )
      )
      .onSuccess(copiedBlobResponse -> {
        fileVersionRepository.createNewFileVersion(
          createdNode.getId(),
          requesterId,
          1,
          sourceCurrentFileVersion.getMimeType(),
          copiedBlobResponse.getSize(),
          copiedBlobResponse.getDigest(),
          false
        );

        createdNode.setCurrentVersion(1);
        nodeRepository.updateNode(createdNode);
      })
      .onFailure(failure -> {
        logger.error(MessageFormat.format(
          "Unable to copy the node {0}. {1}",
          sourceNode.getId(),
          failure
        ));
        nodeRepository.deleteNode(createdNode.getId());
      });

    return nodeRepository.getNode(createdNode.getId());
  }


  /**
   * Copies recursively a folder.
   *
   * @param sourceFolderId the folder that must be copied
   * @param destinationFolder the {@link Node} representing the destination folder
   * @param requesterId the id of the requester
   * @param newName is an {@link Optional<String>} if we want to give a new name to the copied
   * folder, used for name clashes
   *
   * @return the number of files copied
   */
  private void copyFolderCascade(
    String sourceFolderId,
    Node destinationFolder,
    String requesterId,
    Optional<String> newName
  ) {

    // Divide folders from files
    List<Node> folderChildren = nodeRepository
      .getNodes(
        nodeRepository.getChildrenIds(sourceFolderId, Optional.empty(), Optional.empty(), false),
        Optional.empty()
      )
      .collect(Collectors.toList());

    List<Node> filesToCopy = folderChildren
      .stream()
      .filter(node -> !node.getNodeType()
        .equals(NodeType.FOLDER))
      .collect(Collectors.toList());

    List<Node> foldersToCopy = folderChildren
      .stream()
      .filter(node -> node.getNodeType()
        .equals(NodeType.FOLDER))
      .collect(Collectors.toList());

    // Copy each file
    filesToCopy.forEach(file -> copyFile(file, destinationFolder, requesterId, Optional.empty()));

    // Copy recursively each folder
    foldersToCopy.forEach(folderToCopy -> {
      Node copiedFolder = copyFolder(folderToCopy, destinationFolder, requesterId,
        Optional.empty());
      copyFolderCascade(folderToCopy.getId(), copiedFolder, requesterId, Optional.empty());
    });
  }

  private Node copyFolder(
    Node sourceFolder,
    Node destinationFolder,
    String requesterId,
    Optional<String> newName
  ) {
    String effectiveName = newName.orElse(sourceFolder.getName());

    String ownerId = (
      destinationFolder.getNodeType().equals(NodeType.ROOT)
        || requesterId.equals(destinationFolder.getOwnerId())
    )
      ? requesterId
      : destinationFolder.getOwnerId();

    // Create the new folder
    return nodeRepository.createNewNode(
      UUID.randomUUID()
        .toString(),
      requesterId,
      ownerId,
      destinationFolder.getId(),
      effectiveName,
      sourceFolder.getDescription()
        .orElse(""),
      NodeType.FOLDER,
      NodeType.ROOT.equals(destinationFolder.getNodeType())
        ? destinationFolder.getId()
        : destinationFolder.getAncestorIds() + "," + destinationFolder.getId(),
      0L
    );
  }

  private void createIndirectShare(
    String sharedParentId,
    Node nodeToShare
  ) {
    shareRepository.getShares(sharedParentId, Collections.emptyList())
      .forEach(share -> {
        shareRepository.upsertShare(
          nodeToShare.getId(),
          share.getTargetUserId(),
          share.getPermissions(),
          false,
          false,
          share.getExpiredAt()
        );

        if (nodeToShare.getNodeType()
          .equals(NodeType.FOLDER)) {
          shareDataFetcher.cascadeUpsertShare(
            nodeToShare.getId(),
            share.getTargetUserId(),
            share.getPermissions(),
            share.getExpiredAt());
        }
      });
  }

  /**
   * @param filename
   * @param destinationFolderId the id of the destination folder
   * @param nodeOwner
   *
   * @return the alternative name
   */
  private String searchAlternativeName(
    String filename,
    String destinationFolderId,
    String nodeOwner
  ) {
    int level = 1;
    String finalFilename = filename;

    while (nodeRepository
      .getNodeByName(finalFilename, destinationFolderId, nodeOwner)
      .isPresent()
    ) {
      int dotPosition = filename.lastIndexOf('.');

      finalFilename = (dotPosition != -1)
        ? filename.substring(0, dotPosition) + " (" + level + ")" + filename.substring(dotPosition)
        : filename + " (" + level + ")";

      ++level;
    }

    return finalFilename;
  }

  /**
   * <p>This {@link DataFetcher} copy one or more nodes into a destination folder. This is bound to
   * the {@link Files.GraphQL.Mutations#COPY_NODES} mutation.</p>
   * <p>The requester must specify the following input parameters:</p>
   * <ul>
   *   <li>{@link Files.GraphQL.InputParameters.CopyNodes#NODE_IDS}</li> containing a list of nodes id to copy.</li
   *   <li>{@link Files.GraphQL.InputParameters.CopyNodes#DESTINATION_ID}</li> containing the folder id where every node
   *   is copied.</li>
   * </ul>
   * <p>The requester must have the {@link SharePermission#READ_AND_WRITE} permission on the destination folder.</p>
   *
   * @return an asynchronous {@link DataFetcher} containing a {@link List} of all the copied nodes.
   */
  public DataFetcher<CompletableFuture<List<DataFetcherResult<Map<String, Object>>>>> copyNodesFetcher() {
    return environment -> CompletableFuture.supplyAsync(() -> {
      ResultPath resultPath = environment.getExecutionStepInfo().getPath();
      String requesterId = ((User) environment
        .getGraphQlContext()
        .get(Files.GraphQL.Context.REQUESTER)).getId();
      List<String> nodeIds = environment.getArgument(InputParameters.MoveNodes.NODE_IDS);
      String destinationFolderId = environment.getArgument(
        InputParameters.MoveNodes.DESTINATION_ID);

      if (permissionsChecker.getPermissions(destinationFolderId, requesterId)
        .has(SharePermission.READ_AND_WRITE)) {

        Optional<Node> optDestinationFolder = nodeRepository.getNode(destinationFolderId);

        if (optDestinationFolder.isPresent() &&
          (optDestinationFolder.get()
            .getNodeType()
            .equals(NodeType.FOLDER)
            || optDestinationFolder.get()
            .getNodeType()
            .equals(NodeType.ROOT))
        ) {

          List<DataFetcherResult<Map<String, Object>>> copiedNodesResult = new ArrayList<>();
          AtomicReference<List<DataFetcherResult<Map<String, Object>>>> errorsOfNodesWithoutPermission =
            new AtomicReference();

          List<Node> nodes = nodeRepository.getNodes(nodeIds, Optional.empty())
            .collect(Collectors.toList());
          List<String> targetFolderChildrenFilesName = nodeRepository.getNodes(
              nodeRepository.getChildrenIds(
                destinationFolderId,
                Optional.empty(),
                Optional.empty(),
                false),
              Optional.empty()
            )
            .map(Node::getFullName)
            .collect(Collectors.toList());

          // Handle permissions
          List<Node> nodesToCopy = nodes
            .stream()
            .filter(node ->
              node.getNodeType() != NodeType.FOLDER ||
                (node.getNodeType() == NodeType.FOLDER &&
                  !node.getId()
                    .equals(destinationFolderId) && // Can't copy a folder inside itself
                  (!optDestinationFolder.get()
                    .getAncestorsList()
                    .contains(node.getId()) || // Can't copy a folder inside one of it's child
                    node.getParentId()
                      .equals(destinationFolderId) // Can copy a folder inside its parent
                  )
                )
            )
            .filter(node -> permissionsChecker.getPermissions(node.getId(), requesterId)
              .canRead())
            .collect(Collectors.toList());

          errorsOfNodesWithoutPermission.set(
            nodes
              .stream()
              .filter(node -> !nodesToCopy.contains(node))
              .map(node -> new Builder<Map<String, Object>>()
                .error(GraphQLResultErrors.nodeWriteError(node.getId(), resultPath))
                .build()
              )
              .collect(Collectors.toList())
          );

          if (!nodesToCopy.isEmpty()) {
            nodesToCopy
              .stream()
              .filter(node -> targetFolderChildrenFilesName.contains(node.getFullName()))
              .forEach(nodeDup -> {
                String newName = searchAlternativeName(
                  nodeDup.getFullName(), destinationFolderId, nodeDup.getOwnerId()
                );
                if (nodeDup.getNodeType() == NodeType.FOLDER) {
                  Node copiedFolder = copyFolder(
                    nodeDup, optDestinationFolder.get(), requesterId, Optional.of(newName)
                  );
                  copiedNodesResult.add(
                    convertNodeToDataFetcherResult(copiedFolder, requesterId, resultPath)
                  );
                  copyFolderCascade(nodeDup.getId(), copiedFolder, requesterId,
                    Optional.of(newName));
                  createIndirectShare(destinationFolderId, copiedFolder);
                } else {
                  Optional<Node> optCopiedFile = copyFile(
                    nodeDup,
                    optDestinationFolder.get(),
                    requesterId,
                    Optional.of(newName)
                  );

                  if (optCopiedFile.isPresent()) {
                    copiedNodesResult.add(
                      convertNodeToDataFetcherResult(optCopiedFile.get(), requesterId, resultPath)
                    );
                    createIndirectShare(destinationFolderId, optCopiedFile.get());
                  } else {
                    List<DataFetcherResult<Map<String, Object>>> errors =
                      errorsOfNodesWithoutPermission.get();

                    errors.add(DataFetcherResult
                      .<Map<String, Object>>newResult()
                      .error(GraphQLResultErrors.nodeCopyError(
                        nodeDup.getId(),
                        nodeDup.getCurrentVersion(),
                        resultPath
                      ))
                      .build()
                    );

                    errorsOfNodesWithoutPermission.set(errors);
                  }
                }
              });

            nodesToCopy
              .stream()
              .filter(node -> !targetFolderChildrenFilesName.contains(node.getFullName()))
              .forEach(node -> {
                if (node.getNodeType() == NodeType.FOLDER) {
                  Node copiedFolder = copyFolder(node, optDestinationFolder.get(), requesterId,
                    Optional.empty());
                  copiedNodesResult.add(
                    convertNodeToDataFetcherResult(copiedFolder, requesterId, resultPath));
                  copyFolderCascade(node.getId(), copiedFolder, requesterId, Optional.empty());
                  createIndirectShare(destinationFolderId, copiedFolder);
                } else {
                  Optional<Node> optCopiedFile = copyFile(
                    node,
                    optDestinationFolder.get(),
                    requesterId,
                    Optional.empty()
                  );

                  if (optCopiedFile.isPresent()) {
                    copiedNodesResult.add(
                      convertNodeToDataFetcherResult(optCopiedFile.get(), requesterId, resultPath)
                    );
                    createIndirectShare(destinationFolderId, optCopiedFile.get());
                  } else {
                    List<DataFetcherResult<Map<String, Object>>> errors =
                      errorsOfNodesWithoutPermission.get();

                    errors.add(DataFetcherResult
                      .<Map<String, Object>>newResult()
                      .error(GraphQLResultErrors.nodeCopyError(
                        node.getId(),
                        node.getCurrentVersion(),
                        resultPath
                      ))
                      .build()
                    );

                    errorsOfNodesWithoutPermission.set(errors);
                  }
                }
              });
          }
          copiedNodesResult.addAll(errorsOfNodesWithoutPermission.get());
          return copiedNodesResult;
        }
      }

      return Collections.singletonList(new Builder<Map<String, Object>>()
        .error(GraphQLResultErrors.nodeWriteError(destinationFolderId, resultPath))
        .build());
    });
  }

  public DataFetcher<CompletableFuture<List<DataFetcherResult<Map<String, Object>>>>> getVersionsFetcher() {
    return environment -> CompletableFuture.supplyAsync(() -> {
      ResultPath path = environment.getExecutionStepInfo()
        .getPath();
      String requesterId = ((User) environment.getGraphQlContext()
        .get(Files.GraphQL.Context.REQUESTER)).getId();
      String nodeId = environment.getArgument(GetVersions.NODE_ID);
      Optional<List<Integer>> optVersions = Optional.ofNullable(
        environment.getArgument(GetVersions.VERSIONS));

      if (permissionsChecker.getPermissions(nodeId, requesterId)
        .has(SharePermission.READ_ONLY)) {
        List<DataFetcherResult<Map<String, Object>>> results = new ArrayList<>();

        optVersions
          .orElseGet(() -> {
            List<Integer> versions = new ArrayList<>();
            fileVersionRepository
              .getFileVersions(nodeId)
              .forEach(fileVersion -> versions.add(fileVersion.getVersion()));
            return versions;
          })
          .forEach(version -> results.add(
            convertNodeToDataFetcherResult(
              nodeRepository.getNode(nodeId).get(),
              version,
              requesterId,
              path)
          ));

        return results;
      }

      return Collections.singletonList(new Builder<Map<String, Object>>()
        .error(GraphQLResultErrors.nodeWriteError(nodeId, path))
        .build());
    });
  }

  public DataFetcher<CompletableFuture<DataFetcherResult<List<Integer>>>> deleteVersionsFetcher() {
    return environment -> CompletableFuture.supplyAsync(() -> {
      ResultPath path = environment.getExecutionStepInfo()
        .getPath();
      String requesterId = ((User) environment.getGraphQlContext()
        .get(Files.GraphQL.Context.REQUESTER)).getId();
      String nodeId = environment.getArgument(GetVersions.NODE_ID);
      Optional<List<Integer>> optVersionsToDelete = Optional.ofNullable(
        environment.getArgument(GetVersions.VERSIONS));

      if (permissionsChecker.getPermissions(nodeId, requesterId)
        .has(SharePermission.READ_AND_WRITE)) {
        Node node = nodeRepository.getNode(nodeId)
          .get();

        List<FileVersion> fileVersionsToDelete = optVersionsToDelete
          .map(versions -> fileVersionRepository.getFileVersions(nodeId, versions))
          .orElseGet(() -> fileVersionRepository.getFileVersions(nodeId))
          .stream()
          .filter(fileVersion -> !fileVersion.isKeptForever())
          .collect(Collectors.toList());

        List<Integer> versionsToDelete = fileVersionsToDelete
          .stream()
          .map(FileVersion::getVersion)
          .filter(version -> !node.getCurrentVersion()
            .equals(version))
          .collect(Collectors.toList());

        fileVersionRepository.deleteFileVersions(nodeId, versionsToDelete);
        tombstoneRepository.createTombstonesBulk(fileVersionsToDelete, node.getOwnerId());

        List<GraphQLError> undeletedVersionErrors = optVersionsToDelete
          .map(versions -> versions
            .stream()
            .filter(version -> !versionsToDelete.contains(version))
            .map(version -> GraphQLResultErrors.fileVersionNotFound(nodeId, version, path))
            .collect(Collectors.toList())
          )
          .orElse(Collections.emptyList());

        return new Builder<List<Integer>>()
          .data(versionsToDelete)
          .errors(undeletedVersionErrors)
          .build();
      }

      return new Builder<List<Integer>>()
        .error(GraphQLResultErrors.nodeWriteError(nodeId, path))
        .build();
    });
  }

  public DataFetcher<CompletableFuture<DataFetcherResult<List<Integer>>>> keepVersionsFetcher() {
    return environment -> CompletableFuture.supplyAsync(() -> {
      ResultPath path = environment.getExecutionStepInfo()
        .getPath();
      String requesterId = ((User) environment.getGraphQlContext()
        .get(Files.GraphQL.Context.REQUESTER)).getId();
      String nodeId = environment.getArgument(GetVersions.NODE_ID);
      List<Integer> versionsToKeepForever = environment.getArgument(GetVersions.VERSIONS);
      Boolean keepForever = environment.getArgument(KeepVersions.KEEP_FOREVER);

      if (permissionsChecker.getPermissions(nodeId, requesterId)
        .has(SharePermission.READ_AND_WRITE)) {

        List<FileVersion> listOfFileVersions = fileVersionRepository.getFileVersions(nodeId);
        int keepForeverCounter = 0;
        for (FileVersion version : listOfFileVersions) {
          keepForeverCounter = version.isKeptForever()
            ? keepForeverCounter + 1
            : keepForeverCounter;
        }

        List<FileVersion> fileVersions = fileVersionRepository.getFileVersions(nodeId,
          versionsToKeepForever);
        // Make update in batch
        List<FileVersion> fileVersionsNotUpdated = new Vector<>();
        for (FileVersion version : fileVersions) {
          if (!keepForever || keepForeverCounter < maxNumberOfKeepVersions) {
            version.keepForever(keepForever);
            fileVersionRepository.updateFileVersion(version);
            keepForeverCounter = keepForever
              ? keepForeverCounter + 1
              : keepForeverCounter - 1;
          } else {
            fileVersionsNotUpdated.add(version);
          }
        }

        List<Integer> versionsUpdated = fileVersions
          .stream()
          .filter(version -> version.isKeptForever() == keepForever)
          .map(FileVersion::getVersion)
          .collect(Collectors.toList());

        List<GraphQLError> versionsNotUpdated = fileVersionsNotUpdated
          .stream()
          .map(version -> GraphQLResultErrors.tooManyVersionsError(nodeId, path))
          .collect(Collectors.toList());

        versionsNotUpdated.addAll(
          fileVersions
            .stream()
            .filter(versionsNotUpdated::contains)
            .filter(versionsUpdated::contains)
            .map(version -> GraphQLResultErrors.fileVersionNotFound(nodeId, version.getVersion(),
              path))
            .collect(Collectors.toList())
        );

        logger.debug(MessageFormat.format(
          "Keep version operation completed with success on: {0} and failure on {1}",
          versionsUpdated,
          versionsNotUpdated
        ));

        return new Builder<List<Integer>>()
          .data(versionsUpdated)
          .errors(versionsNotUpdated)
          .build();
      }

      return new Builder<List<Integer>>()
        .error(GraphQLResultErrors.nodeWriteError(nodeId, path))
        .build();
    });
  }

  public DataFetcher<CompletableFuture<DataFetcherResult<Map<String, Object>>>> cloneVersionFetcher() {
    return environment -> CompletableFuture.supplyAsync(() -> {
      ResultPath path = environment.getExecutionStepInfo().getPath();
      String requesterId = ((User) environment
        .getGraphQlContext()
        .get(Files.GraphQL.Context.REQUESTER))
        .getId();
      String nodeId = environment.getArgument(Files.GraphQL.InputParameters.CloneVersion.NODE_ID);
      Integer versionToClone = environment.getArgument(
        Files.GraphQL.InputParameters.CloneVersion.VERSION
      );

      if (permissionsChecker
        .getPermissions(nodeId, requesterId)
        .has(SharePermission.READ_AND_WRITE)
      ) {
        Node node = nodeRepository.getNode(nodeId).get();
        if (fileVersionRepository.getFileVersions(nodeId).size() >= maxNumberOfVersions) {
          logger.debug(MessageFormat.format(
            "Node: {0} has reached max number of versions ({1}), cannot add more versions",
            nodeId,
            maxNumberOfVersions
          ));
          return new Builder<Map<String, Object>>()
            .error(GraphQLResultErrors.tooManyVersionsError(nodeId, path))
            .build();
        }
        logger.debug(MessageFormat.format(
          "Version to clone {0}, id fetched to clone {1}, node current version {2}",
          versionToClone,
          fileVersionRepository.getFileVersion(nodeId, versionToClone).get().getNodeId(),
          node.getCurrentVersion()
        ));

        return fileVersionRepository
          .getFileVersion(nodeId, versionToClone)
          .map(fileVersion -> {
            Integer newVersion = node.getCurrentVersion() + 1;

            return Try
              .of(() -> filesConfig
                .getFileStoreClient()
                .copy(
                  FilesIdentifier.of(node.getId(), node.getCurrentVersion(), requesterId),
                  FilesIdentifier.of(node.getId(), newVersion, requesterId),
                  false
                )
              )
              .onSuccess(copiedBlobResponse -> {
                Optional<FileVersion> newFileVersion = fileVersionRepository.createNewFileVersion(
                  node.getId(),
                  requesterId,
                  newVersion,
                  fileVersion.getMimeType(),
                  copiedBlobResponse.getSize(),
                  copiedBlobResponse.getDigest(),
                  false
                );

                node.setCurrentVersion(newVersion);
                nodeRepository.updateNode(node);

                newFileVersion.get().setClonedFromVersion(versionToClone);
                fileVersionRepository.updateFileVersion(newFileVersion.get());

                logger.debug(MessageFormat.format(
                  "Copy operation concluded successfully with file {1} and version {2}",
                  nodeId,
                  newVersion
                ));
              })
              .onFailure(failure -> {
                String error = MessageFormat.format(
                  "Copy error with nodeId: {0} and version {1}",
                  nodeId,
                  versionToClone
                );
                logger.error(error);
                throw new AbortExecutionException(error);
              })
              .transform(copiedBlobResponse ->
                convertNodeToDataFetcherResult(
                  nodeRepository.getNode(node.getId()).get(),
                  newVersion,
                  requesterId,
                  path
                )
              );
          })
          .orElse(
            new Builder<Map<String, Object>>()
              .error(GraphQLResultErrors.fileVersionNotFound(nodeId, versionToClone, path))
              .build()
          );
      }

      return new Builder<Map<String, Object>>()
        .error(GraphQLResultErrors.nodeWriteError(nodeId, path))
        .build();
    });
  }
}
