// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.dal.locks;

import java.util.Collection;

/**
 * CE extension seam called by the lock primitives right after the node rows are locked, and by the
 * blob confirms that do not use them. The Advanced edition supplies an {@code @Alternative}
 * {@code @Priority(1)} implementation that refuses the nodes it holds read-only.
 */
public interface NodeWriteGuard {

  /**
   * @throws NodeBusyException when one of the nodes cannot be written now
   */
  void requireWritable(Collection<String> nodeIds);
}
