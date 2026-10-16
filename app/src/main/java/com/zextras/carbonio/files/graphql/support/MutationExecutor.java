// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.graphql.support;

import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Threads outside Vert.x and the HTTP request, on which GraphQL mutations run to completion. */
@ApplicationScoped
public class MutationExecutor {

  private final AtomicInteger threadCount = new AtomicInteger();
  private final ExecutorService executor =
      Executors.newCachedThreadPool(
          task -> {
            Thread thread = new Thread(task, "files-mutation-" + threadCount.incrementAndGet());
            thread.setDaemon(true);
            return thread;
          });

  public <T> Future<T> submit(Callable<T> task) {
    return executor.submit(task);
  }

  @PreDestroy
  void shutdown() {
    executor.shutdown();
    try {
      executor.awaitTermination(30, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
