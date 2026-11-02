// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.dal.locks;

import java.util.Objects;

public record LockKey(Kind kind, String nodeId, String ownerId) {

  public enum Kind {
    NODE,
    CHILDREN
  }

  public static LockKey node(String nodeId) {
    return new LockKey(Kind.NODE, nodeId.trim(), null);
  }

  public static LockKey children(String folderId, String ownerId) {
    return new LockKey(Kind.CHILDREN, folderId.trim(), Objects.requireNonNull(ownerId, "ownerId"));
  }
}
