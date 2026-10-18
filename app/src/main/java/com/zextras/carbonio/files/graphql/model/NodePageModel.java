// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.graphql.model;

import java.util.List;
import org.eclipse.microprofile.graphql.Name;
import org.eclipse.microprofile.graphql.NonNull;
import org.eclipse.microprofile.graphql.Type;

@Type("NodePage")
public class NodePageModel {

  private final List<NodeModel> nodes;
  private final String pageToken;

  public NodePageModel(List<NodeModel> nodes, String pageToken) {
    this.nodes = nodes;
    this.pageToken = pageToken;
  }

  @NonNull
  public List<NodeModel> getNodes() {
    return nodes;
  }

  @Name("page_token")
  public String getPageToken() {
    return pageToken;
  }
}
