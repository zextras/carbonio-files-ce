// SPDX-FileCopyrightText: 2022 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.rest.types;


import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import java.util.Objects;


public class UploadVersionResponse {

  public static final String SERIALIZED_NAME_VERSION_ID = "version";
  private String nodeId;

  @JsonInclude(Include.NON_NULL)
  private Integer version;

  public Integer getVersion() {
    return version;
  }

  public void setVersion(int version) {
    // TODO: this is a temporary fix since we need to make a breaking change if we want to return
    //  always the version
    if (version > 1) {
      this.version = version;
    }
  }

  public String getNodeId() {
    return nodeId;
  }

  public void setNodeId(String nodeId) {
    this.nodeId = nodeId;
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder();
    sb.append("class UploadNode {\n");
    sb.append("    nodeId: ")
      .append(toIndentedString(nodeId))
      .append("\n");
    sb.append("    versionId: ")
      .append(toIndentedString(version))
      .append("\n");
    sb.append("}");
    return sb.toString();
  }

  /**
   * Convert the given object to string with each line indented by 4 spaces (except the first
   * line).
   */
  private String toIndentedString(Object o) {
    if (o == null) {
      return "null";
    }
    return o.toString().replace("\n", "\n    ");
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    UploadVersionResponse that = (UploadVersionResponse) o;
    return version == that.version && Objects.equals(nodeId, that.nodeId);
  }

  @Override
  public int hashCode() {
    return Objects.hash(nodeId, version);
  }
}
