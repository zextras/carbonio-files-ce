// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.utilities;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.narayana.jta.TransactionRunnerOptions;
import java.util.concurrent.Callable;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

public final class InlineTransactions {

  private InlineTransactions() {}

  public static MockedStatic<QuarkusTransaction> open() {
    TransactionRunnerOptions options = mock(TransactionRunnerOptions.class);
    doAnswer(
            inv -> {
              inv.<Runnable>getArgument(0).run();
              return null;
            })
        .when(options)
        .run(any(Runnable.class));
    doAnswer(inv -> inv.<Callable<?>>getArgument(0).call()).when(options).call(any(Callable.class));
    MockedStatic<QuarkusTransaction> tx = Mockito.mockStatic(QuarkusTransaction.class);
    tx.when(QuarkusTransaction::requiringNew).thenReturn(options);
    return tx;
  }
}
