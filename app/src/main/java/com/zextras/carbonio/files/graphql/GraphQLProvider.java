// SPDX-FileCopyrightText: 2022 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.graphql;

import jakarta.inject.Inject;
import jakarta.enterprise.context.ApplicationScoped;
import com.zextras.carbonio.files.Constants;
import com.zextras.carbonio.files.graphql.datafetchers.*;
import com.zextras.carbonio.files.graphql.validators.InputFieldsController;
import graphql.GraphQL;
import graphql.analysis.MaxQueryDepthInstrumentation;
import graphql.execution.AsyncExecutionStrategy;
import graphql.execution.ResultPath;
import graphql.execution.instrumentation.ChainedInstrumentation;
import graphql.execution.instrumentation.Instrumentation;
import graphql.execution.instrumentation.fieldvalidation.FieldValidation;
import graphql.execution.instrumentation.fieldvalidation.FieldValidationInstrumentation;
import graphql.execution.instrumentation.fieldvalidation.SimpleFieldValidation;
import graphql.schema.DataFetcher;
import graphql.schema.GraphQLCodeRegistry;
import graphql.schema.GraphQLSchema;
import graphql.schema.idl.RuntimeWiring;
import graphql.schema.idl.SchemaGenerator;
import graphql.schema.idl.SchemaParser;
import graphql.schema.visibility.BlockedFields;
import graphql.schema.visibility.GraphqlFieldVisibility;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.util.List;
import java.util.Map;

import static graphql.schema.idl.TypeRuntimeWiring.newTypeWiring;

/**
 * <p>Setups the GraphQL instance with all the necessary properties. A GraphQL instance is
 * necessary to handle and execute every single request. In fact it knows:</p>
 * <ul>
 *  <li>which is the right schema of reference</li>
 *  <li>how to validate the input</li>
 *  <li>which {@link DataFetcher} need to be called</li>
 * </ul>
 */
@ApplicationScoped
public class GraphQLProvider {

  private static final String SCHEMA_URL = "/api/schema.graphql";

  private final GraphQL graphQL;
  private final InputFieldsController inputFieldsController;
  private final NodeDataFetcher nodeDataFetcher;
  private final UserDataFetcher userDataFetcher;
  private final ShareDataFetcher shareDataFetcher;
  private final LinkDataFetcher linkDataFetcher;
  private final CollaborationLinkDataFetcher collaborationLinkDataFetcher;
  private final ConfigDataFetcher configDataFetcher;
  private final NotificationDataFetcher notificationDataFetcher;

  @Inject
  public GraphQLProvider(
      InputFieldsController inputFieldsController,
      NodeDataFetcher nodeDataFetcher,
      UserDataFetcher userDataFetcher,
      ShareDataFetcher shareDataFetcher,
      LinkDataFetcher linkDataFetcher,
      CollaborationLinkDataFetcher collaborationLinkDataFetcher,
      ConfigDataFetcher configDataFetcher,
      NotificationDataFetcher notificationDataFetcher
  ) {
    this.inputFieldsController = inputFieldsController;
    this.nodeDataFetcher = nodeDataFetcher;
    this.userDataFetcher = userDataFetcher;
    this.shareDataFetcher = shareDataFetcher;
    this.linkDataFetcher = linkDataFetcher;
    this.collaborationLinkDataFetcher = collaborationLinkDataFetcher;
    this.configDataFetcher = configDataFetcher;
    this.notificationDataFetcher = notificationDataFetcher;
    graphQL = this.setup();
  }

  /**
   * This method creates the GraphQL instance building the following properties:
   * <ul>
   *   <li>{@link RuntimeWiring}: it links each interface, query and mutation with the related {@link DataFetcher}</li>
   *   <li>{@link GraphQLSchema}: the schema definition file is imported from the resources</li>
   *   <li>Execution strategy: how the execution of a request is performed (async or not)</li>
   *   <li>Instrumentations: it is useful to check the input values of a request and to enable a
   *   batching mechanism using {@link org.dataloader.DataLoader}s</li>
   * </ul>
   *
   * @return {@link GraphQL}
   */
  private GraphQL setup() {
    List<Instrumentation> chainedInstrumentations = List.of(
        buildValidationInstrumentation(),
        new MaxQueryDepthInstrumentation(10)
    );

    return GraphQL.newGraphQL(buildSchema(buildWiring()))
        .queryExecutionStrategy(new AsyncExecutionStrategy())
        .instrumentation(new ChainedInstrumentation(chainedInstrumentations))
        .build();
  }

