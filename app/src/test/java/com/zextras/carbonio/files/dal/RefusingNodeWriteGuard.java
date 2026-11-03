// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.dal;

import com.zextras.carbonio.files.dal.locks.NodeBusyException;
import com.zextras.carbonio.files.dal.locks.NodeWriteGuard;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import java.util.Collection;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Alternative
@Priority(1)
@ApplicationScoped
public class RefusingNodeWriteGuard implements NodeWriteGuard {

  private static final Set<String> REFUSED = ConcurrentHashMap.newKeySet();
  private static final AtomicInteger REFUSALS = new AtomicInteger();
  private static volatile String callerMarker;

  public static void refuse(String nodeId) {
    REFUSED.add(nodeId.trim());
  }

  public static void refuse(String nodeId, String callerFrameMarker) {
    callerMarker = callerFrameMarker;
    refuse(nodeId);
  }

  public static void reset() {
    REFUSED.clear();
    REFUSALS.set(0);
    callerMarker = null;
  }

  public static int refusals() {
    return REFUSALS.get();
  }

  @Override
  public void requireWritable(Collection<String> nodeIds) {
    if (REFUSED.isEmpty() || !calledFromMarkedFrame()) {
      return;
    }
    for (String nodeId : nodeIds) {
      if (REFUSED.contains(nodeId.trim())) {
        REFUSALS.incrementAndGet();
        throw new NodeBusyException(nodeId.trim());
      }
    }
  }

  private static boolean calledFromMarkedFrame() {
    String marker = callerMarker;
    if (marker == null) {
      return true;
    }
    return StackWalker.getInstance()
        .walk(
            frames ->
                frames.anyMatch(
                    frame ->
                        (frame.getClassName() + "." + frame.getMethodName()).contains(marker)));
  }
}
