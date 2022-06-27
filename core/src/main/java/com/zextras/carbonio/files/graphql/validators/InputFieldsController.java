// SPDX-FileCopyrightText: 2022 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.graphql.validators;

import com.google.inject.Inject;
import com.zextras.carbonio.files.Files;
import com.zextras.carbonio.files.Files.GraphQL.InputParameters.RestoreNodes;
import com.zextras.carbonio.files.graphql.GraphQLProvider;
import graphql.GraphQL;
import graphql.GraphQLError;
import graphql.execution.instrumentation.fieldvalidation.FieldAndArguments;
import graphql.execution.instrumentation.fieldvalidation.FieldValidationEnvironment;
import java.util.List;
import java.util.Optional;
import java.util.function.BiFunction;

/**
 * <p>This class contains the implementations of all input validation rules necessary to check if
 * specific inputs values of a GraphQL request are correct. Each method builds a
 * {@link GenericControllerEvaluator} specific for the related inputs to check and returns an
 * implementation of a {@link BiFunction} having the {@link FieldValidationEnvironment} and the
 * {@link FieldAndArguments} objects as input and an {@link Optional} of {@link GraphQLError} as
 * output. These methods are used during the creation of the {@link GraphQL} instance (see
 * {@link GraphQLProvider}
 * <code>buildValidationInstrumentation()</code> method).</p>
 */
public class InputFieldsController {

  private final GenericControllerEvaluatorFactory mGenericControllerEvaluatorFactory;

  @Inject
  public InputFieldsController(GenericControllerEvaluatorFactory genericControllerEvaluatorFactory) {
    mGenericControllerEvaluatorFactory = genericControllerEvaluatorFactory;
  }

  /**
   * @return a {@link BiFunction} rule bound with the {@link Files.GraphQL.Queries#GET_NODE} to
   * check if the node id in input is valid.
   * @see GenericControllerEvaluator#checkNodeId(String)
   */
  public BiFunction<FieldAndArguments, FieldValidationEnvironment, Optional<GraphQLError>> getNodeValidation() {
    return (fieldAndArguments, environment) ->
    {
      return mGenericControllerEvaluatorFactory.create(fieldAndArguments, environment)
        .checkNodeId(Files.GraphQL.InputParameters.NODE_ID)
        .evaluate();
    };
  }

  /**
   * @return a {@link BiFunction} rule bound with the {@link Files.GraphQL.Mutations#CREATE_FOLDER}
   * to check if the parent id is valid and if the folder name in input is valid.
   * @see GenericControllerEvaluator#checkNodeId(String)
   * @see GenericControllerEvaluator#checkNodeName(String)
   */
  public BiFunction<FieldAndArguments, FieldValidationEnvironment, Optional<GraphQLError>> createFolderValidation() {
    return (fieldAndArguments, environment) ->
    {
      return mGenericControllerEvaluatorFactory.create(fieldAndArguments, environment)
        .checkNodeId(Files.GraphQL.InputParameters.CreateFolder.PARENT_ID)
        .checkNodeName(Files.GraphQL.InputParameters.CreateFolder.NAME)
        .evaluate();
    };
  }

  /**
   * @return a {@link BiFunction} rule bound with the {@link Files.GraphQL.Folder#CHILDREN}
   * attribute to checks if the pagination limit is valid and if the cursor node is valid.
   * @see GenericControllerEvaluator#checkLimitPagination(String)
   * @see GenericControllerEvaluator#checkNodeId(String)
   */
  public BiFunction<FieldAndArguments, FieldValidationEnvironment, Optional<GraphQLError>> childrenArgumentValidation() {
    return ((fieldAndArguments, environment) ->
    {
      return mGenericControllerEvaluatorFactory.create(fieldAndArguments, environment)
        .checkLimitPagination(Files.GraphQL.InputParameters.LIMIT)
        .checkNodeId(Files.GraphQL.InputParameters.CURSOR)
        .evaluate();
    });
  }

  /**
   * @return a {@link BiFunction} rule bound with the {@link Files.GraphQL.Mutations#UPDATE_NODE} to
   * check if the id, the name and/or the description of the node are valid.
   * @see GenericControllerEvaluator#checkNodeId(String)
   * @see GenericControllerEvaluator#checkNodeName(String)
   * @see GenericControllerEvaluator#checkNodeDescription(String)
   */
  public BiFunction<FieldAndArguments, FieldValidationEnvironment, Optional<GraphQLError>> updateNodeValidation() {
    return ((fieldAndArguments, environment) ->
    {
      return mGenericControllerEvaluatorFactory.create(fieldAndArguments, environment)
        .checkNodeId(Files.GraphQL.InputParameters.UpdateNode.NODE_ID)
        .checkNodeName(Files.GraphQL.InputParameters.UpdateNode.NAME)
        .checkNodeDescription(Files.GraphQL.InputParameters.UpdateNode.DESCRIPTION)
        .evaluate();
    });
  }

