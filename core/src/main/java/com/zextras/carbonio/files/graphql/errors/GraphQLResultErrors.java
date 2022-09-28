// SPDX-FileCopyrightText: 2022 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.graphql.errors;


import graphql.GraphQLError;
import graphql.GraphqlErrorException;
import graphql.execution.DataFetcherResult;
import graphql.execution.ResultPath;
import java.text.MessageFormat;
import java.util.HashMap;
import java.util.Map;

/**
 * This class is used to generate GraphQL errors to use for {@link DataFetcherResult} when something
 * bad happens inside Data Fetchers.
 */
public class GraphQLResultErrors {

  /**
   * This method generates an error when we could not find data for a requested field that should
   * have been there (ex. entering the distributionList data fetcher when we don't have the id in
   * the context), indicating some error on data on db or wrong usage of datafetchers.
   *
   * @param path the graphQl resultPath extrapolated from the environment to insert into the error
   * to know in which part of the tree the error happened
   *
   * @return
   */
  public static GraphQLError missingField(ResultPath path) {
    Map<String, Object> errorData = new HashMap<>();
    errorData.put("errorCode", ErrorCodes.MISSING_FIELD);
    return GraphqlErrorException.newErrorException()
      .message("Could not find data to retrieve for requested field")
      .path(path.toList())
      .extensions(errorData)
      .build();
  }

  /**
   * This method generates an error when a requested account was not found on Zimbra. Besides from
   * standard data, it adds custom fields on extensions, mainly errorCode, for easily discriminating
   * the error type by who called the api.
   *
   * @param accountIdentifier the accountId/email of the zimbra User or Distribution list not found
   * @param path the graphQl resultPath extrapolated from the environment to insert into the error
   * to know in which part of the tree the error happened
   *
   * @return
   */
  public static GraphQLError accountNotFound(
    String accountIdentifier,
    ResultPath path
  ) {
    Map<String, Object> errorData = new HashMap<>();
    errorData.put("errorCode", ErrorCodes.ACCOUNT_NOT_FOUND);
    errorData.put("identifier", accountIdentifier);
    return GraphqlErrorException.newErrorException()
      .message("Could not find user with identifier " + accountIdentifier)
      .extensions(errorData)
      .path(path.toList())
      .build();
  }

  /**
   * This method generates an error when a requested node was not found. Besides from standard data,
   * it adds custom fields on extensions, mainly errorCode, for easily discriminating the error type
   * by who called the api.
   *
   * @param nodeId the nodeId of the requested node
   * @param path the graphQl resultPath extrapolated from the environment to insert into the error
   * to know in which part of the tree the error happened
   *
   * @return
   */
  public static GraphQLError nodeNotFound(
    String nodeId,
    ResultPath path
  ) {
    Map<String, Object> errorData = new HashMap<>();
    errorData.put("errorCode", ErrorCodes.NODE_NOT_FOUND);
    errorData.put("nodeId", nodeId);
    return GraphqlErrorException.newErrorException()
      .message("Could not find node with id " + nodeId)
      .extensions(errorData)
      .path(path.toList())
      .build();
  }

  /**
   * This method generates an error when a requested node was not found or you don't have permission
   * to write it. This error is used mainly on bulk operations where we provide a list of nodes and
   * both errors could occur but we don't want to give back information on the specific error to
   * avoid giving away data on node existence. Besides from standard data, it adds custom fields on
   * extensions, mainly errorCode, for easily discriminating the error type by who called the api.
   *
   * @param nodeId the nodeId of the requested node
   * @param path the graphQl resultPath extrapolated from the environment to insert into the error
   * to know in which part of the tree the error happened
   *
   * @return
   */
  public static GraphQLError nodeWriteError(
    String nodeId,
    ResultPath path
  ) {
    Map<String, Object> errorData = new HashMap<>();
    errorData.put("errorCode", ErrorCodes.NODE_WRITE_ERROR);
    errorData.put("nodeId", nodeId);
    return GraphqlErrorException.newErrorException()
      .message("There was a problem while executing requested operation on node: " + nodeId)
      .extensions(errorData)
      .path(path.toList())
      .build();
  }

  /**
   * This method generates an error you try to create a Node in a folder in which there is already a
   * Node with the same name. Besides from standard data, it adds custom fields on extensions,
   * mainly errorCode, for easily discriminating the error type by who called the api.
   *
   * @param nodeId the nodeId of the requested node
   * @param destinationFolderId the folder in which there is a duplicate of the Node
   * @param path the graphQl resultPath extrapolated from the environment to insert into the error
   * to know in which part of the tree the error happened
   *
   * @return the error indicating that the Node is a duplicate
   */
  public static GraphQLError duplicateNode(
    String nodeId,
    String destinationFolderId,
    ResultPath path
  ) {
    Map<String, Object> errorData = new HashMap<>();
    errorData.put("errorCode", ErrorCodes.NODE_DUPLICATED);
    errorData.put("nodeId", nodeId);
    errorData.put("destinationFolderId", destinationFolderId);
    return GraphqlErrorException.newErrorException()
      .message("Trying to create a duplicate for the node "
        + nodeId
        + " in destination folder "
        + destinationFolderId
      )
      .extensions(errorData)
      .path(path.toList())
      .build();
  }