  private FieldValidationInstrumentation buildValidationInstrumentation() {
    FieldValidation fieldValidation = new SimpleFieldValidation()
        .addRule(
            ResultPath.parse("/" + Constants.GraphQL.Queries.GET_NODE),
            inputFieldsController.getNodeValidation()
        )
        .addRule(
            ResultPath.parse("/" + Constants.GraphQL.Queries.GET_NODE + "/children"),
            inputFieldsController.childrenArgumentValidation()
        )
        .addRule(
            ResultPath.parse("/" + Constants.GraphQL.Mutations.CREATE_FOLDER),
            inputFieldsController.createFolderValidation()
        )
        .addRule(
            ResultPath.parse("/" + Constants.GraphQL.Mutations.UPDATE_NODE),
            inputFieldsController.updateNodeValidation()
        )
        .addRule(
            ResultPath.parse("/" + Constants.GraphQL.Mutations.MOVE_NODES),
            inputFieldsController.moveNodesValidation()
        )
        .addRule(
            ResultPath.parse("/" + Constants.GraphQL.Mutations.DELETE_NODES),
            inputFieldsController.deleteNodesValidation()
        )
        .addRule(
            ResultPath.parse("/" + Constants.GraphQL.Mutations.TRASH_NODES),
            inputFieldsController.trashNodesValidation()
        )
        .addRule(
            ResultPath.parse("/" + Constants.GraphQL.Mutations.RESTORE_NODES),
            inputFieldsController.restoreNodesValidation()
        )
        .addRule(
            ResultPath.parse("/" + Constants.GraphQL.Mutations.COPY_NODES),
            inputFieldsController.copyNodesValidation()
        )
        .addRule(
            ResultPath.parse("/" + Constants.GraphQL.Mutations.CREATE_SHARE),
            inputFieldsController.shareQueriesValidation()
        )
        .addRule(
            ResultPath.parse("/" + Constants.GraphQL.Queries.GET_SHARE),
            inputFieldsController.shareQueriesValidation()
        )
        .addRule(
            ResultPath.parse("/" + Constants.GraphQL.Mutations.UPDATE_SHARES),
            inputFieldsController.bulkShareQueriesValidation()
        )
        .addRule(
            ResultPath.parse("/" + Constants.GraphQL.Mutations.DELETE_SHARES),
            inputFieldsController.bulkShareQueriesValidation()
        )
        .addRule(
            ResultPath.parse("/" + Constants.GraphQL.Mutations.CREATE_LINK),
            inputFieldsController.createLinkValidation()
        )
        .addRule(
            ResultPath.parse("/" + Constants.GraphQL.Queries.GET_LINKS),
            inputFieldsController.getLinksValidation()
        )
        .addRule(
            ResultPath.parse("/" + Constants.GraphQL.Mutations.UPDATE_LINK),
            inputFieldsController.updateLinkValidation()
        )
        .addRule(
            ResultPath.parse("/" + Constants.GraphQL.Mutations.DELETE_LINKS),
            inputFieldsController.deleteLinksValidation()
        )
        .addRule(
            ResultPath.parse("/" + Constants.GraphQL.Queries.GET_PATH),
            inputFieldsController.getPathValidation()
        )
        .addRule(
            ResultPath.parse("/" + Constants.GraphQL.Queries.GET_ACCOUNT_BY_EMAIL),
            inputFieldsController.getAccountByEmailValidation()
        )
        .addRule(
            ResultPath.parse("/" + Constants.GraphQL.Queries.GET_ACCOUNTS_BY_EMAIL),
            inputFieldsController.getAccountsByEmailValidation()
        )
        .addRule(
            ResultPath.parse("/" + Constants.GraphQL.Mutations.CREATE_COLLABORATION_LINK),
            inputFieldsController.createCollaborationLinkValidation()
        )
        .addRule(
            ResultPath.parse("/" + Constants.GraphQL.Queries.GET_COLLABORATION_LINKS),
            inputFieldsController.getCollaborationLinksValidation()
        )
        .addRule(
            ResultPath.parse("/" + Constants.GraphQL.Mutations.DELETE_COLLABORATION_LINKS),
            inputFieldsController.deleteCollaborationLinksValidation()
        )
        .addRule(
            ResultPath.parse("/" + Constants.GraphQL.Mutations.DELETE_ALL_NODES_AND_BLOBS),
            inputFieldsController.deleteAllNodesAndBlobsValidation()
        );

    return new FieldValidationInstrumentation(fieldValidation);
  }

