// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.graphql.model.support;

import com.zextras.carbonio.files.dal.dao.ebean.Node;
import com.zextras.carbonio.files.dal.dao.ebean.NodeCategory;
import com.zextras.carbonio.files.graphql.model.NodeType;
import com.zextras.carbonio.files.graphql.model.PublicFileModel;
import com.zextras.carbonio.files.graphql.model.PublicFolderModel;
import com.zextras.carbonio.files.graphql.model.PublicNodeModel;

public final class PublicNodeModelFactory {

  private PublicNodeModelFactory() {}

  public static PublicNodeModel from(Node node) {
    NodeCategory category = node.getNodeCategory();
    NodeType gqlType = NodeType.valueOf(node.getNodeType().name());

    if (category == NodeCategory.ROOT || category == NodeCategory.FOLDER) {
      return new PublicFolderModel(
          node.getId(), node.getCreatedAt(), node.getUpdatedAt(), node.getName(), gqlType);
    }

    return new PublicFileModel(
        node.getId(),
        node.getCreatedAt(),
        node.getUpdatedAt(),
        node.getName(),
        node.getExtension().orElse(null),
        gqlType,
        node.getFileVersions().stream().findFirst().get().getMimeType(),
        (double) node.getSize());
  }
}
