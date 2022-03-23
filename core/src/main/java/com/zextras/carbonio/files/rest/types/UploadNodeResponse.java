// SPDX-FileCopyrightText: 2022 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.rest.types;


import java.util.Objects;
import java.util.UUID;

public class UploadNodeResponse {

  public static final String SERIALIZED_NAME_NODE_ID = "nodeId";
  private             UUID   nodeId;

  public UUID getNodeId() {
    return nodeId;
  }

  public void setNodeId(UUID nodeId) {
    this.nodeId = nodeId;
  }


  @Override
  public boolean equals(java.lang.Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    UploadNodeResponse uploadNode = (UploadNodeResponse) o;
    return Objects.equals(this.nodeId, uploadNode.nodeId);
  }

  @Override
  public int hashCode() {
    return Objects.hash(nodeId);
  }


  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder();
    sb.append("class UploadNode {\n");
    sb.append("    nodeIdId: ")
      .append(toIndentedString(nodeId))
      .append("\n");
    sb.append("}");
    return sb.toString();
  }

  /**
   * Converts the given object to string with each line indented by 4 spaces (except the first
   * line).
   */
  private String toIndentedString(java.lang.Object o) {
    if (o == null) {
      return "null";
    }
    return o.toString().replace("\n", "\n    ");
  }

}
