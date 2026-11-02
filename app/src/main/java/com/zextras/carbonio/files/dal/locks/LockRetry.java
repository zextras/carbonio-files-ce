// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.dal.locks;

import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.function.Supplier;

@ApplicationScoped
public class LockRetry {

  public <T> T inNewTransaction(Supplier<T> work) {
    for (int attempt = 0; ; attempt++) {
      try {
        return QuarkusTransaction.requiringNew().call(work::get);
      } catch (RuntimeException failure) {
        if (attempt >= LockConflicts.MAX_RETRIES || !LockConflicts.isRetryable(failure)) {
          throw failure;
        }
      }
    }
  }

  public void runInNewTransaction(Runnable work) {
    inNewTransaction(
        () -> {
          work.run();
          return null;
        });
  }
}
