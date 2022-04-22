// SPDX-FileCopyrightText: 2022 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.exceptions;

import java.text.MessageFormat;

public class NodeNotFoundException extends Exception {

  public NodeNotFoundException() {
    super();
  }

  public NodeNotFoundException(
    String requesterId,
    String nodeId
  ) {
    super(MessageFormat.format(
      "Node {0} requested by {1} does not exist",
      nodeId,
      requesterId
    ));
  }

}
