// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.rest;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

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
import java.util.concurrent.ThreadPoolExecutor;
import java.util.function.LongSupplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Proves the upload and download REST endpoints actually offload their heavy byte-transfer work to
 * the dedicated {@link TransferPool} bean (not the default Quarkus/Vert.x worker pool): it reads the
 * pool's own {@link ThreadPoolExecutor#getCompletedTaskCount()} immediately before and after each
 * call and asserts it strictly increased, which can only happen if the endpoint actually submitted
 * (and completed) a task on THIS SPECIFIC executor instance.
 *
 * <p>Same harness/style as {@link BlobResourceIT}: {@code @QuarkusTest} + {@link
 * FilesStackTestResource} (real Postgres testcontainer), nodes seeded in-JVM via the injected {@link
 * NodeRepository}, requests driven with RestAssured directly against the shared test HTTP port.
 *
 * <p>The completed-task counter is read right after the RestAssured call returns (i.e. after the
 * client has already received the full HTTP response); since the counter is only incremented by the
 * SAME worker thread that produced that response, {@link #awaitGreaterThan} polls with a short bound
 * (no external library needed) rather than doing a single flat read, absorbing the negligible,
 * sub-millisecond local scheduling gap between "the socket write was issued" and "the executor
 * recorded the task as completed" without hiding a real regression (a genuine miss never converges
 * and the assertion fails once the bound is exhausted).
 */
@QuarkusTest
@QuarkusTestResource(FilesStackTestResource.class)
class TransferPoolIsolationIT {

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
        "transfer-pool-isolation-" + folderId,
        "desc",
        NodeType.FOLDER,
        "LOCAL_ROOT",
        0L);
  }

  @Test
  void uploadRunsOnTheTransferPool() {
    ThreadPoolExecutor executor = (ThreadPoolExecutor) transferPool.get();
    long completedBefore = executor.getCompletedTaskCount();

    byte[] content = "isolation-upload-content".getBytes(StandardCharsets.UTF_8);
    upload(folderId, "isolation-upload.txt", content);

    awaitGreaterThan(executor::getCompletedTaskCount, completedBefore);
  }

  @Test
  void downloadRunsOnTheTransferPool() {
    byte[] content = "isolation-download-content".getBytes(StandardCharsets.UTF_8);
    String nodeId = upload(folderId, "isolation-download.txt", content);

    ThreadPoolExecutor executor = (ThreadPoolExecutor) transferPool.get();
    long completedBefore = executor.getCompletedTaskCount();

    given()
        .cookie("ZM_AUTH_TOKEN", FilesStackTestResource.AUTH_TOKEN)
        .when()
        .get("/download/" + nodeId)
        .then()
        .statusCode(200);

    awaitGreaterThan(executor::getCompletedTaskCount, completedBefore);
  }

  /**
   * Polls {@code actual} (max 5s, 10ms steps — no {@code Thread.sleep}-and-hope single check) until
   * it strictly exceeds {@code baseline}, then asserts that. A genuine regression (the endpoint never
   * touching the pool) never converges and fails loudly once the bound is exhausted; it is not a
   * fixed-delay sleep that could mask a slow-but-eventually-correct result as a false negative, nor
   * one that could flake under CI load — it only ever waits as long as it actually needs to.
   */
  private static void awaitGreaterThan(LongSupplier actual, long baseline) {
    long deadline = System.nanoTime() + java.time.Duration.ofSeconds(5).toNanos();
    long last;
    do {
      last = actual.getAsLong();
      if (last > baseline) {
        return;
      }
      if (System.nanoTime() >= deadline) {
        break;
      }
      try {
        Thread.sleep(10);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new RuntimeException(e);
      }
    } while (true);
    assertThat(last).isGreaterThan(baseline);
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