  /**
   * @return a {@link BiFunction} rule bound with the {@link Files.GraphQL.Mutations#MOVE_NODES} to
   * check if the ids of the nodes to move and the destination are valid.
   * @see GenericControllerEvaluator#checkNodesIds(String)
   * @see GenericControllerEvaluator#checkNodeId(String)
   */
  public BiFunction<FieldAndArguments, FieldValidationEnvironment, Optional<GraphQLError>> moveNodesValidation() {
    return ((fieldAndArguments, environment) ->
    {
      return mGenericControllerEvaluatorFactory.create(fieldAndArguments, environment)
        .checkNodesIds(Files.GraphQL.InputParameters.MoveNodes.NODE_IDS)
        .checkNodeId(Files.GraphQL.InputParameters.MoveNodes.DESTINATION_ID)
        .evaluate();
    });
  }

  /**
   * @return a {@link BiFunction} rule bound with the {@link Files.GraphQL.Mutations#DELETE_NODES}
   * to check if the ids of the nodes to delete are valid.
   * @see GenericControllerEvaluator#checkNodesIds(String)
   */
  public BiFunction<FieldAndArguments, FieldValidationEnvironment, Optional<GraphQLError>> deleteNodesValidation() {
    return ((fieldAndArguments, environment) ->
      mGenericControllerEvaluatorFactory.create(fieldAndArguments, environment)
        .checkNodesIds(Files.GraphQL.InputParameters.DeleteNodes.NODE_IDS)
        .evaluate()
    );
  }

  /**
   * @return a {@link BiFunction} rule bound with the {@link Files.GraphQL.Mutations#TRASH_NODES} to
   * check if the ids of the nodes to trash are valid.
   * @see GenericControllerEvaluator#checkNodesIds(String)
   */
  public BiFunction<FieldAndArguments, FieldValidationEnvironment, Optional<GraphQLError>> trashNodesValidation() {
    return ((fieldAndArguments, environment) ->
      mGenericControllerEvaluatorFactory.create(fieldAndArguments, environment)
        .checkNodesIds(Files.GraphQL.InputParameters.TrashNodes.NODE_IDS)
        .evaluate()
    );
  }

  /**
   * @return a {@link BiFunction} rule bound with the {@link Files.GraphQL.Mutations#RESTORE_NODES}
   * to check if the ids of the nodes to delete are valid.
   * @see GenericControllerEvaluator#checkNodesIds(String)
   */
  public BiFunction<FieldAndArguments, FieldValidationEnvironment, Optional<GraphQLError>> restoreNodesValidation() {
    return ((fieldAndArguments, environment) ->
      mGenericControllerEvaluatorFactory.create(fieldAndArguments, environment)
        .checkNodesIds(RestoreNodes.NODE_IDS)
        .evaluate()
    );
  }

  /**
   * @return a {@link BiFunction} rule bound with the {@link Files.GraphQL.Mutations#COPY_NODES} to
   * check if the ids of the nodes to move and the destination are valid.
   * @see GenericControllerEvaluator#checkNodesIds(String)
   * @see GenericControllerEvaluator#checkNodeId(String)
   */
  public BiFunction<FieldAndArguments, FieldValidationEnvironment, Optional<GraphQLError>> copyNodesValidation() {
    return ((fieldAndArguments, environment) ->
      mGenericControllerEvaluatorFactory.create(fieldAndArguments, environment)
        .checkNodesIds(Files.GraphQL.InputParameters.CopyNodes.NODE_IDS)
        .checkNodeId(Files.GraphQL.InputParameters.CopyNodes.DESTINATION_ID)
        .evaluate());
  }

  /**
   * @return a {@link BiFunction} rule bound with the queries and mutations related to the
   * {@link Files.GraphQL.Types#SHARE} type check if the node id and the target user id in input are
   * valid.
   * @see GenericControllerEvaluator#checkNodeId(String)
   * @see GenericControllerEvaluator#checkUserId(String)
   */
  public BiFunction<FieldAndArguments, FieldValidationEnvironment, Optional<GraphQLError>> shareQueriesValidation() {
    return (fieldAndArguments, environment) ->
    {
      GenericControllerEvaluator controller = mGenericControllerEvaluatorFactory
        .create(fieldAndArguments, environment)
        .checkNodeId(Files.GraphQL.InputParameters.Share.NODE_ID)
        .checkUserId(Files.GraphQL.InputParameters.Share.SHARE_TARGET_ID);

      return controller.evaluate();
    };
  }