  /**
   * Creates a {@link RuntimeWiring} object associating a specific {@link DataFetcher} to one of
   * these GraphQL components:
   * <ul>
   *   <li>Interface</li>
   *   <li>Relation between different types (for example: Node has a relation with the Share type)</li>
   *   <li>Query</li>
   *   <li>Mutation</li>
   * </ul>
   *
   * @return a {@link RuntimeWiring} instance.
   */
  private RuntimeWiring buildWiring() {
    return RuntimeWiring.newRuntimeWiring()
        .scalar(new DateTimeScalar().graphQLScalarType())
        .type(newTypeWiring(Constants.GraphQL.Types.NODE_SORT)
            .enumValues(nodeDataFetcher.getNodeSortResolver())
        )
        .type(newTypeWiring(Constants.GraphQL.Types.SHARE_PERMISSION)
            .enumValues(shareDataFetcher.getSharePermissionsResolver())
        )
        .type(newTypeWiring(Constants.GraphQL.Types.NODE_TYPE)
            .enumValues(nodeDataFetcher.getNodeTypeResolver())
        )
        .type(newTypeWiring(Constants.GraphQL.Types.ADDED_NODE_TYPE)
            .enumValues(notificationDataFetcher.getAddedNodeTypeResolver())
        )
        .type(newTypeWiring(Constants.GraphQL.Types.REMOVED_NODE_TYPE)
            .enumValues(notificationDataFetcher.getRemovedNodeTypeResolver())
        )
        .type(newTypeWiring("Query")
            .dataFetcher(Constants.GraphQL.Queries.GET_NODE, nodeDataFetcher.getNodeFetcher())
            .dataFetcher(Constants.GraphQL.Queries.GET_USER, userDataFetcher.getUserFetcher())
            .dataFetcher(Constants.GraphQL.Queries.GET_SHARE, shareDataFetcher.getShareFetcher())
            .dataFetcher(Constants.GraphQL.Queries.GET_ROOTS_LIST, nodeDataFetcher.getRootsListFetcher())
            .dataFetcher(Constants.GraphQL.Queries.GET_PATH, nodeDataFetcher.getPathFetcher())
            .dataFetcher(Constants.GraphQL.Queries.FIND_NODES, nodeDataFetcher.findNodesFetcher())
            .dataFetcher(Constants.GraphQL.Queries.GET_VERSIONS, nodeDataFetcher.getVersionsFetcher())
            .dataFetcher(
                Constants.GraphQL.Queries.GET_ACCOUNT_BY_EMAIL,
                userDataFetcher.getAccountByEmailFetcher()
            )
            .dataFetcher(
                Constants.GraphQL.Queries.GET_ACCOUNTS_BY_EMAIL,
                userDataFetcher.getAccountsByEmailFetcher()
            )
            .dataFetcher(Constants.GraphQL.Queries.GET_LINKS, linkDataFetcher.getLinks())
            .dataFetcher(
                Constants.GraphQL.Queries.GET_COLLABORATION_LINKS,
                collaborationLinkDataFetcher.getCollaborationLinksByNodeId()
            )
            .dataFetcher(Constants.GraphQL.Queries.GET_CONFIGS, configDataFetcher.getConfigs())
            .dataFetcher(Constants.GraphQL.Queries.GET_NOTIFICATIONS,
                notificationDataFetcher.getNotificationsFetcher()
            )
        )
        .type(newTypeWiring("Mutation")
            .dataFetcher(Constants.GraphQL.Mutations.CREATE_FOLDER, nodeDataFetcher.createFolderFetcher())
            .dataFetcher(Constants.GraphQL.Mutations.UPDATE_NODE, nodeDataFetcher.updateNodeFetcher())
            .dataFetcher(Constants.GraphQL.Mutations.FLAG_NODES, nodeDataFetcher.flagNodes())
            .dataFetcher(Constants.GraphQL.Mutations.TRASH_NODES, nodeDataFetcher.trashNodes())
            .dataFetcher(Constants.GraphQL.Mutations.RESTORE_NODES, nodeDataFetcher.restoreNodes())
            .dataFetcher(Constants.GraphQL.Mutations.MOVE_NODES, nodeDataFetcher.moveNodesFetcher())
            .dataFetcher(Constants.GraphQL.Mutations.DELETE_NODES, nodeDataFetcher.deleteNodesFetcher())
            .dataFetcher(Constants.GraphQL.Mutations.DELETE_ALL_NODES_AND_BLOBS, nodeDataFetcher.deleteAllNodesAndBlobs())
            .dataFetcher(
                Constants.GraphQL.Mutations.DELETE_VERSIONS,
                nodeDataFetcher.deleteVersionsFetcher()
            )
            .dataFetcher(Constants.GraphQL.Mutations.KEEP_VERSIONS, nodeDataFetcher.keepVersionsFetcher())
            .dataFetcher(Constants.GraphQL.Mutations.CLONE_VERSION, nodeDataFetcher.cloneVersionFetcher())
            .dataFetcher(Constants.GraphQL.Mutations.COPY_NODES, nodeDataFetcher.copyNodesFetcher())
            .dataFetcher(Constants.GraphQL.Mutations.CREATE_SHARE, shareDataFetcher.createShareFetcher())
            .dataFetcher(Constants.GraphQL.Mutations.UPDATE_SHARES, shareDataFetcher.updateSharesFetcher())
            .dataFetcher(Constants.GraphQL.Mutations.DELETE_SHARES, shareDataFetcher.deleteSharesFetcher())
            .dataFetcher(Constants.GraphQL.Mutations.CREATE_LINK, linkDataFetcher.createLink())
            .dataFetcher(Constants.GraphQL.Mutations.UPDATE_LINK, linkDataFetcher.updateLink())
            .dataFetcher(Constants.GraphQL.Mutations.DELETE_LINKS, linkDataFetcher.deleteLinks())
            .dataFetcher(
                Constants.GraphQL.Mutations.CREATE_COLLABORATION_LINK,
                collaborationLinkDataFetcher.createCollaborationLink()
            )
            .dataFetcher(
                Constants.GraphQL.Mutations.DELETE_COLLABORATION_LINKS,
                collaborationLinkDataFetcher.deleteCollaborationLinks()
            )
        )
        .type(newTypeWiring(Constants.GraphQL.Types.NODE_INTERFACE)
            .typeResolver(nodeDataFetcher.getNodeInterfaceResolver())
        )
        .type(newTypeWiring(Constants.GraphQL.Types.FILE)
            .dataFetcher(Constants.GraphQL.FileVersion.CREATOR, userDataFetcher.getUserFetcher())
            .dataFetcher(Constants.GraphQL.FileVersion.OWNER, userDataFetcher.getUserFetcher())
            .dataFetcher(Constants.GraphQL.FileVersion.LAST_EDITOR, userDataFetcher.getUserFetcher())
            .dataFetcher(Constants.GraphQL.FileVersion.PARENT, nodeDataFetcher.getNodeFetcher())
            .dataFetcher(
                Constants.GraphQL.FileVersion.PERMISSIONS,
                nodeDataFetcher.getPermissionsNodeFetcher()
            )
            .dataFetcher(Constants.GraphQL.FileVersion.SHARES, shareDataFetcher.getSharesFetcher())
            .dataFetcher(Constants.GraphQL.FileVersion.LINKS, linkDataFetcher.getLinks())
            .dataFetcher(
                Constants.GraphQL.FileVersion.COLLABORATION_LINKS,
                collaborationLinkDataFetcher.getCollaborationLinksByNodeId()
            )
        )
        .type(newTypeWiring(Constants.GraphQL.Types.FOLDER)
            .dataFetcher(Constants.GraphQL.Folder.CREATOR, userDataFetcher.getUserFetcher())
            .dataFetcher(Constants.GraphQL.Folder.OWNER, userDataFetcher.getUserFetcher())
            .dataFetcher(Constants.GraphQL.Folder.LAST_EDITOR, userDataFetcher.getUserFetcher())
            .dataFetcher(Constants.GraphQL.Folder.PARENT, nodeDataFetcher.getNodeFetcher())
            .dataFetcher(Constants.GraphQL.Folder.CHILDREN, nodeDataFetcher.getChildNodesFetcherFast())
            .dataFetcher(Constants.GraphQL.Folder.PERMISSIONS, nodeDataFetcher.getPermissionsNodeFetcher())
            .dataFetcher(Constants.GraphQL.Folder.SHARES, shareDataFetcher.getSharesFetcher())
            .dataFetcher(Constants.GraphQL.Folder.LINKS, linkDataFetcher.getLinks())
            .dataFetcher(
                Constants.GraphQL.Folder.COLLABORATION_LINKS,
                collaborationLinkDataFetcher.getCollaborationLinksByNodeId()
            )
        )
        .type(newTypeWiring(Constants.GraphQL.Types.NODE_PAGE)
            .dataFetcher(Constants.GraphQL.NodePage.NODES, nodeDataFetcher.nodePageFetcher())
        )
        .type(newTypeWiring(Constants.GraphQL.Types.SHARED_TARGET)
            .typeResolver(userDataFetcher.getAccountTypeResolver())
        )
        .type(newTypeWiring(Constants.GraphQL.Types.ACCOUNT)
            .typeResolver(userDataFetcher.getAccountTypeResolver())
        )

        .type(newTypeWiring(Constants.GraphQL.Types.SHARE)
            .dataFetcher(Constants.GraphQL.Share.NODE, nodeDataFetcher.sharedNodeFetcher())
            .dataFetcher(Constants.GraphQL.Share.SHARE_TARGET, userDataFetcher.shareTargetUserFetcher())
        )
        .type(newTypeWiring(Constants.GraphQL.Types.DISTRIBUTION_LIST)
            .dataFetcher(Constants.GraphQL.DistributionList.USERS, userDataFetcher.getDLUsersFetcher())
        )
        .type(newTypeWiring(Constants.GraphQL.Types.LINK)
            .dataFetcher(Constants.GraphQL.Link.NODE, nodeDataFetcher.sharedNodeFetcher())
        )
        .type(newTypeWiring(Constants.GraphQL.Types.COLLABORATION_LINK)
            .dataFetcher(Constants.GraphQL.Link.NODE, nodeDataFetcher.sharedNodeFetcher())
        )
        .type(newTypeWiring(Constants.GraphQL.Types.NOTIFICATION_PAGE)
            .dataFetcher(Constants.GraphQL.NotificationPage.NOTIFICATIONS, notificationDataFetcher.notificationPageFetcher())
        )
        .type(newTypeWiring(Constants.GraphQL.Types.NOTIFICATION)
            .typeResolver(notificationDataFetcher.getNotificationInterfaceResolver())
        )
        // Since we always return the nested objects with the notification call, no need to fetch them again (return as is)
        .type(Constants.GraphQL.Types.NEW_SHARE, typeWiring -> typeWiring
            .dataFetcher(Constants.GraphQL.NewShareNotification.NODE_SNAPSHOT, env -> {
              Map<String, Object> source = (Map<String, Object>) env.getSource();
              return source.get(Constants.GraphQL.NewShareNotification.NODE_SNAPSHOT);
            })
            .dataFetcher(Constants.GraphQL.NewShareNotification.USER_SNAPSHOT, env -> {
              Map<String, Object> source = (Map<String, Object>) env.getSource();
              return source.get(Constants.GraphQL.NewShareNotification.USER_SNAPSHOT);
            })
        )
        .type(newTypeWiring(Constants.GraphQL.Types.ADDED_NODE)
            .dataFetcher(Constants.GraphQL.AddedNodeNotification.ADDED_NODE_SNAPSHOT, env -> {
              Map<String, Object> source = (Map<String, Object>) env.getSource();
              return source.get(Constants.GraphQL.AddedNodeNotification.ADDED_NODE_SNAPSHOT);
            })
            .dataFetcher(Constants.GraphQL.AddedNodeNotification.DESTINATION_FOLDER, env -> {
              Map<String, Object> source = (Map<String, Object>) env.getSource();
              return source.get(Constants.GraphQL.AddedNodeNotification.DESTINATION_FOLDER);
            })
            .dataFetcher(Constants.GraphQL.AddedNodeNotification.TRIGGERING_USER, env -> {
              Map<String, Object> source = (Map<String, Object>) env.getSource();
              return source.get(Constants.GraphQL.AddedNodeNotification.TRIGGERING_USER);
            })
        )
        .type(newTypeWiring(Constants.GraphQL.Types.REMOVED_NODE)
            .dataFetcher(Constants.GraphQL.RemovedNodeNotification.REMOVED_NODE, env -> {
              Map<String, Object> source = (Map<String, Object>) env.getSource();
              return source.get(Constants.GraphQL.RemovedNodeNotification.REMOVED_NODE);
            })
            .dataFetcher(Constants.GraphQL.RemovedNodeNotification.ORIGIN_FOLDER, env -> {
              Map<String, Object> source = (Map<String, Object>) env.getSource();
              return source.get(Constants.GraphQL.RemovedNodeNotification.ORIGIN_FOLDER);
            })
            .dataFetcher(Constants.GraphQL.RemovedNodeNotification.TRIGGERING_USER, env -> {
              Map<String, Object> source = (Map<String, Object>) env.getSource();
              return source.get(Constants.GraphQL.RemovedNodeNotification.TRIGGERING_USER);
            })
        )
        .build();
  }

