// SPDX-FileCopyrightText: 2022 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.graphql;

import static graphql.schema.idl.TypeRuntimeWiring.newTypeWiring;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.zextras.carbonio.files.Files;
import com.zextras.carbonio.files.graphql.datafetchers.ConfigDataFetcher;
import com.zextras.carbonio.files.graphql.datafetchers.DateTimeScalar;
import com.zextras.carbonio.files.graphql.datafetchers.CollaborationLinkDataFetcher;
import com.zextras.carbonio.files.graphql.datafetchers.LinkDataFetcher;
import com.zextras.carbonio.files.graphql.datafetchers.NodeDataFetcher;
import com.zextras.carbonio.files.graphql.datafetchers.ShareDataFetcher;
import com.zextras.carbonio.files.graphql.datafetchers.UserDataFetcher;
import com.zextras.carbonio.files.graphql.validators.InputFieldsController;
import graphql.GraphQL;
import graphql.execution.AsyncExecutionStrategy;
import graphql.execution.ResultPath;
import graphql.execution.instrumentation.dataloader.DataLoaderDispatcherInstrumentation;
import graphql.execution.instrumentation.dataloader.DataLoaderDispatcherInstrumentationOptions;
import graphql.execution.instrumentation.fieldvalidation.FieldValidation;
import graphql.execution.instrumentation.fieldvalidation.FieldValidationInstrumentation;
import graphql.execution.instrumentation.fieldvalidation.SimpleFieldValidation;
import graphql.schema.DataFetcher;
import graphql.schema.GraphQLSchema;
import graphql.schema.idl.RuntimeWiring;
import graphql.schema.idl.SchemaGenerator;
import graphql.schema.idl.SchemaParser;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import org.dataloader.BatchLoader;

/**
 * <p>Setups the GraphQL instance with all the necessary properties. A GraphQL instance is
 * necessary to handle and execute every single request. In fact it knows:</p>
 * <ul>
 *  <li>which is the right schema of reference</li>
 *  <li>how to validate the input</li>
 *  <li>which {@link DataFetcher} need to be called</li>
 * </ul>
 */
@Singleton
public class GraphQLProvider {

  private static final String SCHEMA_URL = "/api/schema.graphql";

  private final GraphQL                      graphQL;
  private final InputFieldsController        inputFieldsController;
  private final NodeDataFetcher              nodeDataFetcher;
  private final UserDataFetcher              userDataFetcher;
  private final ShareDataFetcher             shareDataFetcher;
  private final LinkDataFetcher              linkDataFetcher;
  private final CollaborationLinkDataFetcher collaborationLinkDataFetcher;
  private final ConfigDataFetcher            configDataFetcher;

  @Inject
  public GraphQLProvider(
    InputFieldsController inputFieldsController,
    NodeDataFetcher nodeDataFetcher,
    UserDataFetcher userDataFetcher,
    ShareDataFetcher shareDataFetcher,
    LinkDataFetcher linkDataFetcher,
    CollaborationLinkDataFetcher collaborationLinkDataFetcher,
    ConfigDataFetcher configDataFetcher
  ) {
    this.inputFieldsController = inputFieldsController;
    this.nodeDataFetcher = nodeDataFetcher;
    this.userDataFetcher = userDataFetcher;
    this.shareDataFetcher = shareDataFetcher;
    this.linkDataFetcher = linkDataFetcher;
    this.collaborationLinkDataFetcher = collaborationLinkDataFetcher;
    this.configDataFetcher = configDataFetcher;
    graphQL = this.setup();
  }

  /**
   * This method creates the GraphQL instance building the following properties:
   * <ul>
   *   <li>{@link RuntimeWiring}: it links each interface, query and mutation with the related {@link DataFetcher}</li>
   *   <li>{@link GraphQLSchema}: the schema definition file is imported from the resources</li>
   *   <li>Execution strategy: how the execution of a request is performed (async or not)</li>
   *   <li>Instrumentation: it is useful to check the input values of a request</li>
   * </ul>
   *
   * @return {@link GraphQL}
   */
  private GraphQL setup() {
    return GraphQL.newGraphQL(buildSchema(buildWiring()))
      .queryExecutionStrategy(new AsyncExecutionStrategy())
      .instrumentation(buildValidationInstrumentation())
      .instrumentation(buildDataLoaderDispatcherInstrumentation())
      .build();
  }

