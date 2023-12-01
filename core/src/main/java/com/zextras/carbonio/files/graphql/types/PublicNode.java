// SPDX-FileCopyrightText: 2023 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.graphql.types;

import com.zextras.carbonio.files.Files.GraphQL;
import com.zextras.carbonio.files.dal.dao.ebean.Node;
import com.zextras.carbonio.files.dal.dao.ebean.NodeCategory;
import com.zextras.carbonio.files.dal.dao.ebean.NodeType;
import java.util.HashMap;
import java.util.Map;

public class PublicNode {

  private final String id;
  private final long createdAt;
  private final long updatedAt;
  private final String name;
  private final String extension;
  private final NodeType type;
  private final String mimeType;
  private final Long size;

  private PublicNode(
      String id,
      long createdAt,
      long updatedAt,
      String name,
      String extension,
      NodeType nodeType,
      String mimeType,
      Long size) {
    this.id = id;
    this.createdAt = createdAt;
    this.updatedAt = updatedAt;
    this.name = name;
    this.extension = extension;
    this.type = nodeType;
    this.mimeType = mimeType;
    this.size = size;
  }

  public static PublicNode createFromNode(Node node) {
    return new PublicNode(
        node.getId(),
        node.getCreatedAt(),
        node.getUpdatedAt(),
        node.getName(),
        node.getExtension().orElse(null),
        node.getNodeType(),
        node.getNodeCategory().equals(NodeCategory.FILE)
            ? node.getFileVersions().stream().findFirst().get().getMimeType()
            : null,
        node.getNodeCategory().equals(NodeCategory.FILE) ? node.getSize() : null);
  }

  public Map<String, Object> convertToMap() {
    Map<String, Object> mapBuilder = new HashMap<>();
    mapBuilder.put(GraphQL.PublicNode.ID, id);
    mapBuilder.put(GraphQL.PublicNode.CREATED_AT, createdAt);
    mapBuilder.put(GraphQL.PublicNode.UPDATED_AT, updatedAt);
    mapBuilder.put(GraphQL.PublicNode.NAME, name);
    mapBuilder.put(GraphQL.PublicNode.TYPE, type);

    if (!(NodeType.FOLDER.equals(type) || NodeType.ROOT.equals(type))) {
      mapBuilder.put(GraphQL.PublicNode.EXTENSION, extension);
      mapBuilder.put(GraphQL.PublicNode.MIME_TYPE, mimeType);
      mapBuilder.put(GraphQL.PublicNode.SIZE, size);
    }

    return mapBuilder;
  }
}
