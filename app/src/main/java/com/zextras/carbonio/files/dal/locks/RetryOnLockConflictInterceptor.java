// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.dal.locks;

import jakarta.annotation.Priority;
import jakarta.interceptor.AroundInvoke;
import jakarta.interceptor.Interceptor;
import jakarta.interceptor.InvocationContext;

// Outside the transactional interceptor (PLATFORM_BEFORE + 200): every attempt runs in a new
// transaction.
@RetryOnLockConflict
@Interceptor
@Priority(Interceptor.Priority.PLATFORM_BEFORE + 175)
public class RetryOnLockConflictInterceptor {

  @AroundInvoke
  Object retry(InvocationContext context) throws Exception {
    for (int attempt = 0; ; attempt++) {
      try {
        return context.proceed();
      } catch (Exception failure) {
        if (attempt >= LockConflicts.MAX_RETRIES || !LockConflicts.isRetryable(failure)) {
          throw failure;
        }
      }
    }
  }
}
