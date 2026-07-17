// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.graphql;

import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * Drop-in, behaviour-preserving replacement for {@link CompletableFuture#supplyAsync(Supplier)} that
 * runs the supplier SYNCHRONOUSLY on the calling thread instead of hopping to {@code
 * ForkJoinPool.commonPool()}.
 *
 * <p>P3e context: in the Quarkus stack the {@code EntityManager} is request-scoped and bound (via the
 * Vert.x duplicated context) to the request-scoped blocking worker thread on which {@code
 * GraphQL.execute()} runs. The legacy DataFetchers were ported 1:1 from the Guice/Ebean stack, where
 * {@code supplyAsync} on the common pool was harmless because Ebean was thread-agnostic. Under
 * Hibernate ORM that same hop lands on a common-pool thread with no active CDI request context and
 * throws {@code ContextNotActiveException}. Executing the supplier inline keeps every data-access hop
 * on the request-scoped thread.
 *
 * <p>The returned future still captures a thrown exception (completing exceptionally) exactly like
 * {@link CompletableFuture#supplyAsync(Supplier)}, so any downstream {@code .exceptionally(...)} /
 * {@code .handle(...)} chaining in the fetchers keeps working unchanged.
 */
public final class SyncCompletableFuture {

  private SyncCompletableFuture() {}

  public static <T> CompletableFuture<T> supplyAsync(Supplier<T> supplier) {
    CompletableFuture<T> future = new CompletableFuture<>();
    try {
      future.complete(supplier.get());
    } catch (Throwable throwable) {
      future.completeExceptionally(throwable);
    }
    return future;
  }
}
