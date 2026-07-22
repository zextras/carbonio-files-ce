// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.config;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Dedicated, bounded thread pool that runs the heavy REST byte-transfer work (single/ZIP blob
 * downloads and blob uploads) OFF the Vert.x event loop and OFF the shared Quarkus worker pool, so a
 * burst of large transfers cannot starve the rest of the service.
 *
 * <p>The pool is sized from Consul KV (overridable at {@code carbonio-files/transfer/max-threads}
 * and {@code carbonio-files/transfer/queue-size}, surfaced by the bootstrap extension as the
 * {@code application-config.transfer.*} MicroProfile properties). It is a fixed-size {@link
 * ThreadPoolExecutor} (core == max) fronted by a bounded {@link ArrayBlockingQueue}; when both the
 * threads and the queue are saturated the {@link ThreadPoolExecutor.AbortPolicy} rejects new work
 * (surfacing as a {@link java.util.concurrent.RejectedExecutionException} that the transfer helpers
 * translate into a failed transfer rather than a leaked storages stream).
 */
@ApplicationScoped
public class TransferPool {

  private static final Logger logger = LoggerFactory.getLogger(TransferPool.class);

  @ConfigProperty(name = "application-config.transfer.max-threads", defaultValue = "64")
  int maxThreads;

  @ConfigProperty(name = "application-config.transfer.queue-size", defaultValue = "256")
  int queueSize;

  private ThreadPoolExecutor executor;

  @PostConstruct
  void init() {
    AtomicLong counter = new AtomicLong();
    ThreadFactory threadFactory =
        runnable -> {
          Thread thread = new Thread(runnable, "carbonio-files-transfer-" + counter.incrementAndGet());
          thread.setDaemon(true);
          return thread;
        };

    this.executor =
        new ThreadPoolExecutor(
            maxThreads,
            maxThreads,
            60L,
            TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(queueSize),
            threadFactory,
            new ThreadPoolExecutor.AbortPolicy());

    logger.info(
        "Initialized carbonio-files transfer pool: max-threads={}, queue-size={}",
        maxThreads,
        queueSize);
  }

  @PreDestroy
  void shutdown() {
    if (executor != null) {
      executor.shutdown();
    }
  }

  /** Returns the shared, bounded transfer executor. */
  public ExecutorService get() {
    return executor;
  }
}
