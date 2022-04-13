// SPDX-FileCopyrightText: 2022 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.rest.types;

import java.util.Objects;
import java.util.UUID;

public class UploadToRequest {

  private UUID         nodeId;
  private TargetModule targetModule;

  public UUID getNodeId() {
    return nodeId;
  }

  public void setNodeId(UUID nodeId) {
    this.nodeId = nodeId;
  }

  public TargetModule getTargetModule() {
    return targetModule;
  }

  public void setTargetModule(TargetModule targetModule) {
    this.targetModule = targetModule;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    UploadToRequest that = (UploadToRequest) o;
    return Objects.equals(nodeId, that.nodeId) && targetModule == that.targetModule;
  }

  @Override
  public int hashCode() {
    return Objects.hash(nodeId, targetModule);
  }

  public enum TargetModule {
    MAILS,
    CALENDARS,
    CONTACTS,
    CHATS
  }
}
