// SPDX-FileCopyrightText: 2022 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.graphql.types;

import java.util.Map;
import java.util.Objects;

public class NodeEvent {

  private final NodeEventType       action;
  private final Map<String, Object> node;

  public NodeEvent(
    NodeEventType action,
    Map<String, Object> node
  ) {
    this.action = action;
    this.node = node;
  }

  public String getParentId() {
    return (String) node.get("parent_id");
  }

  public String getOwner() {
    return (String) node.get("owner_id");
  }

  public enum NodeEventType {
    ADDED,
    UPDATED,
    DELETED
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    NodeEvent that = (NodeEvent) o;
    return action == that.action && Objects.equals(node, that.node);
  }

  @Override
  public int hashCode() {
    return Objects.hash(action, node);
  }
}
