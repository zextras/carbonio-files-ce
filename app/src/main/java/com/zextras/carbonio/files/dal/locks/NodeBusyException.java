// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.dal.locks;

public class NodeBusyException extends RuntimeException {

  private final String nodeId;

  public NodeBusyException(String nodeId) {
    super("Node " + nodeId + " is busy");
    this.nodeId = nodeId;
  }

  public String getNodeId() {
    return nodeId;
  }
}