  /**
   * Imports the GraphQL schema file (from res folder) and creates the {@link GraphQLSchema} object
   * associating the schema file with the RuntimeWiring object.
   *
   * @param wiring is a {@link RuntimeWiring} that contains the association between the GraphQL
   *               components and their specific {@link DataFetcher}s.
   * @return the {@link GraphQLSchema}.
   */
  private GraphQLSchema buildSchema(RuntimeWiring wiring) {
    // Fetch the content of the GraphQL schema file into an InputStreamReader
    InputStream inputStream = getClass().getResourceAsStream(SCHEMA_URL);
    Reader schema = new InputStreamReader(inputStream);

    // Generate the schema
    GraphQLSchema graphQLSchema = new SchemaGenerator().makeExecutableSchema(new SchemaParser().parse(schema), wiring);

    // Add blocked fields to disable introspection
    GraphQLCodeRegistry existingCodeRegistry = graphQLSchema.getCodeRegistry();
    GraphqlFieldVisibility blockedFields = BlockedFields.newBlock().addPattern("__.*").build();

    GraphQLCodeRegistry updatedCodeRegistry = existingCodeRegistry.transform(builder ->
        builder.fieldVisibility(blockedFields)
    );

    // Apply code registry to schema
    return graphQLSchema.transform(builder ->
        builder.codeRegistry(updatedCodeRegistry)
    );
  }

  public GraphQL getGraphQL() {
    return this.graphQL;
  }
}
