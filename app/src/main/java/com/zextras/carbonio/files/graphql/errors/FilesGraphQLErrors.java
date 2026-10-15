// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.graphql.errors;

import java.text.MessageFormat;
import java.util.LinkedHashMap;
import java.util.Map;

public final class FilesGraphQLErrors {

  private FilesGraphQLErrors() {}

  public static FilesGraphQLException missingField() {
    return build(ErrorCodes.MISSING_FIELD, "Could not find data to retrieve for requested field");
  }

  public static FilesGraphQLException accountNotFound(String identifier) {
    return build(
        ErrorCodes.ACCOUNT_NOT_FOUND,
        "Could not find user with identifier " + identifier,
        "identifier",
        identifier);
  }

  public static FilesGraphQLException nodeNotFound(String nodeId) {
    return build(
        ErrorCodes.NODE_NOT_FOUND, "Could not find node with id " + nodeId, "nodeId", nodeId);
  }

  public static FilesGraphQLException nodeWriteError(String nodeId) {
    return build(
        ErrorCodes.NODE_WRITE_ERROR,
        "There was a problem while executing requested operation on node: " + nodeId,
        "nodeId",
        nodeId);
  }

  public static FilesGraphQLException duplicateNode(String nodeId, String destinationFolderId) {
    return build(
        ErrorCodes.NODE_DUPLICATED,
        "Trying to create a duplicate for the node "
            + nodeId
            + " in destination folder "
            + destinationFolderId,
        "nodeId",
        nodeId,
        "destinationFolderId",
        destinationFolderId);
  }

  public static FilesGraphQLException fileVersionNotFound(
      String nodeId, Integer fileVersion, Object partialResults) {
    return buildPartial(
        ErrorCodes.FILE_VERSION_NOT_FOUND,
        "Could not find version: " + fileVersion + " for node with id " + nodeId,
        partialResults,
        "nodeId",
        nodeId,
        "fileVersion",
        fileVersion);
  }

  public static FilesGraphQLException fileVersionNotFound(String nodeId, Integer fileVersion) {
    return fileVersionNotFound(nodeId, fileVersion, null);
  }

  public static FilesGraphQLException shareNotFound(String nodeId, String userId) {
    return build(
        ErrorCodes.SHARE_NOT_FOUND,
        "Could not find share for node: " + nodeId + " and user " + userId,
        "nodeId",
        nodeId,
        "userId",
        userId);
  }

  public static FilesGraphQLException shareCreationError(String nodeId, String targetUserId) {
    return build(
        ErrorCodes.SHARE_CREATION_ERROR,
        "Could not create share for node: " + nodeId + " and user: " + targetUserId,
        "nodeId",
        nodeId,
        "userId",
        targetUserId);
  }

  public static FilesGraphQLException linkNotFound(String linkId) {
    return build(
        ErrorCodes.LINK_NOT_FOUND, "Could not find link with id " + linkId, "linkId", linkId);
  }

  public static FilesGraphQLException tooManyVersionsError(String nodeId) {
    return build(
        ErrorCodes.VERSIONS_LIMIT_REACHED,
        "There was a problem while executing requested operation on node: " + nodeId,
        "nodeId",
        nodeId);
  }

  public static FilesGraphQLException nodeCopyError(
      ErrorCodes code, String nodeId, Integer version) {
    return build(
        code,
        MessageFormat.format(
            "There was a problem while copying the node {0} with version {1}", nodeId, version),
        "nodeId",
        nodeId,
        "version",
        version);
  }

  public static FilesGraphQLException cloneCopyError(
      ErrorCodes code, String nodeId, Integer version) {
    return build(
        code, MessageFormat.format("Copy error with nodeId: {0} and version {1}", nodeId, version));
  }

  public static FilesGraphQLException accessCodeRequired(String publicLinkId) {
    return build(
        ErrorCodes.ACCESS_CODE_REQUIRED,
        "Access code is required for accessing the resource with public link id: " + publicLinkId,
        "publicLinkId",
        publicLinkId);
  }

  public static FilesGraphQLException wrongAccessCode(String publicLinkId) {
    return build(
        ErrorCodes.WRONG_ACCESS_CODE,
        "The access code for link with public id " + publicLinkId + " is not correct",
        "publicLinkId",
        publicLinkId);
  }

  public static FilesGraphQLException linkLimitExceeded(String nodeId) {
    return build(
        ErrorCodes.LINK_LIMIT_EXCEEDED,
        "The limit for links has been reached for this node: " + nodeId,
        "nodeId",
        nodeId);
  }

  private static FilesGraphQLException build(ErrorCodes code, String message, Object... kv) {
    return new FilesGraphQLException(code, message, extras(kv));
  }

  private static FilesGraphQLException buildPartial(
      ErrorCodes code, String message, Object partialResults, Object... kv) {
    return partialResults == null
        ? new FilesGraphQLException(code, message, extras(kv))
        : new FilesGraphQLException(code, message, partialResults, extras(kv));
  }

  private static Map<String, Object> extras(Object[] kv) {
    Map<String, Object> extras = new LinkedHashMap<>();
    for (int i = 0; i + 1 < kv.length; i += 2) {
      extras.put(String.valueOf(kv[i]), kv[i + 1]);
    }
    return extras;
  }
}
