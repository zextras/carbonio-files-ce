// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.dal.locks;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;
import org.junit.jupiter.api.Test;

class LockFailuresTest {

  @Test
  void aNodeBusyExceptionAnywhereInTheCauseChainIsBusy() {
    Throwable failure =
        new RuntimeException("x", new IllegalStateException(new NodeBusyException("n1")));

    assertThat(LockFailures.isBusy(failure)).isTrue();
    assertThat(LockFailures.busyNodeId(failure)).contains("n1");
  }

  @Test
  void lockTimeoutAndDeadlockSqlStatesAreBusyWithoutANodeId() {
    for (String state : new String[] {"55P03", "40P01"}) {
      Throwable failure = new RuntimeException(new SQLException("lock", state));

      assertThat(LockFailures.isBusy(failure)).isTrue();
      assertThat(LockFailures.busyNodeId(failure)).isEmpty();
    }
  }

  @Test
  void theSqlStateIsFoundAlsoOnTheNextException() {
    SQLException first = new SQLException("batch", "23505");
    first.setNextException(new SQLException("deadlock", "40P01"));

    assertThat(LockFailures.isBusy(new RuntimeException(first))).isTrue();
  }

  @Test
  void otherFailuresAreNotBusy() {
    assertThat(LockFailures.isBusy(new RuntimeException(new SQLException("dup", "23505"))))
        .isFalse();
    assertThat(LockFailures.isBusy(new IllegalStateException("x"))).isFalse();
  }

  @Test
  void aCyclicCauseChainTerminates() {
    RuntimeException a = new RuntimeException("a");
    RuntimeException b = new RuntimeException("b", a);
    a.initCause(b);

    assertThat(LockFailures.isBusy(a)).isFalse();
  }
}
