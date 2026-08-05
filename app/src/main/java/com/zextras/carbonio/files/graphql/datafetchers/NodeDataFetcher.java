// SPDX-FileCopyrightText: 2022 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.graphql.datafetchers;
import com.zextras.carbonio.files.graphql.SyncCompletableFuture;

import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import com.zextras.carbonio.files.Constants;
import com.zextras.carbonio.files.Constants.Db.RootId;
import com.zextras.carbonio.files.Constants.GraphQL;
import com.zextras.carbonio.files.Constants.GraphQL.Context;
import com.zextras.carbonio.files.Constants.GraphQL.InputParameters;
import com.zextras.carbonio.files.Constants.GraphQL.InputParameters.FlagNodes;
import com.zextras.carbonio.files.Constants.GraphQL.InputParameters.GetVersions;
import com.zextras.carbonio.files.Constants.GraphQL.InputParameters.KeepVersions;
import com.zextras.carbonio.files.Constants.GraphQL.InputParameters.RestoreNodes;
import com.zextras.carbonio.files.Constants.GraphQL.NodePage;
import com.zextras.carbonio.files.Constants.ServiceDiscover.Config;
import com.zextras.carbonio.files.config.FilesConfig;
import com.zextras.carbonio.files.dal.dao.ebean.ACL;
import com.zextras.carbonio.files.dal.dao.ebean.ACL.SharePermission;
import com.zextras.carbonio.files.dal.dao.ebean.FileVersion;
import com.zextras.carbonio.files.dal.dao.ebean.Node;
import com.zextras.carbonio.files.dal.dao.ebean.NodeCustomAttributes;
import com.zextras.carbonio.files.dal.dao.ebean.NodeType;
import com.zextras.carbonio.files.dal.dao.ebean.Share;
import com.zextras.carbonio.files.dal.dao.ebean.TrashedNode;
import com.zextras.carbonio.files.dal.repositories.impl.ebean.utilities.AddedNodeType;
import com.zextras.carbonio.files.dal.repositories.impl.ebean.utilities.FileVersionSort;
import com.zextras.carbonio.files.dal.repositories.impl.ebean.utilities.NodeSort;
import com.zextras.carbonio.files.dal.repositories.impl.ebean.utilities.RemovedNodeType;
import com.zextras.carbonio.files.dal.repositories.interfaces.FileVersionRepository;
import com.zextras.carbonio.files.dal.repositories.interfaces.NodeRepository;
import com.zextras.carbonio.files.dal.repositories.interfaces.NotificationRepository;
import com.zextras.carbonio.files.dal.repositories.interfaces.ShareRepository;
import com.zextras.carbonio.files.dal.repositories.interfaces.TombstoneRepository;
import com.zextras.carbonio.files.graphql.errors.CopyFailureClassifier;
import com.zextras.carbonio.files.graphql.errors.GraphQLResultErrors;
import com.zextras.carbonio.files.graphql.types.Permissions;
import com.zextras.carbonio.files.utilities.PermissionsChecker;
import com.zextras.carbonio.files.dal.dao.UserMyself;
import com.zextras.filestore.api.Filestore;
import com.zextras.filestore.model.BulkDeleteRequestItem;
import com.zextras.filestore.model.BulkDeleteResponseItem;
import com.zextras.filestore.model.FilesIdentifier;
import com.zextras.filestore.model.IdentifierType;
import graphql.GraphQLError;
import graphql.execution.AbortExecutionException;
import graphql.execution.DataFetcherResult;
import graphql.execution.DataFetcherResult.Builder;
import graphql.execution.ResultPath;
import graphql.schema.DataFetcher;
import graphql.schema.GraphQLObjectType;
import graphql.schema.TypeResolver;
import graphql.schema.idl.EnumValuesProvider;
import io.vavr.control.Try;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
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

import static com.zextras.carbonio.files.utilities.RenameNodeUtils.searchAlternativeName;