  private FieldValidationInstrumentation buildValidationInstrumentation() {
    FieldValidation fieldValidation = new SimpleFieldValidation()
      .addRule(
        ResultPath.parse("/" + Files.GraphQL.Queries.GET_NODE),
        inputFieldsController.getNodeValidation()
      )
      .addRule(
        ResultPath.parse("/" + Files.GraphQL.Queries.GET_NODE + "/children"),
        inputFieldsController.childrenArgumentValidation()
      )
      .addRule(
        ResultPath.parse("/" + Files.GraphQL.Mutations.CREATE_FOLDER),
        inputFieldsController.createFolderValidation()
      )
      .addRule(
        ResultPath.parse("/" + Files.GraphQL.Mutations.UPDATE_NODE),
        inputFieldsController.updateNodeValidation()
      )
      .addRule(
        ResultPath.parse("/" + Files.GraphQL.Mutations.MOVE_NODES),
        inputFieldsController.moveNodesValidation()
      )
      .addRule(
        ResultPath.parse("/" + Files.GraphQL.Mutations.DELETE_NODES),
        inputFieldsController.deleteNodesValidation()
      )
      .addRule(
        ResultPath.parse("/" + Files.GraphQL.Mutations.TRASH_NODES),
        inputFieldsController.trashNodesValidation()
      )
      .addRule(
        ResultPath.parse("/" + Files.GraphQL.Mutations.RESTORE_NODES),
        inputFieldsController.restoreNodesValidation()
      )
      .addRule(
        ResultPath.parse("/" + Files.GraphQL.Mutations.COPY_NODES),
        inputFieldsController.copyNodesValidation()
      )
      .addRule(
        ResultPath.parse("/" + Files.GraphQL.Mutations.CREATE_SHARE),
        inputFieldsController.shareQueriesValidation()
      )
      .addRule(
        ResultPath.parse("/" + Files.GraphQL.Queries.GET_SHARE),
        inputFieldsController.shareQueriesValidation()
      )
      .addRule(
        ResultPath.parse("/" + Files.GraphQL.Mutations.UPDATE_SHARE),
        inputFieldsController.shareQueriesValidation()
      )
      .addRule(
        ResultPath.parse("/" + Files.GraphQL.Mutations.DELETE_SHARE),
        inputFieldsController.shareQueriesValidation()
      )
      .addRule(
        ResultPath.parse("/" + Files.GraphQL.Mutations.CREATE_LINK),
        inputFieldsController.createLinkValidation()
      )
      .addRule(
        ResultPath.parse("/" + Files.GraphQL.Queries.GET_LINKS),
        inputFieldsController.getLinksValidation()
      )
      .addRule(
        ResultPath.parse("/" + Files.GraphQL.Mutations.UPDATE_LINK),
        inputFieldsController.updateLinkValidation()
      )
      .addRule(
        ResultPath.parse("/" + Files.GraphQL.Mutations.DELETE_LINKS),
        inputFieldsController.deleteLinksValidation()
      )
      .addRule(
        ResultPath.parse("/" + Files.GraphQL.Queries.GET_PATH),
        inputFieldsController.getPathValidation()
      )
      .addRule(
        ResultPath.parse("/" + Files.GraphQL.Queries.GET_ACCOUNT_BY_EMAIL),
        inputFieldsController.getAccountByEmailValidation()
      )
      .addRule(
        ResultPath.parse("/" + Files.GraphQL.Queries.GET_ACCOUNTS_BY_EMAIL),
        inputFieldsController.getAccountsByEmailValidation()
      )
      .addRule(
        ResultPath.parse("/" + Files.GraphQL.Mutations.CREATE_COLLABORATION_LINK),
        inputFieldsController.createCollaborationLinkValidation()
      )
      .addRule(
        ResultPath.parse("/" + Files.GraphQL.Queries.GET_COLLABORATION_LINKS),
        inputFieldsController.getCollaborationLinksValidation()
      )
      .addRule(
        ResultPath.parse("/" + Files.GraphQL.Mutations.DELETE_COLLABORATION_LINKS),
        inputFieldsController.deleteCollaborationLinksValidation()
      );

    return new FieldValidationInstrumentation(fieldValidation);
  }