  /**
   * @return a {@link BiFunction} rule bound with the {@link Files.GraphQL.Mutations#CREATE_LINK} to
   * check if the node id and/or the link description are valid.
   * @see GenericControllerEvaluator#checkNodeId(String)
   * @see GenericControllerEvaluator#checkLinkDescription(String)
   */
  public BiFunction<FieldAndArguments, FieldValidationEnvironment, Optional<GraphQLError>> createLinkValidation() {
    return (fieldAndArguments, environment) ->
    {
      return mGenericControllerEvaluatorFactory.create(fieldAndArguments, environment)
        .checkNodeId(Files.GraphQL.InputParameters.Link.NODE_ID)
        .checkLinkDescription(Files.GraphQL.InputParameters.Link.DESCRIPTION)
        .evaluate();
    };
  }

  /**
   * @return a {@link BiFunction} rule bound with the {@link Files.GraphQL.Queries#GET_LINKS} to
   * check if the node id of the links to retrieve is valid.
   * @see GenericControllerEvaluator#checkNodeId(String)
   */
  public BiFunction<FieldAndArguments, FieldValidationEnvironment, Optional<GraphQLError>> getLinksValidation() {
    return (fieldAndArguments, environment) ->
    {
      return mGenericControllerEvaluatorFactory.create(fieldAndArguments, environment)
        .checkNodeId(Files.GraphQL.InputParameters.Link.NODE_ID)
        .evaluate();
    };
  }

  /**
   * @return a {@link BiFunction} rule bound with the {@link Files.GraphQL.Mutations#UPDATE_LINK} to
   * check if the link id and/or the link description are valid.
   * @see GenericControllerEvaluator#checkLinkId(String)
   * @see GenericControllerEvaluator#checkLinkDescription(String)
   */
  public BiFunction<FieldAndArguments, FieldValidationEnvironment, Optional<GraphQLError>> updateLinkValidation() {
    return (fieldAndArguments, environment) ->
    {
      return mGenericControllerEvaluatorFactory.create(fieldAndArguments, environment)
        .checkLinkId(Files.GraphQL.InputParameters.Link.LINK_ID)
        .checkLinkDescription(Files.GraphQL.InputParameters.Link.DESCRIPTION)
        .evaluate();
    };
  }

  /**
   * @return a {@link BiFunction} rule bound with the {@link Files.GraphQL.Mutations#DELETE_LINKS}
   * to check if the link ids to remove are valid.
   * @see GenericControllerEvaluator#checkLinkIds(String)
   */
  public BiFunction<FieldAndArguments, FieldValidationEnvironment, Optional<GraphQLError>> deleteLinksValidation() {
    return (fieldAndArguments, environment) ->
    {
      return mGenericControllerEvaluatorFactory.create(fieldAndArguments, environment)
        .checkLinkIds(Files.GraphQL.InputParameters.Link.LINK_IDS)
        .evaluate();
    };
  }

  /**
   * @return a {@link BiFunction} rule bound with the {@link Files.GraphQL.Queries#GET_PATH} to
   * check if the node id in input is valid.
   * @see GenericControllerEvaluator#checkNodeId(String)
   */
  public BiFunction<FieldAndArguments, FieldValidationEnvironment, Optional<GraphQLError>> getPathValidation() {
    return (fieldAndArguments, environment) ->
    {
      return mGenericControllerEvaluatorFactory.create(fieldAndArguments, environment)
        .checkNodeId(Files.GraphQL.InputParameters.NODE_ID)
        .evaluate();
    };
  }

  /**
   * @return a {@link BiFunction} rule bound with the
   * {@link Files.GraphQL.Queries#GET_ACCOUNT_BY_EMAIL} to check if the email in input is valid.
   * @see GenericControllerEvaluator#checkEmail(String)
   */
  public BiFunction<FieldAndArguments, FieldValidationEnvironment, Optional<GraphQLError>> getAccountByEmailValidation() {
    return (fieldAndArguments, environment) ->
    {
      return mGenericControllerEvaluatorFactory.create(fieldAndArguments, environment)
        .checkEmail(Files.GraphQL.InputParameters.EMAIL)
        .evaluate();
    };
  }

  /**
   * @return a {@link BiFunction} rule bound with the
   * {@link Files.GraphQL.Queries#GET_ACCOUNTS_BY_EMAIL} to check if the list of emails in input are
   * valid.
   * @see GenericControllerEvaluator#checkEmails(String)
   */
  public BiFunction<FieldAndArguments, FieldValidationEnvironment, Optional<GraphQLError>> getAccountsByEmailValidation() {
    return (fieldAndArguments, environment) ->
      mGenericControllerEvaluatorFactory.create(fieldAndArguments, environment)
        .checkEmails(Files.GraphQL.InputParameters.GetAccountsByEmail.EMAILS)
        .evaluate();
  }
}
