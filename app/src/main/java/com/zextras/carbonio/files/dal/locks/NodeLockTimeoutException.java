// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.dal.locks;

import java.time.Duration;
import java.util.Collection;

public class NodeLockTimeoutException extends RuntimeException {

  public NodeLockTimeoutException(Collection<String> nodeIds, Duration budget) {
    super("Node locks not acquired within " + budget.toMillis() + " ms for " + nodeIds);
  }

  public NodeLockTimeoutException(Collection<String> nodeIds, Duration budget, Throwable cause) {
    super("Node locks not acquired within " + budget.toMillis() + " ms for " + nodeIds, cause);
  }
}
