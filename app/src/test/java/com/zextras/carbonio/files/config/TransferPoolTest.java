// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * Plain JUnit (NOT {@code @QuarkusTest}) regression guard for {@link TransferPool}: it constructs
 * the bean directly (no CDI), drives its {@code @PostConstruct}/{@code @PreDestroy} lifecycle
 * methods directly (package-private, so no reflection is needed from this same-package test), and
 * proves the three properties that make it a safe dedicated transfer pool:
 *
 * <ol>
 *   <li>submitted work actually runs on the {@code carbonio-files-transfer-*} named threads (not
 *       the caller's thread or some other pool);
 *   <li>it is bounded — with a 1-thread/1-slot-queue configuration, a 3rd concurrent submission is
 *       synchronously rejected ({@link RejectedExecutionException}, the {@code AbortPolicy});
 *   <li>{@code @PreDestroy} actually shuts the underlying executor down.
 * </ol>
 *
 * <p>Every scenario is driven with {@link CountDownLatch}es, never {@code Thread.sleep}, so the
 * test is deterministic: a task submission is only made once we have positive confirmation (via a
 * latch released from inside a previous task) that the pool is in the state the assertion needs.
 */
class TransferPoolTest {

  private static TransferPool newPool(int maxThreads, int queueSize) {
    TransferPool pool = new TransferPool();
    pool.maxThreads = maxThreads;
    pool.queueSize = queueSize;
    pool.init();
    return pool;
  }

  @Test
  void submittedTasksRunOnNamedTransferThreads() throws InterruptedException {
    TransferPool pool = newPool(2, 2);
    ExecutorService executor = pool.get();
    try {
      AtomicReference<String> observedThreadName = new AtomicReference<>();
      CountDownLatch taskRan = new CountDownLatch(1);

      executor.execute(
          () -> {
            observedThreadName.set(Thread.currentThread().getName());
            taskRan.countDown();
          });

      assertThat(taskRan.await(5, TimeUnit.SECONDS)).as("task ran within timeout").isTrue();
      assertThat(observedThreadName.get()).startsWith("carbonio-files-transfer-");
    } finally {
      pool.shutdown();
    }
  }

  @Test
  void poolIsBoundedAndRejectsBeyondThreadsPlusQueue() throws InterruptedException {
    // 1 worker thread + a 1-slot queue: the 1st submission runs, the 2nd sits in the queue, and a
    // 3rd concurrent submission has nowhere to go -> AbortPolicy fires synchronously.
    TransferPool pool = newPool(1, 1);
    ExecutorService executor = pool.get();
    CountDownLatch firstTaskStarted = new CountDownLatch(1);
    CountDownLatch releaseFirstTask = new CountDownLatch(1);
    try {
      // Occupies the single worker thread until we release it.
      executor.execute(
          () -> {
            firstTaskStarted.countDown();
            awaitUninterruptibly(releaseFirstTask);
          });
      assertThat(firstTaskStarted.await(5, TimeUnit.SECONDS))
          .as("first task started within timeout")
          .isTrue();

      // Fills the single queue slot (execute() only enqueues; it does not block/run).
      executor.execute(() -> awaitUninterruptibly(releaseFirstTask));

      // Threads (1/1 busy) AND queue (1/1 full): this 3rd submission must be rejected immediately.
      assertThatThrownBy(() -> executor.execute(() -> {}))
          .isInstanceOf(RejectedExecutionException.class);
    } finally {
      releaseFirstTask.countDown();
      pool.shutdown();
    }
  }

  @Test
  void preDestroyShutsTheExecutorDown() {
    TransferPool pool = newPool(2, 2);
    ExecutorService executor = pool.get();
    assertThat(executor.isShutdown()).isFalse();

    pool.shutdown();

    assertThat(executor.isShutdown()).isTrue();
    assertThatThrownBy(() -> executor.execute(() -> {}))
        .isInstanceOf(RejectedExecutionException.class);
  }

  private static void awaitUninterruptibly(CountDownLatch latch) {
    boolean interrupted = false;
    try {
      while (true) {
        try {
          latch.await();
          return;
        } catch (InterruptedException e) {
          interrupted = true;
        }
      }
    } finally {
      if (interrupted) {
        Thread.currentThread().interrupt();
      }
    }
  }
}
