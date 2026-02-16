// SPDX-FileCopyrightText: 2022 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.graphql.validators;

import com.google.inject.Inject;
import com.zextras.carbonio.files.Constants;
import com.zextras.carbonio.files.Constants.GraphQL.InputParameters.DeleteCollaborationLinks;
import com.zextras.carbonio.files.Constants.GraphQL.InputParameters.RestoreNodes;
import com.zextras.carbonio.files.graphql.GraphQLProvider;
import graphql.GraphQL;
import graphql.GraphQLError;
import graphql.execution.instrumentation.fieldvalidation.FieldAndArguments;
import graphql.execution.instrumentation.fieldvalidation.FieldValidationEnvironment;
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
   * @return a {@link BiFunction} rule bound with the {@link Constants.GraphQL.Queries#GET_NODE} to
   * check if the node id in input is valid.
   * @see GenericControllerEvaluator#checkNodeId(String)
   */
  public BiFunction<FieldAndArguments, FieldValidationEnvironment, Optional<GraphQLError>> getNodeValidation() {
    return (fieldAndArguments, environment) ->
    {
      return mGenericControllerEvaluatorFactory.create(fieldAndArguments, environment)
        .checkNodeId(Constants.GraphQL.InputParameters.NODE_ID)
        .evaluate();
    };
  }

  /**
   * @return a {@link BiFunction} rule bound with the {@link Constants.GraphQL.Mutations#CREATE_FOLDER}
   * to check if the parent id is valid and if the folder name in input is valid.
   * @see GenericControllerEvaluator#checkNodeId(String)
   * @see GenericControllerEvaluator#checkNodeName(String)
   */
  public BiFunction<FieldAndArguments, FieldValidationEnvironment, Optional<GraphQLError>> createFolderValidation() {
    return (fieldAndArguments, environment) ->
    {
      return mGenericControllerEvaluatorFactory.create(fieldAndArguments, environment)
        .checkNodeId(Constants.GraphQL.InputParameters.CreateFolder.PARENT_ID)
        .checkNodeName(Constants.GraphQL.InputParameters.CreateFolder.NAME)
        .evaluate();
    };
  }

  /**
   * @return a {@link BiFunction} rule bound with the {@link Constants.GraphQL.Folder#CHILDREN}
   * attribute to checks if the pagination limit is valid and if the cursor node is valid.
   * @see GenericControllerEvaluator#checkLimitPagination(String)
   * @see GenericControllerEvaluator#checkNodeId(String)
   */
  public BiFunction<FieldAndArguments, FieldValidationEnvironment, Optional<GraphQLError>> childrenArgumentValidation() {
    return ((fieldAndArguments, environment) ->
    {
      return mGenericControllerEvaluatorFactory.create(fieldAndArguments, environment)
        .checkLimitPagination(Constants.GraphQL.InputParameters.LIMIT)
        .checkNodeId(Constants.GraphQL.InputParameters.CURSOR)
        .evaluate();
    });
  }

  /**
   * @return a {@link BiFunction} rule bound with the {@link Constants.GraphQL.Mutations#UPDATE_NODE} to
   * check if the id, the name and/or the description of the node are valid.
   * @see GenericControllerEvaluator#checkNodeId(String)
   * @see GenericControllerEvaluator#checkNodeName(String)
   * @see GenericControllerEvaluator#checkNodeDescription(String)
   */
  public BiFunction<FieldAndArguments, FieldValidationEnvironment, Optional<GraphQLError>> updateNodeValidation() {
    return ((fieldAndArguments, environment) ->
    {
      return mGenericControllerEvaluatorFactory.create(fieldAndArguments, environment)
        .checkNodeId(Constants.GraphQL.InputParameters.UpdateNode.NODE_ID)
        .checkNodeName(Constants.GraphQL.InputParameters.UpdateNode.NAME)
        .checkNodeDescription(Constants.GraphQL.InputParameters.UpdateNode.DESCRIPTION)
        .evaluate();
    });
  }

  /**
   * @return a {@link BiFunction} rule bound with the {@link Constants.GraphQL.Mutations#MOVE_NODES} to
   * check if the ids of the nodes to move and the destination are valid.
   * @see GenericControllerEvaluator#checkNodesIds(String)
   * @see GenericControllerEvaluator#checkNodeId(String)
   */
  public BiFunction<FieldAndArguments, FieldValidationEnvironment, Optional<GraphQLError>> moveNodesValidation() {
    return ((fieldAndArguments, environment) ->
    {
      return mGenericControllerEvaluatorFactory.create(fieldAndArguments, environment)
        .checkNodesIds(Constants.GraphQL.InputParameters.MoveNodes.NODE_IDS)
        .checkNodeId(Constants.GraphQL.InputParameters.MoveNodes.DESTINATION_ID)
        .evaluate();
    });
  }

  /**
   * @return a {@link BiFunction} rule bound with the {@link Constants.GraphQL.Mutations#DELETE_NODES}
   * to check if the ids of the nodes to delete are valid.
   * @see GenericControllerEvaluator#checkNodesIds(String)
   */
  public BiFunction<FieldAndArguments, FieldValidationEnvironment, Optional<GraphQLError>> deleteNodesValidation() {
    return ((fieldAndArguments, environment) ->
      mGenericControllerEvaluatorFactory.create(fieldAndArguments, environment)
        .checkNodesIds(Constants.GraphQL.InputParameters.DeleteNodes.NODE_IDS)
        .evaluate()
    );
  }

  /**
   * @return a {@link BiFunction} rule bound with the {@link Constants.GraphQL.Mutations#TRASH_NODES} to
   * check if the ids of the nodes to trash are valid.
   * @see GenericControllerEvaluator#checkNodesIds(String)
   */
  public BiFunction<FieldAndArguments, FieldValidationEnvironment, Optional<GraphQLError>> trashNodesValidation() {
    return ((fieldAndArguments, environment) ->
      mGenericControllerEvaluatorFactory.create(fieldAndArguments, environment)
        .checkNodesIds(Constants.GraphQL.InputParameters.TrashNodes.NODE_IDS)
        .evaluate()
    );
  }

  /**
   * @return a {@link BiFunction} rule bound with the {@link Constants.GraphQL.Mutations#RESTORE_NODES}
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
   * @return a {@link BiFunction} rule bound with the {@link Constants.GraphQL.Mutations#COPY_NODES} to
   * check if the ids of the nodes to move and the destination are valid.
   * @see GenericControllerEvaluator#checkNodesIds(String)
   * @see GenericControllerEvaluator#checkNodeId(String)
   */
  public BiFunction<FieldAndArguments, FieldValidationEnvironment, Optional<GraphQLError>> copyNodesValidation() {
    return ((fieldAndArguments, environment) ->
      mGenericControllerEvaluatorFactory.create(fieldAndArguments, environment)
        .checkNodesIds(Constants.GraphQL.InputParameters.CopyNodes.NODE_IDS)
        .checkNodeId(Constants.GraphQL.InputParameters.CopyNodes.DESTINATION_ID)
        .evaluate());
  }

  /**
   * @return a {@link BiFunction} rule bound with the queries and mutations related to the
   * {@link Constants.GraphQL.Types#SHARE} type check if the node id and the target user id in input are
   * valid.
   * @see GenericControllerEvaluator#checkNodeId(String)
   * @see GenericControllerEvaluator#checkUserId(String)
   */
  public BiFunction<FieldAndArguments, FieldValidationEnvironment, Optional<GraphQLError>> shareQueriesValidation() {
    return (fieldAndArguments, environment) ->
    {
      GenericControllerEvaluator controller = mGenericControllerEvaluatorFactory
        .create(fieldAndArguments, environment)
        .checkNodeId(Constants.GraphQL.InputParameters.Share.NODE_ID)
        .checkUserId(Constants.GraphQL.InputParameters.Share.SHARE_TARGET_ID);

      return controller.evaluate();
    };
  }

  /**
   * @return a {@link BiFunction} rule bound with the {@link Constants.GraphQL.Mutations#UPDATE_SHARES}
   * and {@link Constants.GraphQL.Mutations#DELETE_SHARES} mutations to check if the node id and the
   * target user ids in input are valid.
   * @see GenericControllerEvaluator#checkNodeId(String)
   * @see GenericControllerEvaluator#checkUserIds(String)
   */
  public BiFunction<FieldAndArguments, FieldValidationEnvironment, Optional<GraphQLError>> bulkShareQueriesValidation() {
    return (fieldAndArguments, environment) ->
    {
      GenericControllerEvaluator controller = mGenericControllerEvaluatorFactory
        .create(fieldAndArguments, environment)
        .checkNodeId(Constants.GraphQL.InputParameters.Share.NODE_ID)
        .checkUserIds(Constants.GraphQL.InputParameters.Share.SHARE_TARGET_IDS);

      return controller.evaluate();
    };
  }

  /**
   * @return a {@link BiFunction} rule bound with the {@link Constants.GraphQL.Mutations#CREATE_LINK} to
   * check if the node id and/or the link description and/or the access code are valid.
   * @see GenericControllerEvaluator#checkNodeId(String)
   * @see GenericControllerEvaluator#checkLinkDescription(String)
   * @see GenericControllerEvaluator#checkLinkAccessCode(String)
   */
  public BiFunction<FieldAndArguments, FieldValidationEnvironment, Optional<GraphQLError>> createLinkValidation() {
    return (fieldAndArguments, environment) ->
    {
      return mGenericControllerEvaluatorFactory.create(fieldAndArguments, environment)
        .checkNodeId(Constants.GraphQL.InputParameters.Link.NODE_ID)
        .checkLinkDescription(Constants.GraphQL.InputParameters.Link.DESCRIPTION)
        .checkLinkAccessCode(Constants.GraphQL.InputParameters.Link.ACCESS_CODE)
        .evaluate();
    };
  }

  /**
   * @return a {@link BiFunction} rule bound with the {@link Constants.GraphQL.Queries#GET_LINKS} to
   * check if the node id of the links to retrieve is valid.
   * @see GenericControllerEvaluator#checkNodeId(String)
   */
  public BiFunction<FieldAndArguments, FieldValidationEnvironment, Optional<GraphQLError>> getLinksValidation() {
    return (fieldAndArguments, environment) ->
    {
      return mGenericControllerEvaluatorFactory.create(fieldAndArguments, environment)
        .checkNodeId(Constants.GraphQL.InputParameters.Link.NODE_ID)
        .evaluate();
    };
  }

  /**
   * @return a {@link BiFunction} rule bound with the {@link Constants.GraphQL.Mutations#UPDATE_LINK} to
   * check if the link id and/or the link description and/or the access code are valid.
   * @see GenericControllerEvaluator#checkLinkId(String)
   * @see GenericControllerEvaluator#checkLinkDescription(String)
   * @see GenericControllerEvaluator#checkLinkAccessCode(String)
   */
  public BiFunction<FieldAndArguments, FieldValidationEnvironment, Optional<GraphQLError>> updateLinkValidation() {
    return (fieldAndArguments, environment) ->
    {
      return mGenericControllerEvaluatorFactory.create(fieldAndArguments, environment)
        .checkLinkId(Constants.GraphQL.InputParameters.Link.LINK_ID)
        .checkLinkDescription(Constants.GraphQL.InputParameters.Link.DESCRIPTION)
        .checkLinkAccessCode(Constants.GraphQL.InputParameters.Link.ACCESS_CODE)
        .evaluate();
    };
  }

  /**
   * @return a {@link BiFunction} rule bound with the {@link Constants.GraphQL.Mutations#DELETE_LINKS}
   * to check if the link ids to remove are valid.
   * @see GenericControllerEvaluator#checkLinkIds(String)
   */
  public BiFunction<FieldAndArguments, FieldValidationEnvironment, Optional<GraphQLError>> deleteLinksValidation() {
    return (fieldAndArguments, environment) ->
    {
      return mGenericControllerEvaluatorFactory.create(fieldAndArguments, environment)
        .checkLinkIds(Constants.GraphQL.InputParameters.Link.LINK_IDS)
        .evaluate();
    };
  }

  /**
   * @return a {@link BiFunction} rule bound with the {@link Constants.GraphQL.Queries#GET_PATH} to
   * check if the node id in input is valid.
   * @see GenericControllerEvaluator#checkNodeId(String)
   */
  public BiFunction<FieldAndArguments, FieldValidationEnvironment, Optional<GraphQLError>> getPathValidation() {
    return (fieldAndArguments, environment) ->
    {
      return mGenericControllerEvaluatorFactory.create(fieldAndArguments, environment)
        .checkNodeId(Constants.GraphQL.InputParameters.NODE_ID)
        .evaluate();
    };
  }

  /**
   * @return a {@link BiFunction} rule bound with the
   * {@link Constants.GraphQL.Queries#GET_ACCOUNT_BY_EMAIL} to check if the email in input is valid.
   * @see GenericControllerEvaluator#checkEmail(String)
   */
  public BiFunction<FieldAndArguments, FieldValidationEnvironment, Optional<GraphQLError>> getAccountByEmailValidation() {
    return (fieldAndArguments, environment) ->
    {
      return mGenericControllerEvaluatorFactory.create(fieldAndArguments, environment)
        .checkEmail(Constants.GraphQL.InputParameters.EMAIL)
        .evaluate();
    };
  }

  /**
   * @return a {@link BiFunction} rule bound with the
   * {@link Constants.GraphQL.Queries#GET_ACCOUNTS_BY_EMAIL} to check if the list of emails in input are
   * valid.
   * @see GenericControllerEvaluator#checkEmails(String)
   */
  public BiFunction<FieldAndArguments, FieldValidationEnvironment, Optional<GraphQLError>> getAccountsByEmailValidation() {
    return (fieldAndArguments, environment) ->
      mGenericControllerEvaluatorFactory.create(fieldAndArguments, environment)
        .checkEmails(Constants.GraphQL.InputParameters.GetAccountsByEmail.EMAILS)
        .evaluate();
  }

  /**
   * @return a {@link BiFunction} rule bound with the
   * {@link Constants.GraphQL.Mutations#CREATE_COLLABORATION_LINK} to check if the node id is valid.
   * @see GenericControllerEvaluator#checkNodeId(String)
   */
  public BiFunction<FieldAndArguments, FieldValidationEnvironment, Optional<GraphQLError>> createCollaborationLinkValidation() {
    return (fieldAndArguments, environment) ->
      mGenericControllerEvaluatorFactory
        .create(fieldAndArguments, environment)
        .checkNodeId(Constants.GraphQL.InputParameters.CreateCollaborationLink.NODE_ID)
        .evaluate();
  }

  /**
   * @return a {@link BiFunction} rule bound with the
   * {@link Constants.GraphQL.Queries#GET_COLLABORATION_LINKS} to check if the node id of the
   * collaboration links to retrieve is valid.
   * @see GenericControllerEvaluator#checkNodeId(String)
   */
  public BiFunction<FieldAndArguments, FieldValidationEnvironment, Optional<GraphQLError>> getCollaborationLinksValidation() {
    return (fieldAndArguments, environment) ->
      mGenericControllerEvaluatorFactory
        .create(fieldAndArguments, environment)
        .checkNodeId(Constants.GraphQL.InputParameters.GetCollaborationLink.NODE_ID)
        .evaluate();
  }

  /**
   * @return a {@link BiFunction} rule bound with the
   * {@link Constants.GraphQL.Mutations#DELETE_COLLABORATION_LINKS} to check if the collaboration link
   * ids to remove are valid.
   * @see GenericControllerEvaluator#checkLinkIds(String)
   */
  public BiFunction<FieldAndArguments, FieldValidationEnvironment, Optional<GraphQLError>> deleteCollaborationLinksValidation() {
    return (fieldAndArguments, environment) ->
      mGenericControllerEvaluatorFactory
        .create(fieldAndArguments, environment)
        .checkLinkIds(DeleteCollaborationLinks.COLLABORATION_LINK_IDS)
        .evaluate();
  }

  /**
   * @return a {@link BiFunction} rule bound with the
   * {@link Constants.GraphQL.Mutations#DELETE_ALL_NODES_AND_BLOBS} to check if the user id is not empty or null.
   */
  public BiFunction<FieldAndArguments, FieldValidationEnvironment, Optional<GraphQLError>> deleteAllNodesAndBlobsValidation() {
    return (fieldAndArguments, environment) ->
      mGenericControllerEvaluatorFactory.create(fieldAndArguments, environment)
        .checkUserId(Constants.GraphQL.InputParameters.DeleteAllNodesAndBlobs.USER_ID)
        .evaluate();
  }
}
