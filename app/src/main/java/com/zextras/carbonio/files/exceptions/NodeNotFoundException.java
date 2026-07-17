// SPDX-FileCopyrightText: 2022 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.exceptions;

public class NodeNotFoundException extends Exception {

  public NodeNotFoundException() {
    super();
  }

  public NodeNotFoundException(
    String requesterId,
    String nodeId
  ) {
    super(String.format(
      "Node %s requested by %s does not exist or it does not have the permission to read it",
      nodeId,
      requesterId
    ));
  }

}
