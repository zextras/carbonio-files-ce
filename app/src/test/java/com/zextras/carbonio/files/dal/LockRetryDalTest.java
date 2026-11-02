// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.dal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zextras.carbonio.files.FilesStackTestResource;
import com.zextras.carbonio.files.dal.locks.LockConflicts;
import com.zextras.carbonio.files.dal.locks.LockRetry;
import com.zextras.carbonio.files.dal.locks.LockSetChangedException;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.RollbackException;
import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@QuarkusTest
@QuarkusTestResource(FilesStackTestResource.class)
class LockRetryDalTest {

  @Inject RetryProbeBean probe;
  @Inject LockRetry lockRetry;

  private final AtomicInteger calls = new AtomicInteger();

  @BeforeEach
  void reset() {
    probe.reset();
    calls.set(0);
  }

  @Test
  void interceptorRetriesTwiceThenSucceeds() {
    assertThat(probe.failTwiceThenSucceed()).isEqualTo(3);
    assertThat(probe.calls()).isEqualTo(3);
  }

  @Test
  void interceptorGivesUpAfterTwoRetries() {
    assertThatThrownBy(probe::alwaysStale).isInstanceOf(LockSetChangedException.class);
    assertThat(probe.calls()).isEqualTo(3);
  }

  @Test
  void interceptorDoesNotRetryBusinessFailures() {
    assertThatThrownBy(probe::businessFailure).isInstanceOf(IllegalArgumentException.class);
    assertThat(probe.calls()).isEqualTo(1);
  }

  @Test
  void interceptorRunsEachAttemptInANewTransaction() throws Exception {
    probe.transactionIdentity();

    assertThat(probe.transactions()).hasSize(2);
    assertThat(probe.transactions().get(0)).isNotSameAs(probe.transactions().get(1));
  }

  @Test
  void helperRetriesTwiceThenSucceeds() {
    int result =
        lockRetry.inNewTransaction(
            () -> {
              int count = calls.incrementAndGet();
              if (count <= 2) {
                throw new LockSetChangedException("x");
              }
              return count;
            });

    assertThat(result).isEqualTo(3);
    assertThat(calls.get()).isEqualTo(3);
  }

  @Test
  void helperGivesUpAfterTwoRetries() {
    assertThatThrownBy(
            () ->
                lockRetry.runInNewTransaction(
                    () -> {
                      calls.incrementAndGet();
                      throw new LockSetChangedException("x");
                    }))
        .satisfies(e -> assertThat(LockConflicts.isRetryable(e)).isTrue());
    assertThat(calls.get()).isEqualTo(3);
  }

  @Test
  void helperDoesNotRetryBusinessFailures() {
    assertThatThrownBy(
            () ->
                lockRetry.runInNewTransaction(
                    () -> {
                      calls.incrementAndGet();
                      throw new IllegalArgumentException("business");
                    }))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(calls.get()).isEqualTo(1);
  }

  @Test
  void retryableClassification() {
    assertThat(LockConflicts.isRetryable(new SQLException("d", "40P01"))).isTrue();
    assertThat(LockConflicts.isRetryable(new SQLException("t", "55P03"))).isTrue();

    RollbackException rollback = new RollbackException("rolled back");
    rollback.initCause(new SQLException("t", "55P03"));
    assertThat(LockConflicts.isRetryable(rollback)).isTrue();

    assertThat(LockConflicts.isRetryable(new RuntimeException(new SQLException("d", "40P01"))))
        .isTrue();
    assertThat(LockConflicts.isRetryable(new LockSetChangedException("x"))).isTrue();

    SQLException chained = new SQLException("batch", "00000");
    chained.setNextException(new SQLException("d", "40P01"));
    assertThat(LockConflicts.isRetryable(chained)).isTrue();

    assertThat(LockConflicts.isRetryable(new SQLException("u", "23505"))).isFalse();
    assertThat(LockConflicts.isRetryable(new IllegalStateException())).isFalse();
  }
}
