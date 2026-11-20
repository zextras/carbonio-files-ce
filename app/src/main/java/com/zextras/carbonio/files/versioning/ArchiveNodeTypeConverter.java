// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.versioning;

import com.zextras.carbonio.files.graphql.model.NodeType;
import com.zextras.carbonio.quarkus.extensions.apiversioning.ApiConverter;
import com.zextras.carbonio.quarkus.extensions.apiversioning.ValueConverter;

@ApiConverter
public class ArchiveNodeTypeConverter implements ValueConverter<NodeType> {

  @Override
  public Class<NodeType> type() {
    return NodeType.class;
  }

  @Override
  public NodeType convert(NodeType value) {
    return value == NodeType.ARCHIVE ? NodeType.OTHER : value;
  }
}