  /**
   * This method generates an error when a requested version is not found for a specific node.
   * Besides from standard data, it adds custom fields on extensions, mainly errorCode, for easily
   * discriminating the error type by who called the api.
   *
   * @param nodeId the id of the node for which the version was requested
   * @param fileVersion the number of the version requested
   * @param path the graphQl resultPath extrapolated from the environment to insert into the error
   * to know in which part of the tree the error happened
   *
   * @return
   */
  public static GraphQLError fileVersionNotFound(
    String nodeId,
    Integer fileVersion,
    ResultPath path
  ) {
    Map<String, Object> errorData = new HashMap<>();
    errorData.put("errorCode", ErrorCodes.FILE_VERSION_NOT_FOUND);
    errorData.put("nodeId", nodeId);
    errorData.put("fileVersion", fileVersion);
    return GraphqlErrorException.newErrorException()
      .message("Could not find version: " + fileVersion + " for node with id " + nodeId)
      .extensions(errorData)
      .path(path.toList())
      .build();
  }

  /**
   * This method generates an error when a requested share was not found on DB. Besides from
   * standard data, it adds custom fields on extensions, mainly errorCode, for easily discriminating
   * the error type by who called the api.
   *
   * @param nodeId the id of the share not found
   * @param path the graphQl resultPath extrapolated from the environment to insert into the error
   * to know in which part of the tree the error happened
   *
   * @return
   */
  public static GraphQLError shareNotfound(
    String nodeId,
    String userId,
    ResultPath path
  ) {
    Map<String, Object> errorData = new HashMap<>();
    errorData.put("errorCode", ErrorCodes.SHARE_NOT_FOUND);
    errorData.put("nodeId", nodeId);
    errorData.put("userId", userId);
    return GraphqlErrorException.newErrorException()
      .message("Could not find share for node: " + nodeId + " and user " + userId)
      .extensions(errorData)
      .path(path.toList())
      .build();
  }

  /**
   * This method generates an error when failing to create a requested share. Besides from standard
   * data, it adds custom fields on extensions, mainly errorCode, for easily discriminating the
   * error type by who called the api.
   *
   * @param nodeId the id of the node where i was creating the share
   * @param targetUserId the id of the user for which i was creating the share
   * @param path the graphQl resultPath extrapolated from the environment to insert into the error
   * to know in which part of the tree the error happened
   *
   * @return
   */
  public static GraphQLError shareCreationError(
    String nodeId,
    String targetUserId,
    ResultPath path
  ) {
    Map<String, Object> errorData = new HashMap<>();
    errorData.put("errorCode", ErrorCodes.SHARE_CREATION_ERROR);
    errorData.put("nodeId", nodeId);
    errorData.put("userId", targetUserId);
    return GraphqlErrorException.newErrorException()
      .message("Could not create share for node: " + nodeId + " and user: " + targetUserId)
      .extensions(errorData)
      .path(path.toList())
      .build();
  }

  /**
   * This method generates an error when a requested link was not found. Besides from standard data,
   * it adds the following custom fields in the extensions attribute:
   * <ul>
   *   <li>the errorCode for easily discriminating</li>
   *   <li>the id of the link not found</li>
   * </ul>
   * .
   *
   * @param linkId the link identifier of the requested link.
   * @param path the graphQl resultPath extrapolated from the environment to insert into the error
   * to know in which part of the tree the error happened
   *
   * @return
   */
  public static GraphQLError linkNotFound(
    String linkId,
    ResultPath path
  ) {
    Map<String, Object> errorData = new HashMap<>();
    errorData.put("errorCode", ErrorCodes.LINK_NOT_FOUND);
    errorData.put("linkId", linkId);
    return GraphqlErrorException.newErrorException()
      .message("Could not find link with id " + linkId)
      .extensions(errorData)
      .path(path.toList())
      .build();
  }

  /**
   * This method generates an error when a requested node has reached the maximum number of
   * versions. Besides from standard data, it adds the following custom fields in the extensions
   * attribute:
   * * <ul>
   * *   <li>the errorCode for easily discriminating</li>
   * *   <li>the id of the link not found</li>
   * * </ul>
   *
   * @param nodeId the nodeId of the requested node
   * @param path the graphQl resultPath extrapolated from the environment to insert into the error
   * to know in which part of the tree the error happened
   *
   * @return
   */
  public static GraphQLError tooManyVersionsError(
    String nodeId,
    ResultPath path
  ) {
    Map<String, Object> errorData = new HashMap<>();
    errorData.put("errorCode", ErrorCodes.VERSIONS_LIMIT_REACHED);
    errorData.put("nodeId", nodeId);
    return GraphqlErrorException.newErrorException()
      .message("There was a problem while executing requested operation on node: " + nodeId)
      .extensions(errorData)
      .path(path.toList())
      .build();
  }
  /**
   * This method generates an error when a copy of a node fails. In addition to the usual data, it
   * adds custom fields on extensions, mainly <code>errorCode</code>, for easily discriminating
   * the error type by who called the API.
   *
   * @param nodeId is a {@link String} representing the id of the requested node.
   * @param version is an {@link Integer} representing the version of the requested node.
   * @param path is a {@link ResultPath } extrapolated from the environment to insert into the error
   * to know in which part of the tree the error happened.
   *
   * @return a {@link GraphQLError} containing info about the error.
   */
  public static GraphQLError nodeCopyError(
    String nodeId,
    Integer version,
    ResultPath path
  ) {
    Map<String, Object> errorData = new HashMap<>();
    errorData.put("errorCode", ErrorCodes.NODE_COPY_ERROR);
    errorData.put("nodeId", nodeId);
    errorData.put("version", version);

    String errorMessage = MessageFormat.format(
      "There was a problem while copying the node {0} with version {1}",
      nodeId,
      version
    );

    return GraphqlErrorException
      .newErrorException()
      .message(errorMessage)
      .extensions(errorData)
      .path(path.toList())
      .build();
  }

}
