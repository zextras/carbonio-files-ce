// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.dal.locks;

import jakarta.enterprise.context.ApplicationScoped;
import java.util.Collection;

@ApplicationScoped
public class NoOpNodeWriteGuard implements NodeWriteGuard {

  @Override
  public void requireWritable(Collection<String> nodeIds) {}
}
