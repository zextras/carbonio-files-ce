// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.dal.repositories.impl;

import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.concurrent.Callable;

/** Runs work in a new transaction; a seam that keeps unit tests free of the transaction manager. */
@ApplicationScoped
public class Transactions {

  public <T> T inNewTransaction(Callable<T> work) {
    return QuarkusTransaction.requiringNew().call(work);
  }
}