  /**
   * @return a {@link DataLoaderDispatcherInstrumentation} that allows to enable the registration of
   * {@link BatchLoader}s. By default, the statistics are disabled.
   */
  private DataLoaderDispatcherInstrumentation buildDataLoaderDispatcherInstrumentation() {
    return new DataLoaderDispatcherInstrumentation(
      DataLoaderDispatcherInstrumentationOptions
        .newOptions()
        .includeStatistics(false)
    );
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
      .type(newTypeWiring(Files.GraphQL.Types.NODE_SORT)
        .enumValues(nodeDataFetcher.getNodeSortResolver())
      )
      .type(newTypeWiring(Files.GraphQL.Types.SHARE_PERMISSION)
        .enumValues(shareDataFetcher.getSharePermissionsResolver())
      )
      .type(newTypeWiring(Files.GraphQL.Types.NODE_TYPE)
        .enumValues(nodeDataFetcher.getNodeTypeResolver())
      )
      .type(newTypeWiring("Query")
        .dataFetcher(Files.GraphQL.Queries.GET_NODE, nodeDataFetcher.getNodeFetcher())
        .dataFetcher(Files.GraphQL.Queries.GET_USER, userDataFetcher.getUserFetcher())
        .dataFetcher(Files.GraphQL.Queries.GET_SHARE, shareDataFetcher.getShareFetcher())
        .dataFetcher(Files.GraphQL.Queries.GET_ROOTS_LIST, nodeDataFetcher.getRootsListFetcher())
        .dataFetcher(Files.GraphQL.Queries.GET_PATH, nodeDataFetcher.getPathFetcher())
        .dataFetcher(Files.GraphQL.Queries.FIND_NODES, nodeDataFetcher.findNodesFetcher())
        .dataFetcher(Files.GraphQL.Queries.GET_VERSIONS, nodeDataFetcher.getVersionsFetcher())
        .dataFetcher(
          Files.GraphQL.Queries.GET_ACCOUNT_BY_EMAIL,
          userDataFetcher.getAccountByEmailFetcher()
        )
        .dataFetcher(
          Files.GraphQL.Queries.GET_ACCOUNTS_BY_EMAIL,
          userDataFetcher.getAccountsByEmailFetcher()
        )
        .dataFetcher(Files.GraphQL.Queries.GET_LINKS, linkDataFetcher.getLinks())
        .dataFetcher(
          Files.GraphQL.Queries.GET_COLLABORATION_LINKS,
          collaborationLinkDataFetcher.getCollaborationLinksByNodeId()
        )
        .dataFetcher(Files.GraphQL.Queries.GET_CONFIGS, configDataFetcher.getConfigs())
      )
      .type(newTypeWiring("Mutation")
        .dataFetcher(Files.GraphQL.Mutations.CREATE_FOLDER, nodeDataFetcher.createFolderFetcher())
        .dataFetcher(Files.GraphQL.Mutations.UPDATE_NODE, nodeDataFetcher.updateNodeFetcher())
        .dataFetcher(Files.GraphQL.Mutations.FLAG_NODES, nodeDataFetcher.flagNodes())
        .dataFetcher(Files.GraphQL.Mutations.TRASH_NODES, nodeDataFetcher.trashNodes())
        .dataFetcher(Files.GraphQL.Mutations.RESTORE_NODES, nodeDataFetcher.restoreNodes())
        .dataFetcher(Files.GraphQL.Mutations.MOVE_NODES, nodeDataFetcher.moveNodesFetcher())
        .dataFetcher(Files.GraphQL.Mutations.DELETE_NODES, nodeDataFetcher.deleteNodesFetcher())
        .dataFetcher(
          Files.GraphQL.Mutations.DELETE_VERSIONS,
          nodeDataFetcher.deleteVersionsFetcher()
        )
        .dataFetcher(Files.GraphQL.Mutations.KEEP_VERSIONS, nodeDataFetcher.keepVersionsFetcher())
        .dataFetcher(Files.GraphQL.Mutations.CLONE_VERSION, nodeDataFetcher.cloneVersionFetcher())
        .dataFetcher(Files.GraphQL.Mutations.COPY_NODES, nodeDataFetcher.copyNodesFetcher())
        .dataFetcher(Files.GraphQL.Mutations.CREATE_SHARE, shareDataFetcher.createShareFetcher())
        .dataFetcher(Files.GraphQL.Mutations.UPDATE_SHARE, shareDataFetcher.updateShareFetcher())
        .dataFetcher(Files.GraphQL.Mutations.DELETE_SHARE, shareDataFetcher.deleteShareFetcher())
        .dataFetcher(Files.GraphQL.Mutations.CREATE_LINK, linkDataFetcher.createLink())
        .dataFetcher(Files.GraphQL.Mutations.UPDATE_LINK, linkDataFetcher.updateLink())
        .dataFetcher(Files.GraphQL.Mutations.DELETE_LINKS, linkDataFetcher.deleteLinks())
        .dataFetcher(
          Files.GraphQL.Mutations.CREATE_COLLABORATION_LINK,
          collaborationLinkDataFetcher.createCollaborationLink()
        )
        .dataFetcher(
          Files.GraphQL.Mutations.DELETE_COLLABORATION_LINKS,
          collaborationLinkDataFetcher.deleteCollaborationLinks()
        )
      )
      .type(newTypeWiring(Files.GraphQL.Types.NODE_INTERFACE)
        .typeResolver(nodeDataFetcher.getNodeInterfaceResolver())
      )
      .type(newTypeWiring(Files.GraphQL.Types.FILE)
        .dataFetcher(Files.GraphQL.FileVersion.CREATOR, userDataFetcher.getUserFetcher())
        .dataFetcher(Files.GraphQL.FileVersion.OWNER, userDataFetcher.getUserFetcher())
        .dataFetcher(Files.GraphQL.FileVersion.LAST_EDITOR, userDataFetcher.getUserFetcher())
        .dataFetcher(Files.GraphQL.FileVersion.PARENT, nodeDataFetcher.getNodeFetcher())
        .dataFetcher(
          Files.GraphQL.FileVersion.PERMISSIONS,
          nodeDataFetcher.getPermissionsNodeFetcher()
        )
        .dataFetcher(Files.GraphQL.FileVersion.SHARES, shareDataFetcher.getSharesFetcher())
        .dataFetcher(Files.GraphQL.FileVersion.LINKS, linkDataFetcher.getLinks())
        .dataFetcher(
          Files.GraphQL.FileVersion.COLLABORATION_LINKS,
          collaborationLinkDataFetcher.getCollaborationLinksByNodeId()
        )
      )
      .type(newTypeWiring(Files.GraphQL.Types.FOLDER)
        .dataFetcher(Files.GraphQL.Folder.CREATOR, userDataFetcher.getUserFetcher())
        .dataFetcher(Files.GraphQL.Folder.OWNER, userDataFetcher.getUserFetcher())
        .dataFetcher(Files.GraphQL.Folder.LAST_EDITOR, userDataFetcher.getUserFetcher())
        .dataFetcher(Files.GraphQL.Folder.PARENT, nodeDataFetcher.getNodeFetcher())
        .dataFetcher(Files.GraphQL.Folder.CHILDREN, nodeDataFetcher.getChildNodesFetcherFast())
        .dataFetcher(Files.GraphQL.Folder.PERMISSIONS, nodeDataFetcher.getPermissionsNodeFetcher())
        .dataFetcher(Files.GraphQL.Folder.SHARES, shareDataFetcher.getSharesFetcher())
        .dataFetcher(Files.GraphQL.Folder.LINKS, linkDataFetcher.getLinks())
        .dataFetcher(
          Files.GraphQL.Folder.COLLABORATION_LINKS,
          collaborationLinkDataFetcher.getCollaborationLinksByNodeId()
        )
      )
      .type(newTypeWiring(Files.GraphQL.Types.NODE_PAGE)
        .dataFetcher(Files.GraphQL.NodePage.NODES, nodeDataFetcher.nodePageFetcher())
      )
      .type(newTypeWiring(Files.GraphQL.Types.SHARED_TARGET)
        .typeResolver(userDataFetcher.getAccountTypeResolver())
      )
      .type(newTypeWiring(Files.GraphQL.Types.ACCOUNT)
        .typeResolver(userDataFetcher.getAccountTypeResolver())
      )

      .type(newTypeWiring(Files.GraphQL.Types.SHARE)
        .dataFetcher(Files.GraphQL.Share.NODE, nodeDataFetcher.sharedNodeFetcher())
        .dataFetcher(Files.GraphQL.Share.SHARE_TARGET, userDataFetcher.shareTargetUserFetcher())
      )
      .type(newTypeWiring(Files.GraphQL.Types.DISTRIBUTION_LIST)
        .dataFetcher(Files.GraphQL.DistributionList.USERS, userDataFetcher.getDLUsersFetcher())
      )
      .type(newTypeWiring(Files.GraphQL.Types.LINK)
        .dataFetcher(Files.GraphQL.Link.NODE, nodeDataFetcher.sharedNodeFetcher())
      )
      .type(newTypeWiring(Files.GraphQL.Types.COLLABORATION_LINK)
        .dataFetcher(Files.GraphQL.Link.NODE, nodeDataFetcher.sharedNodeFetcher())
      )
      .build();
  }

  /**
   * Imports the GraphQL schema file (from res folder) and creates the {@link GraphQLSchema} object
   * associating the schema file with the RuntimeWiring object.
   *
   * @param wiring is a {@link RuntimeWiring} that contains the association between the GraphQL
   * components and their specific {@link DataFetcher}s.
   *
   * @return the {@link GraphQLSchema}.
   */
  private GraphQLSchema buildSchema(RuntimeWiring wiring) {
    // Fetch the content of the GraphQL schema file into an InputStreamReader
    InputStream inputStream = getClass().getResourceAsStream(SCHEMA_URL);
    Reader schema = new InputStreamReader(inputStream);

    // Create the GraphQLSchema object
    return new SchemaGenerator().makeExecutableSchema(new SchemaParser().parse(schema), wiring);
  }

  public GraphQL getGraphQL() {
    return this.graphQL;
  }
}
