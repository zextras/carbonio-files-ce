// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.dal;

import com.zextras.carbonio.files.dal.locks.LockSetChangedException;
import com.zextras.carbonio.files.dal.locks.RetryOnLockConflict;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.TransactionManager;
import jakarta.transaction.Transactional;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

@ApplicationScoped
public class RetryProbeBean {

  private final AtomicInteger calls = new AtomicInteger();
  private final List<Object> transactions = new CopyOnWriteArrayList<>();

  @Inject TransactionManager transactionManager;

  public void reset() {
    calls.set(0);
    transactions.clear();
  }

  public int calls() {
    return calls.get();
  }

  public List<Object> transactions() {
    return List.copyOf(transactions);
  }

  @RetryOnLockConflict
  @Transactional
  public int failTwiceThenSucceed() {
    int count = calls.incrementAndGet();
    if (count <= 2) {
      throw new LockSetChangedException("x");
    }
    return count;
  }

  @RetryOnLockConflict
  @Transactional
  public int alwaysStale() {
    calls.incrementAndGet();
    throw new LockSetChangedException("x");
  }

  @RetryOnLockConflict
  @Transactional
  public void businessFailure() {
    calls.incrementAndGet();
    throw new IllegalArgumentException("business");
  }

  @RetryOnLockConflict
  @Transactional
  public int transactionIdentity() throws Exception {
    int count = calls.incrementAndGet();
    Object identity = transactionManager.getTransaction();
    transactions.add(identity);
    if (count == 1) {
      throw new LockSetChangedException("x");
    }
    return count;
  }
}
