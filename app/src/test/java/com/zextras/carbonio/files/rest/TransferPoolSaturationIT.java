// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.rest;

import static io.restassured.RestAssured.given;

import com.zextras.carbonio.files.FilesStackTestResource;
import com.zextras.carbonio.files.config.TransferPool;
import com.zextras.carbonio.files.dal.dao.ebean.NodeType;
import com.zextras.carbonio.files.dal.repositories.interfaces.NodeRepository;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * End-to-end proof for the {@link TransferPool} saturation status: without shrinking the pool or
 * touching any config (a {@code @TestProfile} config override was tried first, but this module fixes
 * {@code quarkus.datasource.db-kind} at build time, so any test profile that forces Quarkus to
 * re-augment the shared application fails with "Build time property cannot be changed at runtime" —
 * a pre-existing constraint of this module's datasource wiring, unrelated to the transfer pool; see
 * the class-level note below), this test saturates the REAL, default-sized pool directly through the
 * injected {@link TransferPool} bean:
 *
 * <ol>
 *   <li>submits exactly {@code maximumPoolSize} blocking tasks — since the pool is fixed-size (core
 *       == max), {@link ThreadPoolExecutor#execute} is guaranteed to spin up a brand-new thread for
 *       each submission while the worker count is below that size, never queueing them, so this
 *       deterministically occupies every worker thread (confirmed with a latch, not assumed);
 *   <li>submits exactly {@code queue.remainingCapacity()} more blocking tasks, which — with every
 *       worker now busy — fill the bounded queue instead of running;
 *   <li>issues a real {@code GET /download/{nodeId}} through RestAssured: its own attempt to offload
 *       the byte-pump to this same, now fully saturated pool has nowhere to go, so it hits the
 *       {@code AbortPolicy} ({@link java.util.concurrent.RejectedExecutionException}) and, via {@link
 *       com.zextras.carbonio.files.rest.resources.BlobExceptionMapper}'s new mapping branch, the
 *       client observes HTTP 503.
 * </ol>
 *
 * <p>{@link com.zextras.carbonio.files.rest.resources.BlobExceptionMapperTest} additionally isolates
 * just the exception -&gt; status mapping as a fast, dependency-free unit test.
 */
@QuarkusTest
@QuarkusTestResource(FilesStackTestResource.class)
class TransferPoolSaturationIT {

  @Inject NodeRepository nodeRepository;
  @Inject TransferPool transferPool;

  private String folderId;

  @BeforeEach
  void seed() {
    folderId = UUID.randomUUID().toString();
    nodeRepository.createNewNode(
        folderId,
        FilesStackTestResource.TEST_USER_ID,
        FilesStackTestResource.TEST_USER_ID,
        "LOCAL_ROOT",
        "transfer-pool-saturation-" + folderId,
        "desc",
        NodeType.FOLDER,
        "LOCAL_ROOT",
        0L);
  }

  @Test
  void downloadReturns503WhenTheTransferPoolIsSaturated() throws InterruptedException {
    byte[] content = "saturation-content".getBytes(StandardCharsets.UTF_8);
    String nodeId = upload(folderId, "saturation.txt", content);

    ThreadPoolExecutor executor = (ThreadPoolExecutor) transferPool.get();
    int maxThreads = executor.getMaximumPoolSize();
    int queueCapacity = executor.getQueue().remainingCapacity(); // queue is empty at this point

    CountDownLatch allWorkerThreadsBusy = new CountDownLatch(maxThreads);
    CountDownLatch release = new CountDownLatch(1);

    try {
      // core == max (fixed-size pool): while workerCount < maxThreads, execute() ALWAYS starts a
      // brand-new thread rather than queueing, so these `maxThreads` submissions deterministically
      // occupy every worker thread.
      for (int i = 0; i < maxThreads; i++) {
        executor.execute(
            () -> {
              allWorkerThreadsBusy.countDown();
              awaitUninterruptibly(release);
            });
      }
      if (!allWorkerThreadsBusy.await(10, TimeUnit.SECONDS)) {
        throw new AssertionError("not all transfer-pool worker threads started within timeout");
      }

      // Every worker is now busy: these submissions have nowhere to run, so they fill the bounded
      // queue instead (execute() only enqueues; it never blocks/runs the task itself).
      for (int i = 0; i < queueCapacity; i++) {
        executor.execute(() -> awaitUninterruptibly(release));
      }

      // Threads AND queue are both saturated: the download's own attempt to offload the byte-pump
      // to this same pool has nowhere to go -> RejectedExecutionException -> (via
      // BlobExceptionMapper's new branch) HTTP 503.
      given()
          .cookie("ZM_AUTH_TOKEN", FilesStackTestResource.AUTH_TOKEN)
          .when()
          .get("/download/" + nodeId)
          .then()
          .statusCode(503);
    } finally {
      release.countDown();
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

  private String upload(String folderId, String filename, byte[] content) {
    return given()
        .cookie("ZM_AUTH_TOKEN", FilesStackTestResource.AUTH_TOKEN)
        .header("ParentId", folderId)
        .header("Filename", base64(filename))
        .contentType("application/octet-stream")
        .body(content)
        .when()
        .post("/upload")
        .then()
        .statusCode(200)
        .extract()
        .path("nodeId");
  }

  private static String base64(String value) {
    return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
  }
}
