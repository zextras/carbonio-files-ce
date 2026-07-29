// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.rest.resources;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.zextras.carbonio.files.rest.types.BlobResponse;
import io.smallrye.mutiny.Uni;
import io.vertx.core.http.HttpServerResponse;
import java.io.InputStream;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * Plain JUnit (NOT {@code @QuarkusTest}) unit test for {@link TransferStreaming#streamBlob}'s
 * pool-saturation branch, replacing the end-to-end proof formerly in {@code
 * com.zextras.carbonio.files.rest.TransferPoolSaturationIT}. That IT needed to saturate the SAME
 * live {@code TransferPool} bean instance the launched app's {@code BlobResource} uses — impossible
 * out-of-process, since an out-of-process {@code @QuarkusIntegrationTest} cannot reach into a
 * separate JVM's CDI container. Here, the identical bounded-executor shape {@link
 * com.zextras.carbonio.files.config.TransferPool} builds (fixed-size {@link ThreadPoolExecutor},
 * {@link ThreadPoolExecutor.AbortPolicy}) is constructed directly and saturated in-process, then
 * handed to the real, unmodified {@link TransferStreaming#streamBlob} — proving the production
 * code's own {@code pool.execute(...)} call really does surface a full pool as a failed {@link Uni}
 * carrying {@link RejectedExecutionException}, exactly like the real endpoint would observe.
 *
 * <p>{@link com.zextras.carbonio.files.rest.resources.BlobExceptionMapperTest} separately proves
 * that mapper turns a {@link RejectedExecutionException} into HTTP 503; together the two unit tests
 * reconstruct the deleted IT's full "saturated pool -&gt; 503" intent. {@code
 * com.zextras.carbonio.files.config.TransferPoolTest} separately proves the pool itself (named
 * threads, bounded rejection, shutdown) — this test is only concerned with {@code
 * TransferStreaming}'s own reaction to a saturated pool.
 *
 * <p><b>Coverage change (accepted):</b> the former {@code TransferPoolIsolationIT} additionally
 * proved that a REAL HTTP upload/download round-trip actually completes its work ON the transfer
 * pool's named threads. That specific end-to-end wiring proof has no black-box (out-of-process)
 * equivalent and is not reconstructed here; it is implied by (a) every successful upload/download
 * {@code *IT} already passing (they would time out or 500 if {@code transferPool.get()} were never
 * reached) and (b) {@code TransferPoolTest} proving the pool itself runs submitted work on {@code
 * carbonio-files-transfer-*} threads.
 */
class TransferStreamingRejectionTest {

  /**
   * A 1-thread executor with a ZERO-capacity queue: {@link SynchronousQueue} (unlike {@link
   * java.util.concurrent.ArrayBlockingQueue}, which rejects a capacity of 0) is a pure handoff
   * queue, so once the single worker is busy, the very next submission has nowhere to go and is
   * rejected immediately — no second "filler" task needed to reach saturation.
   */
  private static ThreadPoolExecutor onePlaceExecutor() {
    return new ThreadPoolExecutor(
        1, 1, 0L, TimeUnit.SECONDS, new SynchronousQueue<>(), new ThreadPoolExecutor.AbortPolicy());
  }

  @Test
  void streamBlobFailsWithRejectedExecutionExceptionWhenThePoolIsSaturated() throws Exception {
    ThreadPoolExecutor pool = onePlaceExecutor();
    CountDownLatch workerBusy = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    try {
      // Occupy the single worker thread (and the queue has zero capacity), so the very next
      // execute() call has nowhere to go and is rejected synchronously (AbortPolicy).
      pool.execute(
          () -> {
            workerBusy.countDown();
            awaitUninterruptibly(release);
          });
      assertThat(workerBusy.await(5, TimeUnit.SECONDS)).as("worker started").isTrue();

      BlobResponse blob =
          new BlobResponse(InputStream.nullInputStream(), "file.txt", 10L, "text/plain");
      HttpServerResponse resp = mock(HttpServerResponse.class);

      Uni<Void> uni = TransferStreaming.streamBlob(blob, resp, pool);

      assertThatThrownBy(() -> uni.subscribeAsCompletionStage().get(5, TimeUnit.SECONDS))
          .isInstanceOf(ExecutionException.class)
          .cause()
          .isInstanceOf(RejectedExecutionException.class);
    } finally {
      release.countDown();
      pool.shutdown();
    }
  }

  @Test
  void streamBlobSucceedsWhenThePoolHasRoom() throws Exception {
    ThreadPoolExecutor pool = onePlaceExecutor();
    try {
      byte[] content = "hello".getBytes();
      BlobResponse blob =
          new BlobResponse(
              new java.io.ByteArrayInputStream(content), "file.txt", (long) content.length,
              "text/plain");
      HttpServerResponse resp = mock(HttpServerResponse.class);
      io.vertx.core.Future<Void> succeeded = io.vertx.core.Future.succeededFuture();
      org.mockito.Mockito.when(resp.write(org.mockito.ArgumentMatchers.any(io.vertx.core.buffer.Buffer.class)))
          .thenReturn(succeeded);
      org.mockito.Mockito.when(resp.end()).thenReturn(succeeded);

      Uni<Void> uni = TransferStreaming.streamBlob(blob, resp, pool);

      try {
        uni.subscribeAsCompletionStage().get(5, TimeUnit.SECONDS);
      } catch (ExecutionException | CompletionException e) {
        throw new AssertionError("expected streamBlob to succeed against a non-saturated pool", e);
      }
    } finally {
      pool.shutdown();
    }
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