/**
 * Contains all the implementations of the {@link DataFetcher}s for all the queries and mutations
 * related to the Node, File and Folder types defined in the GraphQL schema.
 * <p>
 * The implementation of each {@link DataFetcher} is asynchronous and returns an {@link HashMap}
 * containing the data fetched from the database. Each key of the resulting map must match the
 * attribute of the related type defined in the GraphQL schema.
 * <p>
 * These {@link DataFetcher}s will be used in the GraphQLProvider (wired in a later phase) where they are bound with
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
@ApplicationScoped
public class NodeDataFetcher {

  private static final Logger logger =
    LoggerFactory.getLogger(NodeDataFetcher.class);

  private final NodeRepository        nodeRepository;
  private final NotificationRepository notificationRepository;
  private final FileVersionRepository fileVersionRepository;
  private final PermissionsChecker    permissionsChecker;
  private final ShareRepository       shareRepository;
  private final ShareDataFetcher      shareDataFetcher;
  private final FilesConfig           filesConfig;
  private final Filestore             fileStore;
  private final TombstoneRepository   tombstoneRepository;
  private final CopyFailureClassifier copyFailureClassifier;

  @Inject
  NodeDataFetcher(
    NodeRepository nodeRepository,
    NotificationRepository notificationRepository,
    FileVersionRepository fileVersionRepository,
    PermissionsChecker permissionsChecker,
    ShareRepository shareRepository,
    ShareDataFetcher shareDataFetcher,
    FilesConfig filesConfig,
    Filestore fileStore,
    TombstoneRepository tombstoneRepository,
    CopyFailureClassifier copyFailureClassifier
  ) {
    this.nodeRepository = nodeRepository;
    this.notificationRepository = notificationRepository;
    this.fileVersionRepository = fileVersionRepository;
    this.shareRepository = shareRepository;
    this.permissionsChecker = permissionsChecker;
    this.shareDataFetcher = shareDataFetcher;
    this.filesConfig = filesConfig;
    this.fileStore = fileStore;
    this.tombstoneRepository = tombstoneRepository;
    this.copyFailureClassifier = copyFailureClassifier;
  }

  /**
   * Live per-operation read of the max-number-of-versions cap. Must NOT be snapshotted into a
   * field at construction time: this bean is {@code @ApplicationScoped} (built once), while the
   * underlying config value can change at runtime (Consul KV override, or acceptance-seam
   * overrides via {@code TestFilesConfig}).
   */
  private int getMaxNumberOfVersions() {
    return filesConfig.getMaxNumberOfVersions();
  }

  /**
   * Live per-operation read of the max-number-of-kept-forever-versions cap, derived from the
   * current {@link #getMaxNumberOfVersions()}. See {@link #getMaxNumberOfVersions()} for why this
   * cannot be a construction-time snapshot either.
   */
  private int getMaxNumberOfKeepVersions() {
    int maxNumberOfVersions = getMaxNumberOfVersions();
    return maxNumberOfVersions <= Config.DIFF_MAX_VERSION_AND_MAX_KEEP_VERSION
      ? 0
      : maxNumberOfVersions - Config.DIFF_MAX_VERSION_AND_MAX_KEEP_VERSION;
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

    result.put(Constants.GraphQL.Node.ID, node.getId());
    result.put(Constants.GraphQL.Node.CREATED_AT, node.getCreatedAt());
    result.put(Constants.GraphQL.Node.UPDATED_AT, node.getUpdatedAt());
    result.put(Constants.GraphQL.Node.NAME, node.getName());
    result.put(Constants.GraphQL.Node.TYPE, node.getNodeType().name());

    result.put(
      Constants.GraphQL.Node.ROOT_ID,
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
      .ifPresent(description -> result.put(Constants.GraphQL.Node.DESCRIPTION, description));

    node
      .getParentId()
      .ifPresent(parentId -> nodeContext.put(Constants.GraphQL.Node.PARENT, parentId));

    if (!node.getNodeType().equals(NodeType.FOLDER) && !node.getNodeType().equals(NodeType.ROOT)) {
      node
        .getExtension()
        .ifPresent(extension -> result.put(Constants.GraphQL.Node.EXTENSION, extension));

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

    nodeContext.put(Constants.GraphQL.Node.OWNER, node.getOwnerId());
    nodeContext.put(Constants.GraphQL.Node.CREATOR, node.getCreatorId());
    nodeContext.put(Constants.GraphQL.Node.ID, node.getId());

    // TODO Move up when the last_editor coherent between node and file version will be coherent
    Optional
      .ofNullable((String) result.get(GraphQL.Node.LAST_EDITOR))
      .ifPresent(lastEditorId -> nodeContext.put(Constants.GraphQL.Node.LAST_EDITOR, lastEditorId));

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
    fileVersionMap.put(Constants.GraphQL.FileVersion.UPDATED_AT, fileVersion.getUpdatedAt());
    fileVersionMap.put(GraphQL.FileVersion.LAST_EDITOR, fileVersion.getLastEditorId());
    fileVersionMap.put(Constants.GraphQL.FileVersion.VERSION, fileVersion.getVersion());
    fileVersionMap.put(Constants.GraphQL.FileVersion.MIME_TYPE, fileVersion.getMimeType());
    fileVersionMap.put(Constants.GraphQL.FileVersion.SIZE, fileVersion.getSize());
    fileVersionMap.put(Constants.GraphQL.FileVersion.KEEP_FOREVER, fileVersion.isKeptForever());
    fileVersionMap.put(Constants.GraphQL.FileVersion.DIGEST, fileVersion.getDigest());
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
            if (fieldName.equals(Constants.GraphQL.Node.PARENT)) {
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
                ((UserMyself) environment.getGraphQlContext().get(Context.REQUESTER)).getId().getUserId();

              Integer version = Optional
                .ofNullable((Integer) environment.getArgument(Constants.GraphQL.FileVersion.VERSION))
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
        .orElse(SyncCompletableFuture.supplyAsync(() ->
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
      return (result.get(Constants.GraphQL.Node.TYPE).equals(NodeType.FOLDER.toString())
        || result.get(Constants.GraphQL.Node.TYPE).equals(NodeType.ROOT.toString())
      )
        ? (GraphQLObjectType) environment.getSchema().getType(Constants.GraphQL.Types.FOLDER)
        : (GraphQLObjectType) environment.getSchema().getType(Constants.GraphQL.Types.FILE);
    };
  }

  public EnumValuesProvider getNodeSortResolver() {
    return NodeSort::valueOf;
  }

  public EnumValuesProvider getNodeTypeResolver() {
    return NodeType::valueOf;
  }

  public DataFetcher<CompletableFuture<DataFetcherResult<Map<String, String>>>> getChildNodesFetcherFast() {
    return environment -> SyncCompletableFuture.supplyAsync(() -> {
        Map<String, Object> partialResult = environment.getSource();
        String requesterId = ((UserMyself) environment.getGraphQlContext()
          .get(Constants.GraphQL.Context.REQUESTER)).getId().getUserId();
        String folderNodeId = (String) partialResult.get(Constants.GraphQL.Node.ID);
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
          environment.getArgument(Constants.GraphQL.InputParameters.PAGE_TOKEN)
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
   * Reusable core of the {@code createFolder} mutation: enforces the READ_AND_WRITE permission on
   * the parent, requires the parent to be a {@link NodeType#FOLDER} or {@link NodeType#ROOT},
   * computes the new folder's owner (the requester if the parent is a ROOT or the requester
   * already owns the parent, otherwise the parent's owner), de-duplicates the requested name
   * against existing siblings, creates the folder, propagates inherited shares onto it, and fires
   * the added-node notification (gated on {@link FilesConfig#areNotificationsEnabled()}). Shared
   * verbatim by the GraphQL {@link #createFolderFetcher()} DataFetcher and (in a later phase) the
   * {@code /internal/folders} REST endpoint, so the folder-creation business logic lives in one
   * place.
   *
   * @param requesterId the id of the user creating the folder
   * @param parentId the id of the parent folder (or root) under which to create the new folder
   * @param name the requested name of the new folder (leading/trailing whitespace is trimmed; a
   *     numeric suffix is appended if a sibling with the same name already exists)
   * @param requester the full {@link UserMyself} of the requester, used to attribute the
   *     added-node notification
   * @return the created {@link Node} (of type {@link NodeType#FOLDER})
   * @throws NodeAccessException if the requester lacks READ_AND_WRITE on the parent
   * @throws NodeNotFoundException if the parent does not exist, or is neither a FOLDER nor a ROOT
   */
  public Node createFolder(
    String requesterId,
    String parentId,
    String name,
    UserMyself requester
  ) {
    if (!permissionsChecker
      .getPermissions(parentId, requesterId)
      .has(SharePermission.READ_AND_WRITE)
    ) {
      throw new NodeAccessException(parentId);
    }

    Node parent = nodeRepository
      .getNode(parentId)
      .filter(p -> NodeType.FOLDER.equals(p.getNodeType())
        || NodeType.ROOT.equals(p.getNodeType())
      )
      .orElseThrow(() -> new NodeNotFoundException(parentId));

    String ownerId = (
      NodeType.ROOT.equals(parent.getNodeType())
        || requesterId.equals(parent.getOwnerId())
    )
      ? requesterId
      : parent.getOwnerId();

    String folderName = searchAlternativeName(
      nodeRepository,
      name.trim(),
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
    List<String> usersToNotify = createIndirectShare(parentId, createdFolder);
    usersToNotify.remove(requesterId); // Remove requesterId from the list if present

    // If the requester is the owner of the parent folder, do not notify him since he did the upload himself
    // Also exclude uploads on root, since root can't be shared and does not have an owner
    if (!parent.getNodeType().equals(NodeType.ROOT) &&
        !requesterId.equals(parent.getOwnerId()) &&
        !usersToNotify.contains(parent.getOwnerId())) {
      usersToNotify.add(parent.getOwnerId());
    }

    if (!usersToNotify.isEmpty() && filesConfig.areNotificationsEnabled())
      notificationRepository.createAddedNodeNotification(
        createdFolder,
        parent,
        requester,
        AddedNodeType.CREATE,
        usersToNotify
      );

    return createdFolder;
  }

  /**
   * Thrown by {@link #createFolder} when the requester lacks the READ_AND_WRITE permission on the
   * parent (mirrors the GraphQL {@code nodeWriteError}).
   */
  public static class NodeAccessException extends RuntimeException {
    public NodeAccessException(String parentId) {
      super("Cannot create folder under node " + parentId
        + ": missing READ_AND_WRITE permission");
    }
  }

  /**
   * Thrown by {@link #createFolder} when the parent does not exist, or is neither a {@link
   * NodeType#FOLDER} nor a {@link NodeType#ROOT} (mirrors the GraphQL {@code nodeNotFound}).
   */
  public static class NodeNotFoundException extends RuntimeException {
    public NodeNotFoundException(String parentId) {
      super("Cannot create folder: parent node " + parentId
        + " not found, or not a folder/root");
    }
  }

  /**
   * <p>This {@link DataFetcher} must be used for the {@link Constants.GraphQL.Mutations#CREATE_FOLDER}
   * mutation.</p>
   * <p>The request must have the following parameters in input:</p>
   * <ul>
   *   <li>
   *     {@link Constants.GraphQL.InputParameters.CreateFolder#PARENT_ID}: a {@link String} representing the id of the
   *     parent folder for the new folder that needs to be created
   *   </li>
   *   <li>
   *     {@link Constants.GraphQL.InputParameters.CreateFolder#NAME}: a {@link String} representing the name of the
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
    return (environment) -> SyncCompletableFuture.supplyAsync(() -> {
        ResultPath resultPath = environment.getExecutionStepInfo().getPath();
        String parentId = environment.getArgument(InputParameters.CreateFolder.PARENT_ID);
        UserMyself requester = (UserMyself) environment
          .getGraphQlContext()
          .get(Constants.GraphQL.Context.REQUESTER);
        String requesterId = requester.getId().getUserId();
        String name = environment.getArgument(InputParameters.CreateFolder.NAME);

        try {
          Node createdFolder = createFolder(requesterId, parentId, name, requester);
          return convertNodeToDataFetcherResult(
            createdFolder,
            requesterId,
            resultPath
          );
        } catch (NodeNotFoundException e) {
          return new DataFetcherResult
            .Builder<Map<String, Object>>()
            .error(GraphQLResultErrors.nodeNotFound(parentId.trim(), resultPath))
            .build();
        } catch (NodeAccessException e) {
          return new DataFetcherResult
            .Builder<Map<String, Object>>()
            .error(GraphQLResultErrors.nodeWriteError(parentId.trim(), resultPath))
            .build();
        }
      }
    );
  }

  /**
   * <p>This {@link DataFetcher} must be used to fetch the permissions of the requester {@link } on
   * the specified node. It works only if the previous data fetcher creates a
   * {@link Constants.GraphQL.Types#NODE_INTERFACE} and if it is bound to resolve attributes that have
   * type {@link Constants.GraphQL.Types#PERMISSIONS}.</p>
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
    return environment -> SyncCompletableFuture.supplyAsync(() -> {
      Map<String, Object> partialResult = environment.getSource();
      String requesterId = ((UserMyself) environment.getGraphQlContext()
        .get(Constants.GraphQL.Context.REQUESTER)).getId().getUserId();
      String nodeId = (String) partialResult.get(Constants.GraphQL.Node.ID);

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
   * <p>This {@link DataFetcher} must be used for the {@link Constants.GraphQL.Mutations#UPDATE_NODE}
   * mutation or when it is necessary to update an existing node.</p>
   * <p>The request must have the following parameters in input:</p>
   * <ul>
   *  <li>
   *    {@link Constants.GraphQL.InputParameters.UpdateNode#NODE_ID}: a {@link String} representing the id of the node to
   *    update (this is mandatory).
   *  </li>
   *  <li>
   *    {@link Constants.GraphQL.InputParameters.UpdateNode#NAME}: a {@link String} representing the new name of the node
   *  </li>
   *  <li>
   *    {@link Constants.GraphQL.InputParameters.UpdateNode#DESCRIPTION}: a {@link String} representing the new description
   *    of the node
   *  </li>
   *  <li>
   *    {@link Constants.GraphQL.InputParameters.UpdateNode#FLAGGED}: a {@link boolean} to flag or un-flag the node
   *  </li>
   *  <li>
   *    {@link Constants.GraphQL.InputParameters.UpdateNode#MARKED_FOR_DELETION}: a {@link boolean} to marked or un-mark for
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
    return (environment -> SyncCompletableFuture.supplyAsync(() -> {
      ResultPath path = environment.getExecutionStepInfo()
        .getPath();
      String requesterId = ((UserMyself) environment.getGraphQlContext()
        .get(Constants.GraphQL.Context.REQUESTER)).getId().getUserId();
      String nodeId = environment.getArgument(Constants.GraphQL.InputParameters.UpdateNode.NODE_ID);

      if (permissionsChecker.getPermissions(nodeId, requesterId)
        .has(SharePermission.READ_AND_WRITE)) {
        Node nodeToUpdate = nodeRepository.getNode(nodeId)
          .get();
        String parentFolderId = nodeToUpdate.getParentId()
          .orElse(RootId.LOCAL_ROOT);
        Optional<String> optName = Optional.ofNullable(
          environment.getArgument(Constants.GraphQL.InputParameters.UpdateNode.NAME)
        );
        Optional<String> optDescription = Optional.ofNullable(
          environment.getArgument(Constants.GraphQL.InputParameters.UpdateNode.DESCRIPTION)
        );
        Optional<Boolean> optFlagged = Optional.ofNullable(
          environment.getArgument(Constants.GraphQL.InputParameters.UpdateNode.FLAGGED)
        );

        if (optName.isPresent()) {
          // In input the system receives only the name without its extension.
          // Here we create the fullName to check if there are duplicates in the destination folder
          String nodeFullName = nodeToUpdate
            .getExtension()
            .map(extension -> optName.get() + "." + extension)
            .orElse(optName.get());

          if (searchAlternativeName(
                nodeRepository,
                nodeFullName,
                parentFolderId,
                nodeToUpdate.getOwnerId()
              ).equals(nodeFullName)) {
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
        nodeRepository.updateNode(nodeToUpdate);
        // Map a freshly re-fetched, request-scoped managed entity (mirrors moveNodes / getNode).
        // The instance returned by updateNode() is the merge() result of the @Transactional write,
        // which is DETACHED once that transaction commits, so its LAZY fileVersions collection can
        // no longer be initialised while building the GraphQL response.
        return convertNodeToDataFetcherResult(
          nodeRepository.getNode(nodeId).get(),
          requesterId,
          environment.getExecutionStepInfo().getPath()
        );
      }

      return new DataFetcherResult.Builder<Map<String, Object>>()
        .error(GraphQLResultErrors.nodeNotFound(nodeId, path))
        .build();
    }));
  }

  public DataFetcher<CompletableFuture<DataFetcherResult<List<String>>>> flagNodes() {
    return environment -> SyncCompletableFuture.supplyAsync(() -> {
      String requesterId = ((UserMyself) environment.getGraphQlContext()
        .get(Constants.GraphQL.Context.REQUESTER)).getId().getUserId();
      List<String> nodesIds = environment.getArgument(FlagNodes.NODE_IDS);
      boolean starNodes = environment.getArgument(FlagNodes.FLAG);

      List<String> flaggableNodes = nodesIds.stream()
        .filter(nodeId -> {
          Optional<Node> rNode = nodeRepository.getNode(nodeId);
          return rNode.isPresent() && rNode.get().getNodeType() != NodeType.ROOT;
        })
        .filter(nodeId -> permissionsChecker.getPermissions(nodeId, requesterId).has(SharePermission.READ_AND_WRITE))
        .collect(Collectors.toList());

      List<String> nodesInError = nodesIds.stream()
        .filter(nodeId -> !flaggableNodes.contains(nodeId))
        .toList();

      flaggableNodes.forEach(nodeId -> nodeRepository.flagForUser(nodeId, requesterId, starNodes));

      return new DataFetcherResult.Builder<List<String>>()
        .data(flaggableNodes)
        .errors(nodesInError.stream()
          .map(nodeId -> GraphQLResultErrors.nodeWriteError(nodeId,
            environment.getExecutionStepInfo().getPath()))
          .toList())
        .build();
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
    return environment -> SyncCompletableFuture.supplyAsync(() ->
    {
      UserMyself requester = (UserMyself) environment.getGraphQlContext()
        .get(Constants.GraphQL.Context.REQUESTER);
      String requesterId = requester.getId().getUserId();
      List<String> nodesIds = environment.getArgument(
        Constants.GraphQL.InputParameters.TrashNodes.NODE_IDS);

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

            List<String> usersToNotify = new ArrayList<>(
                shareRepository.getSharesUsersIds(trashedNode.getId(), List.of())
            );
            usersToNotify.remove(requesterId);

            // If the requester is the owner of the parent folder, do not notify him since he did the upload himself
            // Also exclude uploads on root, since root can't be shared and does not have an owner
            Node parent = nodeRepository.getNode(trashedNode.getParentId().get()).get();
            if (!parent.getNodeType().equals(NodeType.ROOT) &&
                !requesterId.equals(parent.getOwnerId()) &&
                !usersToNotify.contains(parent.getOwnerId())) {
              usersToNotify.add(parent.getOwnerId());
            }

            if (!usersToNotify.isEmpty() && filesConfig.areNotificationsEnabled())
              notificationRepository.createRemovedNodeNotification(
                trashedNode,
                parent,
                requester,
                RemovedNodeType.DELETE,
                usersToNotify
              );

            trashedNode.setAncestorIds(Constants.Db.RootId.TRASH_ROOT);
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
    return environment -> SyncCompletableFuture.supplyAsync(() ->
    {
      String requesterId = ((UserMyself) environment.getGraphQlContext()
        .get(Constants.GraphQL.Context.REQUESTER)).getId().getUserId();
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
            String newName = searchAlternativeName(
                nodeRepository, node.getFullName(), node.getParentId().get(), node.getOwnerId()
            );
            node.setFullName(newName);
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
   * represents the attribute {@link Constants.GraphQL.Share#NODE} in a GraphQL Share object. It works
   * only if the localContext exists and if the previous data fetcher saves the
   * {@link Constants.GraphQL.InputParameters#NODE_ID} in the context: if one of these pre-conditions
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
              ((UserMyself) environment.getGraphQlContext().get(Context.REQUESTER)).getId().getUserId(),
              environment.getExecutionStepInfo().getPath())
            )
            .exceptionally((e) -> new DataFetcherResult.Builder<Map<String, Object>>().build())
        )
        .orElse(SyncCompletableFuture.supplyAsync(() ->
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
    return environment -> SyncCompletableFuture.supplyAsync(() -> {
      ResultPath path = environment.getExecutionStepInfo().getPath();
      String requesterId = ((UserMyself) environment
        .getGraphQlContext()
        .get(Constants.GraphQL.Context.REQUESTER))
        .getId().getUserId();
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
    return environment -> SyncCompletableFuture.supplyAsync(() -> {

      List<Node> rootList = nodeRepository.getRootsList();

      List<Map<String, Object>> result = new ArrayList<>();

      rootList.forEach(root -> {

        Map<String, Object> mappedRoot = new HashMap<>();
        mappedRoot.put(Constants.GraphQL.Node.ID, root.getId());
        mappedRoot.put(Constants.GraphQL.Node.NAME, root.getName());

        result.add(mappedRoot);

      });

      return new DataFetcherResult.Builder<List<Map<String, Object>>>()
        .data(result)
        .build();
    });
  }

  /**
   * <p>This {@link DataFetcher} must be used for the {@link Constants.GraphQL.Queries#FIND_NODES}
   * query.</p>
   * <p>The request could have the following optional parameters in input:</p>
   * <ul>
   *  <li>
   *    {@link Constants.GraphQL.InputParameters.FindNodes#SORT}: a {@link NodeSort} representing the chosen sort method
   *    for ordering the found nodes.
   *  </li>
   *  <li>
   *    {@link Constants.GraphQL.InputParameters.FindNodes#FLAGGED}: a {@link Boolean} representing the value of the flag
   *    to search.
   *  </li>
   *  <li>
   *    {@link Constants.GraphQL.InputParameters.FindNodes#SHARED_BY_ME}: a {@link Boolean} for searching only nodes
   *    i shared or not shared by me.
   *  </li>
   *  <li>
   *    {@link Constants.GraphQL.InputParameters.FindNodes#SHARED_WITH_ME}: a {@link Boolean} for searching only in nodes
   *    shared with me or not.
   *  </li>
   *  <li>
   *    {@link Constants.GraphQL.InputParameters.FindNodes#SKIP}: an {@link Integer} used for starting search from an
   *    offset rather than from the start of the list.
   *  </li>
   *  <li>
   *    {@link Constants.GraphQL.InputParameters.FindNodes#LIMIT}: a {@link Integer} to limit the number of returned
   *    results.
   *  </li>
   *  <li>
   *    {@link Constants.GraphQL.InputParameters.FindNodes#CURSOR}: a {@link String} containing the page token given
   *    by a previous findNodes call, it's used to keep on the pagination of the dataset, if this is used all other
   *    params will be ignored since all necessary params needed for pagination are saved with the cursor.
   *  </li>
   * </ul>
   *
   * @return an asynchronous {@link DataFetcher} containing the pageToken to use as a cursor for
   * requesting the next page of data.
   */
  public DataFetcher<CompletableFuture<DataFetcherResult<Map<String, String>>>> findNodesFetcher() {
    return environment -> SyncCompletableFuture.supplyAsync(() -> {
      String requesterId = ((UserMyself) environment.getGraphQlContext()
        .get(Constants.GraphQL.Context.REQUESTER)).getId().getUserId();
      Optional<Boolean> optFlagged = Optional.ofNullable(
        environment.getArgument(Constants.GraphQL.InputParameters.FindNodes.FLAGGED)
      );
      Optional<Boolean> optSharedByMe = Optional.ofNullable(
        environment.getArgument(Constants.GraphQL.InputParameters.FindNodes.SHARED_BY_ME)
      );
      Optional<Boolean> optSharedWithMe = Optional.ofNullable(
        environment.getArgument(Constants.GraphQL.InputParameters.FindNodes.SHARED_WITH_ME)
      );
      Optional<Boolean> optDirectShare = Optional.ofNullable(
        environment.getArgument(Constants.GraphQL.InputParameters.FindNodes.DIRECT_SHARE)
      );
      Optional<String> optFolderId = Optional.ofNullable(
        environment.getArgument(Constants.GraphQL.InputParameters.FindNodes.FOLDER_ID)
      );
      Optional<Boolean> optCascade = Optional.ofNullable(
        environment.getArgument(Constants.GraphQL.InputParameters.FindNodes.CASCADE)
      );
      Optional<Integer> optLimit = Optional.ofNullable(
        environment.getArgument(Constants.GraphQL.InputParameters.FindNodes.LIMIT)
      );
      Optional<NodeSort> optSort = Optional.ofNullable(
        environment.getArgument(Constants.GraphQL.InputParameters.FindNodes.SORT)
      );
      Optional<String> optPageToken = Optional.ofNullable(
        environment.getArgument(Constants.GraphQL.InputParameters.FindNodes.PAGE_TOKEN)
      );
      Optional<List<String>> optKeywords = Optional.ofNullable(
        environment.getArgument(Constants.GraphQL.InputParameters.FindNodes.KEYWORDS)
      );

      Optional<NodeType> optNodeType = Optional.ofNullable(
        environment.getArgument(Constants.GraphQL.InputParameters.FindNodes.NODE_TYPE)
      );

      Optional<String> optOwnerId = Optional.ofNullable(
        environment.getArgument(Constants.GraphQL.InputParameters.FindNodes.OWNER_ID)
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
      result.put(Constants.GraphQL.NodePage.PAGE_TOKEN, findResult.getRight());

      nodeContext.put(Constants.GraphQL.NodePage.NODES, findResult.getLeft());
      return new DataFetcherResult.Builder<Map<String, String>>()
        .data(result)
        .localContext(nodeContext)
        .build();

    });
  }

  /**
   * <p>This {@link DataFetcher} must be used for the retrieving the nodes attribute of the
   * {@link Constants.GraphQL.Queries#FIND_NODES} query.</p>
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
    return environment -> SyncCompletableFuture.supplyAsync(() -> {
      return Optional.ofNullable(environment.getLocalContext())
        .map(context -> {
          return ((Map<String, List<Node>>) context).get(Constants.GraphQL.NodePage.NODES)
            .stream()
            .map(node ->
              convertNodeToDataFetcherResult(
                node,
                ((UserMyself) environment.getGraphQlContext().get(Context.REQUESTER)).getId().getUserId(),
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
   * to the {@link Constants.GraphQL.Mutations#MOVE_NODES} mutation.</p>
   * <p>The requester must specify the following input parameters:</p>
   * <ul>
   *   <li>{@link Constants.GraphQL.InputParameters.MoveNodes#NODE_IDS}</li> containing a list of nodes id to move.</li
   *   <li>{@link Constants.GraphQL.InputParameters.MoveNodes#DESTINATION_ID}</li> containing the folder id where every node
   *   is moved.</li>
   * </ul>
   * <p>The requester must have the {@link SharePermission#READ_AND_WRITE} permission on every nodes that should be
   * moved and on the destination folder.</p>
   *
   * @return an asynchronous {@link DataFetcher} containing a {@link List} of the moved nodes.
   **/
  public DataFetcher<CompletableFuture<List<DataFetcherResult<Map<String, Object>>>>> moveNodesFetcher() {
    return environment -> SyncCompletableFuture.supplyAsync(() -> {
      ResultPath resultPath = environment.getExecutionStepInfo()
        .getPath();
      UserMyself requester = (UserMyself) environment.getGraphQlContext()
        .get(Constants.GraphQL.Context.REQUESTER);
      String requesterId = (requester).getId().getUserId();
      List<String> nodeIds = environment.getArgument(
        Constants.GraphQL.InputParameters.MoveNodes.NODE_IDS);
      String destinationFolderId = environment.getArgument(
        Constants.GraphQL.InputParameters.MoveNodes.DESTINATION_ID);

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
            nodeIdsToMove
                .forEach(nodeId -> {
                    Node node = nodeRepository.getNode(nodeId).get();

                    // Search for a new name only if not moving to same parent directory because obviously if so
                    // there will always be a node with that name already present causing node to be wrongly renamed
                    if(node.getParentId().isPresent() && !node.getParentId().get().equals(destinationFolderId)) {
                      String newName = searchAlternativeName(
                        nodeRepository, node.getFullName(), destinationFolderId, node.getOwnerId()
                      );
                      node.setFullName(newName);
                    }

                    nodeRepository.updateNode(node);

                    // Remove node notification snapshot & creation
                    List<String> usersToNotifyRemoveNode = new ArrayList<>(shareRepository.getSharesUsersIds(node.getId(), List.of()));
                    usersToNotifyRemoveNode.remove(requesterId);

                    Node parent = nodeRepository.getNode(node.getParentId().get()).get();
                    if (!parent.getNodeType().equals(NodeType.ROOT) &&
                        !requesterId.equals(parent.getOwnerId()) &&
                        !usersToNotifyRemoveNode.contains(parent.getOwnerId())) {
                      usersToNotifyRemoveNode.add(parent.getOwnerId());
                    }

                    if (!usersToNotifyRemoveNode.isEmpty() && filesConfig.areNotificationsEnabled())
                      notificationRepository.createRemovedNodeNotification(
                        node,
                        parent,
                        requester,
                        RemovedNodeType.MOVE,
                        usersToNotifyRemoveNode
                      );

                });

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
                Node node = nodeRepository.getNode(nodeId).get();

                cascadeUpdateAncestors(node);
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
                List<String> usersToNotifyAddNode = new ArrayList<>();
                shareRepository
                  .getShares(destinationFolderId, Collections.emptyList())
                  .forEach(share -> {
                    Optional<Share> sourceShare = shareRepository.getShare(nodeId,
                      share.getTargetUserId());
                    usersToNotifyAddNode.add(share.getTargetUserId());
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

                usersToNotifyAddNode.remove(requesterId); //remove requester if present

                // If the requester is the owner of the parent folder, do not notify him since he did the upload himself
                // Also exclude uploads on root, since root can't be shared and does not have an owner
                if (!optDestinationFolder.get().getNodeType().equals(NodeType.ROOT) &&
                    !requesterId.equals(optDestinationFolder.get().getOwnerId())) {
                  usersToNotifyAddNode.add(optDestinationFolder.get().getOwnerId());
                }

                if (!usersToNotifyAddNode.isEmpty() && filesConfig.areNotificationsEnabled())
                  notificationRepository.createAddedNodeNotification(
                    node,
                    optDestinationFolder.get(),
                    requester,
                    AddedNodeType.MOVE,
                    usersToNotifyAddNode
                  );
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


  /**
   * Recursively collects all descendants of the given root nodes (depth-first, bottom-up friendly).
   * Returns a flat list that includes the root nodes themselves plus every descendant, so that
   * the caller can work on the complete subtree without further recursion.
   */
  private List<Node> collectAllDescendants(List<Node> rootNodes) {
    List<Node> result = new ArrayList<>(rootNodes);
    for (Node node : rootNodes) {
      if (node.getNodeType().equals(NodeType.FOLDER)) {
        List<String> childrenIds = nodeRepository.getChildrenIds(
          node.getId(), Optional.empty(), Optional.empty(), true);
        if (!childrenIds.isEmpty()) {
          List<Node> children = nodeRepository.getNodes(childrenIds, Optional.empty())
            .filter(Objects::nonNull)
            .collect(Collectors.toList());
          result.addAll(collectAllDescendants(children));
        }
      }
    }
    return result;
  }

  public DataFetcher<CompletableFuture<DataFetcherResult<List<String>>>> deleteNodesFetcher() {

    return environment -> SyncCompletableFuture.supplyAsync(() -> {
      ResultPath resultPath = environment.getExecutionStepInfo().getPath();
      String requesterId = ((UserMyself) environment.getGraphQlContext()
        .get(Constants.GraphQL.Context.REQUESTER)).getId().getUserId();
      List<String> nodeIds = environment.getArgument(
        Constants.GraphQL.InputParameters.DeleteNodes.NODE_IDS);

      // Phase 1: filter requested nodes by existence and permission (root nodes only).
      List<Node> requestedNodes = nodeRepository.getNodes(nodeIds, Optional.empty())
        .filter(Objects::nonNull)
        .filter(node -> !node.getNodeType().equals(NodeType.ROOT))
        .filter(node -> permissionsChecker.getPermissions(node.getId(), requesterId)
          .has(SharePermission.READ_AND_WRITE))
        .collect(Collectors.toList());

      Set<String> requestedNodeIds = requestedNodes.stream()
        .map(Node::getId).collect(Collectors.toSet());

      // Phase 2: expand hierarchy.
      List<Node> allNodes = collectAllDescendants(requestedNodes);

      List<Node> fileNodes = allNodes.stream()
        .filter(node -> !node.getNodeType().equals(NodeType.FOLDER))
        .collect(Collectors.toList());

      // Phase 3: capture blob coords BEFORE delete (FK cascade removes file_versions).
      // Group by ownerId for later bulkDelete calls.
      Map<String, List<FileVersion>> fileVersionsByOwner = new java.util.HashMap<>();
      fileNodes.forEach(node -> {
        List<FileVersion> versions = fileVersionRepository.getFileVersions(
          node.getId(), List.of(FileVersionSort.VERSION_ASC));
        fileVersionsByOwner.computeIfAbsent(node.getOwnerId(), k -> new ArrayList<>())
          .addAll(versions);
      });

      // Phase 4: ONE transaction — write tombstones + delete DB rows + commit.
      try {
        QuarkusTransaction.requiringNew().run(() -> {
          // Write tombstones for all file versions grouped by owner.
          fileVersionsByOwner.forEach((ownerId, versions) ->
            tombstoneRepository.createTombstonesBulk(versions, ownerId));

          // Delete DB rows.
          deleteNodes(allNodes);
          allNodes.stream()
            .filter(node -> node.getNodeType().equals(NodeType.FOLDER))
            .map(Node::getId)
            .forEach(this::cascadeDeleteNode);
        });
      } catch (RuntimeException e) {
        logger.error("DB error during deleteNodesFetcher, rolling back: {}", e.getMessage());
        Builder<List<String>> errorBuilder = new Builder<List<String>>().data(List.of());
        nodeIds.stream()
          .filter(id -> !requestedNodeIds.contains(id))
          .forEach(id -> errorBuilder.error(GraphQLResultErrors.nodeNotFound(id, resultPath)));
        requestedNodeIds.forEach(id ->
          errorBuilder.error(GraphQLResultErrors.nodeWriteError(id, resultPath)));
        return errorBuilder.build();
      }

      // Phase 5: best-effort sync bulkDelete grouped by owner AFTER commit.
      fileVersionsByOwner.forEach((ownerId, versions) -> {
        List<BulkDeleteRequestItem> deleteRequests = versions.stream()
          .map(fv -> BulkDeleteRequestItem.filesItem(fv.getNodeId(), fv.getVersion()))
          .collect(Collectors.toList());
        if (deleteRequests.isEmpty()) return;
        List<BulkDeleteResponseItem> failedItems;
        try {
          failedItems = fileStore.bulkDelete(
            IdentifierType.files, ownerId, deleteRequests);
        } catch (Exception e) {            // ANY exception (incl. NullPointerException) -> NOT deleted -> keep all tombstones
          logger.warn("Bulk delete failed for owner {}: {}. Tombstones remain for retry.", ownerId, e.getMessage());
          return;
        }
        if (failedItems == null) {         // null return is NOT a success signal (happens on connection failure) -> keep all
          logger.warn("Bulk delete returned null for owner {} (treated as failure). Tombstones remain for retry.", ownerId);
          return;
        }
        // non-null list: empty = all deleted; partial = listed ids failed.
        Set<String> failedNodeIds = failedItems.stream()
          .map(BulkDeleteResponseItem::getNode).collect(Collectors.toSet());
        versions.stream()
          .filter(fv -> !failedNodeIds.contains(fv.getNodeId()))
          .forEach(fv ->
            tombstoneRepository.deleteTombstonesByNodeAndVersion(fv.getNodeId(), fv.getVersion()));
      });

      // Phase 6: build result — always full success (all permitted nodes deleted).
      Builder<List<String>> resultBuilder = new Builder<List<String>>()
        .data(new ArrayList<>(requestedNodeIds));
      nodeIds.stream()
        .filter(id -> !requestedNodeIds.contains(id))
        .forEach(id -> resultBuilder.error(GraphQLResultErrors.nodeNotFound(id, resultPath)));
      return resultBuilder.build();
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
      nodeRepository.deleteNodes(nodeIds);
    }
  }

  private record CopyOutcome(Optional<Node> node, Throwable failure) {}

  /**
   * Copies a {@link Node} of type File into a destination folder.
   *
   * @param sourceNode the file that must be copied.
   * @param destinationFolder the {@link Node} representing the destination folder.
   * @param requesterId requester identifier.
   * @param newFileName is an {@link Optional<String>} if we want to give a new name to the copied
   * file, used for name clashes
   *
   * @return the copied node (empty on failure) together with the copy failure (null on success).
   */
  private CopyOutcome copyFile(
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

    AtomicReference<Throwable> copyFailure = new AtomicReference<>();

    // TODO: make the copy async
    Try
      .of(() -> fileStore
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
        copyFailure.set(failure);
      });

    return new CopyOutcome(nodeRepository.getNode(createdNode.getId()), copyFailure.get());
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

  // Returns the list of users ids that have an indirect share on node
  private List<String> createIndirectShare(
    String sharedParentId,
    Node nodeToShare
  ) {
    List<String> targetUserIds = new ArrayList<>();
    shareRepository.getShares(sharedParentId, Collections.emptyList())
      .forEach(share -> {
        targetUserIds.add(share.getTargetUserId());
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
    return targetUserIds;
  }

  /**
   * <p>This {@link DataFetcher} copy one or more nodes into a destination folder. This is bound to
   * the {@link Constants.GraphQL.Mutations#COPY_NODES} mutation.</p>
   * <p>The requester must specify the following input parameters:</p>
   * <ul>
   *   <li>{@link Constants.GraphQL.InputParameters.CopyNodes#NODE_IDS}</li> containing a list of nodes id to copy.</li
   *   <li>{@link Constants.GraphQL.InputParameters.CopyNodes#DESTINATION_ID}</li> containing the folder id where every node
   *   is copied.</li>
   * </ul>
   * <p>The requester must have the {@link SharePermission#READ_AND_WRITE} permission on the destination folder.</p>
   *
   * @return an asynchronous {@link DataFetcher} containing a {@link List} of all the copied nodes.
   */
  public DataFetcher<CompletableFuture<List<DataFetcherResult<Map<String, Object>>>>> copyNodesFetcher() {
    return environment -> SyncCompletableFuture.supplyAsync(() -> {
      ResultPath resultPath = environment.getExecutionStepInfo().getPath();
      UserMyself requester = (UserMyself) environment
        .getGraphQlContext()
        .get(Constants.GraphQL.Context.REQUESTER);
      String requesterId = requester.getId().getUserId();
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
                  nodeRepository, nodeDup.getFullName(), destinationFolderId, nodeDup.getOwnerId()
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

                  List<String> usersToNotify = createIndirectShare(destinationFolderId, copiedFolder);
                  usersToNotify.remove(requesterId); // remove requester if present

                  // If the requester is the owner of the parent folder, do not notify him since he did the upload himself
                  // Also exclude uploads on root, since root can't be shared and does not have an owner
                  if (!optDestinationFolder.get().getNodeType().equals(NodeType.ROOT) &&
                      !requesterId.equals(optDestinationFolder.get().getOwnerId()) &&
                      !usersToNotify.contains(optDestinationFolder.get().getOwnerId())) {
                    usersToNotify.add(optDestinationFolder.get().getOwnerId());
                  }

                  if (!usersToNotify.isEmpty() && filesConfig.areNotificationsEnabled())
                    notificationRepository.createAddedNodeNotification(
                      copiedFolder,
                      optDestinationFolder.get(),
                      requester,
                      AddedNodeType.COPY,
                      usersToNotify
                    );

                } else {
                  CopyOutcome copyOutcome = copyFile(
                    nodeDup,
                    optDestinationFolder.get(),
                    requesterId,
                    Optional.of(newName)
                  );

                  if (copyOutcome.node().isPresent()) {
                    copiedNodesResult.add(
                      convertNodeToDataFetcherResult(copyOutcome.node().get(), requesterId, resultPath)
                    );
                    List<String> usersToNotify = createIndirectShare(destinationFolderId, copyOutcome.node().get());
                    usersToNotify.remove(requesterId); // remove requester if present

                    // If the requester is the owner of the parent folder, do not notify him since he did the upload himself
                    // Also exclude uploads on root, since root can't be shared and does not have an owner
                    if (!optDestinationFolder.get().getNodeType().equals(NodeType.ROOT) &&
                        !requesterId.equals(optDestinationFolder.get().getOwnerId()) &&
                        !usersToNotify.contains(optDestinationFolder.get().getOwnerId())) {
                      usersToNotify.add(optDestinationFolder.get().getOwnerId());
                    }

                    if (!usersToNotify.isEmpty() && filesConfig.areNotificationsEnabled())
                      notificationRepository.createAddedNodeNotification(
                        copyOutcome.node().get(),
                        optDestinationFolder.get(),
                        requester,
                        AddedNodeType.COPY,
                        usersToNotify
                      );

                  } else {
                    List<DataFetcherResult<Map<String, Object>>> errors =
                      errorsOfNodesWithoutPermission.get();

                    errors.add(DataFetcherResult
                      .<Map<String, Object>>newResult()
                      .error(copyFailureClassifier.classify(
                        copyOutcome.failure(),
                        nodeDup.getId(),
                        resultPath,
                        () -> GraphQLResultErrors.nodeCopyError(
                          nodeDup.getId(),
                          nodeDup.getCurrentVersion(),
                          resultPath
                        )
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

                  List<String> usersToNotify = createIndirectShare(destinationFolderId, copiedFolder);
                  usersToNotify.remove(requesterId); // remove requester if present

                  // If the requester is the owner of the parent folder, do not notify him since he did the upload himself
                  // Also exclude uploads on root, since root can't be shared and does not have an owner
                  if (!optDestinationFolder.get().getNodeType().equals(NodeType.ROOT) &&
                      !requesterId.equals(optDestinationFolder.get().getOwnerId()) &&
                      !usersToNotify.contains(optDestinationFolder.get().getOwnerId())) {
                    usersToNotify.add(optDestinationFolder.get().getOwnerId());
                  }

                  if (!usersToNotify.isEmpty() && filesConfig.areNotificationsEnabled())
                    notificationRepository.createAddedNodeNotification(
                      copiedFolder,
                      optDestinationFolder.get(),
                      requester,
                      AddedNodeType.COPY,
                      usersToNotify
                    );

                } else {
                  CopyOutcome copyOutcome = copyFile(
                    node,
                    optDestinationFolder.get(),
                    requesterId,
                    Optional.empty()
                  );

                  if (copyOutcome.node().isPresent()) {
                    copiedNodesResult.add(
                      convertNodeToDataFetcherResult(copyOutcome.node().get(), requesterId, resultPath)
                    );
                    List<String> usersToNotify = createIndirectShare(destinationFolderId, copyOutcome.node().get());
                    usersToNotify.remove(requesterId); // remove requester if present

                    // If the requester is the owner of the parent folder, do not notify him since he did the upload himself
                    // Also exclude uploads on root, since root can't be shared and does not have an owner
                    if (!optDestinationFolder.get().getNodeType().equals(NodeType.ROOT) &&
                        !requesterId.equals(optDestinationFolder.get().getOwnerId()) &&
                        !usersToNotify.contains(optDestinationFolder.get().getOwnerId())) {
                      usersToNotify.add(optDestinationFolder.get().getOwnerId());
                    }

                    if (!usersToNotify.isEmpty() && filesConfig.areNotificationsEnabled())
                      notificationRepository.createAddedNodeNotification(
                        copyOutcome.node().get(),
                        optDestinationFolder.get(),
                        requester,
                        AddedNodeType.COPY,
                        usersToNotify
                      );
                  } else {
                    List<DataFetcherResult<Map<String, Object>>> errors =
                      errorsOfNodesWithoutPermission.get();

                    errors.add(DataFetcherResult
                      .<Map<String, Object>>newResult()
                      .error(copyFailureClassifier.classify(
                        copyOutcome.failure(),
                        node.getId(),
                        resultPath,
                        () -> GraphQLResultErrors.nodeCopyError(
                          node.getId(),
                          node.getCurrentVersion(),
                          resultPath
                        )
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
    return environment -> SyncCompletableFuture.supplyAsync(() -> {
      ResultPath path = environment.getExecutionStepInfo()
        .getPath();
      String requesterId = ((UserMyself) environment.getGraphQlContext()
        .get(Constants.GraphQL.Context.REQUESTER)).getId().getUserId();
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
              .getFileVersions(nodeId, List.of(FileVersionSort.VERSION_DESC))
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
    return environment -> SyncCompletableFuture.supplyAsync(() -> {
      ResultPath path = environment.getExecutionStepInfo().getPath();
      String requesterId = ((UserMyself) environment.getGraphQlContext()
        .get(Constants.GraphQL.Context.REQUESTER)).getId().getUserId();
      String nodeId = environment.getArgument(GetVersions.NODE_ID);
      Optional<List<Integer>> optVersionsToDelete = Optional.ofNullable(
        environment.getArgument(GetVersions.VERSIONS));

      if (!permissionsChecker.getPermissions(nodeId, requesterId)
        .has(SharePermission.READ_AND_WRITE)) {
        return new Builder<List<Integer>>()
          .error(GraphQLResultErrors.nodeWriteError(nodeId, path))
          .build();
      }

      Node node = nodeRepository.getNode(nodeId).get();

      // Phase 1: determine eligible versions (skip current + keepForever).
      List<FileVersion> fileVersionsToDelete = optVersionsToDelete
        .map(versions -> fileVersionRepository.getFileVersions(nodeId, versions))
        .orElseGet(() -> fileVersionRepository.getFileVersions(nodeId, List.of(FileVersionSort.VERSION_DESC)))
        .stream()
        .filter(fv -> !fv.isKeptForever())
        .filter(fv -> !node.getCurrentVersion().equals(fv.getVersion()))
        .collect(Collectors.toList());

      List<Integer> versionsToDelete = fileVersionsToDelete.stream()
        .map(FileVersion::getVersion).collect(Collectors.toList());

      if (!versionsToDelete.isEmpty()) {
        // Phase 2: ONE transaction — write tombstones + delete from DB + commit.
        final String ownerId = node.getOwnerId();
        try {
          QuarkusTransaction.requiringNew().run(() -> {
            tombstoneRepository.createTombstonesBulk(fileVersionsToDelete, ownerId);
            fileVersionRepository.deleteFileVersions(nodeId, versionsToDelete);
          });
        } catch (RuntimeException e) {
          logger.error("DB error during deleteVersionsFetcher, rolling back: {}", e.getMessage());
          return new Builder<List<Integer>>()
            .error(GraphQLResultErrors.nodeWriteError(nodeId, path))
            .build();
        }

        // Phase 3: best-effort bulkDelete AFTER commit.
        List<BulkDeleteRequestItem> deleteRequests = fileVersionsToDelete.stream()
          .map(fv -> BulkDeleteRequestItem.filesItem(nodeId, fv.getVersion()))
          .collect(Collectors.toList());
        List<BulkDeleteResponseItem> failedItems;
        try {
          failedItems = fileStore.bulkDelete(
            IdentifierType.files, ownerId, deleteRequests);
        } catch (Exception e) {            // ANY exception (incl. NullPointerException) -> NOT deleted -> keep all tombstones
          logger.warn("Bulk delete failed for node {}: {}. Tombstones remain for retry.", nodeId, e.getMessage());
          failedItems = null;
        }
        if (failedItems == null) {         // null return is NOT a success signal (happens on connection failure) -> keep all
          logger.warn("Bulk delete returned null for node {} (treated as failure). Tombstones remain for retry.", nodeId);
        } else {
          // non-null list: empty = all deleted; partial = listed ids failed.
          Set<Integer> failedVersionSet = failedItems.stream()
            .filter(item -> nodeId.equals(item.getNode()))
            .map(BulkDeleteResponseItem::getVersion)
            .filter(Optional::isPresent).map(Optional::get)
            .collect(Collectors.toSet());
          fileVersionsToDelete.stream()
            .filter(fv -> !failedVersionSet.contains(fv.getVersion()))
            .forEach(fv ->
              tombstoneRepository.deleteTombstonesByNodeAndVersion(fv.getNodeId(), fv.getVersion()));
        }
      }

      // Phase 4: build result — all eligible versions deleted (no PowerStore error surfaced).
      Builder<List<Integer>> resultBuilder = new Builder<List<Integer>>().data(versionsToDelete);
      optVersionsToDelete.ifPresent(requested ->
        requested.stream()
          .filter(v -> !versionsToDelete.contains(v))
          .forEach(v -> resultBuilder.error(GraphQLResultErrors.fileVersionNotFound(nodeId, v, path)))
      );
      return resultBuilder.build();
    });
  }

  public DataFetcher<CompletableFuture<DataFetcherResult<List<Integer>>>> keepVersionsFetcher() {
    return environment -> SyncCompletableFuture.supplyAsync(() -> {
      ResultPath path = environment.getExecutionStepInfo()
        .getPath();
      String requesterId = ((UserMyself) environment.getGraphQlContext()
        .get(Constants.GraphQL.Context.REQUESTER)).getId().getUserId();
      String nodeId = environment.getArgument(GetVersions.NODE_ID);
      List<Integer> versionsToKeepForever = environment.getArgument(GetVersions.VERSIONS);
      Boolean keepForever = environment.getArgument(KeepVersions.KEEP_FOREVER);

      if (permissionsChecker.getPermissions(nodeId, requesterId)
        .has(SharePermission.READ_AND_WRITE)) {

        int maxNumberOfKeepVersions = getMaxNumberOfKeepVersions();
        List<FileVersion> listOfFileVersions = fileVersionRepository.getFileVersions(nodeId, List.of(FileVersionSort.VERSION_DESC));
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
    return environment -> SyncCompletableFuture.supplyAsync(() -> {
      ResultPath path = environment.getExecutionStepInfo().getPath();
      String requesterId = ((UserMyself) environment
        .getGraphQlContext()
        .get(Constants.GraphQL.Context.REQUESTER))
        .getId().getUserId();
      String nodeId = environment.getArgument(Constants.GraphQL.InputParameters.CloneVersion.NODE_ID);
      Integer versionToClone = environment.getArgument(
        Constants.GraphQL.InputParameters.CloneVersion.VERSION
      );

      if (permissionsChecker
        .getPermissions(nodeId, requesterId)
        .has(SharePermission.READ_AND_WRITE)
      ) {
        Node node = nodeRepository.getNode(nodeId).get();
        int maxNumberOfVersions = getMaxNumberOfVersions();
        if (fileVersionRepository.getFileVersions(nodeId, List.of(FileVersionSort.VERSION_DESC)).size() >= maxNumberOfVersions) {
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
              .of(() -> fileStore
                .copy(
                  FilesIdentifier.of(node.getId(), versionToClone, requesterId),
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
              .transform(attempt -> {
                if (attempt.isSuccess()) {
                  return convertNodeToDataFetcherResult(
                    nodeRepository.getNode(node.getId()).get(),
                    newVersion,
                    requesterId,
                    path
                  );
                }
                String error = MessageFormat.format(
                  "Copy error with nodeId: {0} and version {1}",
                  nodeId,
                  versionToClone
                );
                logger.error(error);
                // Default: throw AbortExecutionException(String) so its message surfaces verbatim.
                // Classified: RETURN the error so its extensions survive (a thrown error is stripped
                // by the async DataFetcherExceptionHandler, unlike a returned DataFetcherResult error).
                AbortExecutionException defaultError = new AbortExecutionException(error);
                GraphQLError classified =
                  copyFailureClassifier.classify(attempt.getCause(), nodeId, path, () -> defaultError);
                if (classified == defaultError) {
                  throw defaultError;
                }
                return new Builder<Map<String, Object>>().error(classified).build();
              });
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

  /**
   * Purges every non-root node owned by {@code userId}, including their blobs on Storages. Extracted
   * as its own reusable core (the same shape as the GraphQL mutation DataFetchers in this class) so
   * the trusted-caller {@code DELETE /internal/nodes} REST endpoint ({@code InternalNodeResource})
   * has a single, (data-loss-sensitive) delete-ordering implementation to call.
   *
   * <p><strong>DB-before-blob ordering is preserved:</strong> the DB rows (tombstones + node/version
   * deletes + folder cascade) are committed in a single transaction FIRST; only AFTER commit is the
   * best-effort storages {@code bulkDelete} attempted. A DB failure rolls the transaction back and
   * propagates a {@link RuntimeException} <em>before</em> any blob is touched, so no blob is ever
   * orphaned by a failed metadata delete. Tombstones of blobs that storages fails to delete are kept
   * for the purge retry.
   *
   * @param userId the owner whose nodes/blobs are to be deleted
   * @throws RuntimeException if the DB transaction fails (caller must treat as a rollback)
   */
  public void deleteAllNodesAndBlobsForUser(String userId) {
    List<Node> allNodes = nodeRepository.findNodesByOwner(userId).stream()
      .filter(Objects::nonNull)
      .filter(node -> !node.getNodeType().equals(NodeType.ROOT))
      .toList();

    List<Node> fileNodes = allNodes.stream()
      .filter(node -> !node.getNodeType().equals(NodeType.FOLDER))
      .toList();

    // Capture file versions grouped by owner before delete.
    Map<String, List<FileVersion>> fileVersionsByOwner = new java.util.HashMap<>();
    fileNodes.forEach(node -> {
      List<FileVersion> versions = fileVersionRepository.getFileVersions(
        node.getId(), List.of(FileVersionSort.VERSION_ASC));
      fileVersionsByOwner.computeIfAbsent(node.getOwnerId(), k -> new ArrayList<>())
        .addAll(versions);
    });

    // ONE transaction: tombstones + DB delete + commit. A RuntimeException here rolls back and
    // propagates to the caller BEFORE the best-effort bulkDelete below (DB-before-blob ordering).
    QuarkusTransaction.requiringNew().run(() -> {
      fileVersionsByOwner.forEach((ownerId, versions) ->
        tombstoneRepository.createTombstonesBulk(versions, ownerId));
      deleteNodes(allNodes);
      allNodes.stream()
        .filter(node -> node.getNodeType().equals(NodeType.FOLDER))
        .map(Node::getId)
        .forEach(this::cascadeDeleteNode);
    });

    // Best-effort bulkDelete after commit.
    fileVersionsByOwner.forEach((ownerId, versions) -> {
      List<BulkDeleteRequestItem> deleteRequests = versions.stream()
        .map(fv -> BulkDeleteRequestItem.filesItem(fv.getNodeId(), fv.getVersion()))
        .collect(Collectors.toList());
      if (deleteRequests.isEmpty()) return;
      List<BulkDeleteResponseItem> failedItems;
      try {
        failedItems = fileStore.bulkDelete(
          IdentifierType.files, ownerId, deleteRequests);
      } catch (Exception e) {            // ANY exception (incl. NullPointerException) -> NOT deleted -> keep all tombstones
        logger.warn("Bulk delete failed for owner {}: {}. Tombstones remain for retry.", ownerId, e.getMessage());
        return;
      }
      if (failedItems == null) {         // null return is NOT a success signal (happens on connection failure) -> keep all
        logger.warn("Bulk delete returned null for owner {} (treated as failure). Tombstones remain for retry.", ownerId);
        return;
      }
      // non-null list: empty = all deleted; partial = listed ids failed.
      Set<String> failedNodeIds = failedItems.stream()
        .map(BulkDeleteResponseItem::getNode).collect(Collectors.toSet());
      versions.stream()
        .filter(fv -> !failedNodeIds.contains(fv.getNodeId()))
        .forEach(fv ->
          tombstoneRepository.deleteTombstonesByNodeAndVersion(fv.getNodeId(), fv.getVersion()));
    });
  }
}
