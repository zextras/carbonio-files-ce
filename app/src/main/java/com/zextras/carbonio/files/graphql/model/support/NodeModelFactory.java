// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.graphql.model.support;

import com.zextras.carbonio.files.dal.dao.ebean.FileVersion;
import com.zextras.carbonio.files.dal.dao.ebean.Node;
import com.zextras.carbonio.files.dal.dao.ebean.NodeCategory;
import com.zextras.carbonio.files.dal.dao.ebean.NodeCustomAttributes;
import com.zextras.carbonio.files.dal.dao.ebean.NodeType;
import com.zextras.carbonio.files.graphql.model.FileModel;
import com.zextras.carbonio.files.graphql.model.FolderModel;
import com.zextras.carbonio.files.graphql.model.NodeModel;

public final class NodeModelFactory {

  private NodeModelFactory() {}

  public static NodeModel from(Node node, Integer version, String requesterId) {
    NodeCategory category = node.getNodeCategory();

    boolean flagged =
        node.getCustomAttributes().stream()
            .filter(a -> requesterId.equals(a.getUserId()))
            .findFirst()
            .map(NodeCustomAttributes::getFlag)
            .orElse(false);

    String rootId =
        node.getNodeType().equals(NodeType.ROOT) ? node.getId() : node.getAncestorsList().get(0);

    String description = node.getDescription().orElse("");

    com.zextras.carbonio.files.graphql.model.NodeType gqlType =
        com.zextras.carbonio.files.graphql.model.NodeType.valueOf(node.getNodeType().name());

    if (category == NodeCategory.ROOT || category == NodeCategory.FOLDER) {
      return new FolderModel(
          node.getId(),
          node.getParentId().orElse(null),
          node.getOwnerId(),
          node.getCreatorId(),
          node.getLastEditorId().orElse(null),
          node.getCreatedAt(),
          node.getUpdatedAt(),
          node.getName(),
          description,
          gqlType,
          flagged,
          rootId);
    }

    int resolvedVersion = version != null ? version : node.getCurrentVersion();

    FileVersion fv =
        node.getFileVersions().stream()
            .filter(fileVersion -> resolvedVersion == fileVersion.getVersion())
            .findFirst()
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "FileVersion " + resolvedVersion + " not found for node " + node.getId()));

    return new FileModel(
        node.getId(),
        node.getParentId().orElse(null),
        node.getOwnerId(),
        node.getCreatorId(),
        fv.getLastEditorId(),
        node.getCreatedAt(),
        fv.getUpdatedAt(),
        node.getName(),
        node.getExtension().orElse(null),
        description,
        gqlType,
        fv.getMimeType(),
        fv.getSize(),
        fv.getDigest(),
        fv.getVersion(),
        fv.isKeptForever(),
        fv.getClonedFromVersion().orElse(null),
        flagged,
        rootId);
  }
}
