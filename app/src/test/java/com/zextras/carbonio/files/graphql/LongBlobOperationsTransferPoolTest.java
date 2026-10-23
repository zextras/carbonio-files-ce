// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.graphql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;

import com.zextras.carbonio.files.FilesStackTestResource;
import com.zextras.carbonio.files.config.TransferPool;
import com.zextras.carbonio.files.dal.repositories.interfaces.NodeRepository;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.mockito.InjectSpy;
import io.restassured.RestAssured;
import io.restassured.response.Response;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.Destroyed;
import jakarta.enterprise.context.Initialized;
import jakarta.enterprise.context.RequestScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Base64;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * copyNodes, cloneVersion and transferOwnership run on a transfer-pool thread for their whole
 * execution, the request context is initialized and destroyed once per request although the
 * resolvers deactivate it on a thread without a Vert.x context, and a full pool answers 503. The
 * heartbeat after the head is covered by {@code HeartbeatResponseWriterTest}: it needs the real
 * interval to elapse, which no test here waits for.
 */
@QuarkusTest
@QuarkusTestResource(FilesStackTestResource.class)
class LongBlobOperationsTransferPoolTest {

  static final AtomicInteger INITIALIZED = new AtomicInteger();
  static final AtomicInteger DESTROYED = new AtomicInteger();
  static final AtomicInteger BEAN_CREATED = new AtomicInteger();
  static final AtomicInteger BEAN_PRE_DESTROY = new AtomicInteger();
  static final ConcurrentLinkedQueue<String> INITIALIZED_ON = new ConcurrentLinkedQueue<>();
  static final ConcurrentLinkedQueue<String> DESTROYED_ON = new ConcurrentLinkedQueue<>();

  private static final String TOKEN = FilesStackTestResource.AUTH_TOKEN;
  private static final String COOKIE = "ZM_AUTH_TOKEN=" + TOKEN;
  private static final String TRANSFER_THREAD = "carbonio-files-transfer-";

  @RequestScoped
  public static class ProbeBean {
    private boolean created;

    void touch() {
      if (!created) {
        created = true;
        BEAN_CREATED.incrementAndGet();
      }
    }

    @PreDestroy
    void preDestroy() {
      BEAN_PRE_DESTROY.incrementAndGet();
    }
  }

  @ApplicationScoped
  public static class Probe {
    @Inject ProbeBean bean;

    void initialized(@Observes @Initialized(RequestScoped.class) Object event) {
      INITIALIZED.incrementAndGet();
      INITIALIZED_ON.add(Thread.currentThread().getName());
      bean.touch();
    }

    void destroyed(@Observes @Destroyed(RequestScoped.class) Object event) {
      DESTROYED.incrementAndGet();
      DESTROYED_ON.add(Thread.currentThread().getName());
    }
  }

  @InjectSpy NodeRepository nodeRepository;
  @Inject TransferPool transferPool;

  private final ConcurrentLinkedQueue<String> resolverThreads = new ConcurrentLinkedQueue<>();

  @AfterEach
  void cleanUp() throws SQLException {
    Mockito.reset(nodeRepository);
    try (Connection connection =
            DriverManager.getConnection(FilesStackTestResource.POSTGRES_JDBC_URL, "test", "test");
        Statement statement = connection.createStatement()) {
      statement.execute("DELETE FROM node WHERE owner_id IS NOT NULL");
      statement.execute("TRUNCATE tombstone, version_reservation, overwrite_lease CASCADE");
    }
    FilesStackTestResource.getStoragesService().reset();
    FilesStackTestResource.getStoragesService().clearAll();
  }

  private void recordResolverThreads() {
    Mockito.doAnswer(
            invocation -> {
              resolverThreads.add(Thread.currentThread().getName());
              return invocation.callRealMethod();
            })
        .when(nodeRepository)
        .getNode(anyString());
  }

  private static Response post(String body) {
    return RestAssured.given()
        .contentType("application/json")
        .header("Cookie", COOKIE)
        .body(body)
        .post("/graphql");
  }

  private static String query(String graphql) {
    return "{\"query\":\"" + graphql.replace("\"", "\\\"") + "\"}";
  }

  private static String createFolder(String name) {
    return post(query(
            "mutation { createFolder(destination_id: \"LOCAL_ROOT\", name: \""
                + name
                + "\") { id } }"))
        .jsonPath()
        .getString("data.createFolder.id");
  }

  private static String createFile(String name, String content) {
    return RestAssured.given()
        .header(
            "Filename", Base64.getEncoder().encodeToString(name.getBytes(StandardCharsets.UTF_8)))
        .header("Cookie", COOKIE)
        .body(content.getBytes(StandardCharsets.UTF_8))
        .post("/upload")
        .jsonPath()
        .getString("nodeId");
  }

  private static void awaitRequestContextsDestroyed() {
    long deadline = System.nanoTime() + 5_000_000_000L;
    while (System.nanoTime() < deadline && DESTROYED.get() < INITIALIZED.get()) {
      Thread.onSpinWait();
    }
  }

  private record Lifecycle(int initialized, int destroyed, int beans, int preDestroyed) {}

  private static Lifecycle lifecycleOf(Runnable request) {
    int i = INITIALIZED.get();
    int d = DESTROYED.get();
    int b = BEAN_CREATED.get();
    int p = BEAN_PRE_DESTROY.get();
    request.run();
    awaitRequestContextsDestroyed();
    return new Lifecycle(
        INITIALIZED.get() - i,
        DESTROYED.get() - d,
        BEAN_CREATED.get() - b,
        BEAN_PRE_DESTROY.get() - p);
  }

  @Test
  void aMultiRootCopyNodesRunsEntirelyOnATransferThreadWithOneRequestContext() {
    String source = createFolder("copy-source");
    String destination = createFolder("copy-destination");
    recordResolverThreads();
    INITIALIZED_ON.clear();
    DESTROYED_ON.clear();
    Response[] response = new Response[1];
    String copy =
        "copyNodes(node_ids: [\\\""
            + source
            + "\\\"], destination_id: \\\""
            + destination
            + "\\\")";
    String fields =
        " { id name type owner { id } creator { id } parent { id } permissions { can_read } }";

    Lifecycle lifecycle =
        lifecycleOf(
            () ->
                response[0] =
                    post(
                        "{\"query\":\"mutation { a: "
                            + copy
                            + fields
                            + " b: "
                            + copy
                            + fields
                            + " }\"}"));

    assertThat(response[0].getStatusCode()).isEqualTo(200);
    assertThat(response[0].jsonPath().getList("errors")).isNull();
    assertThat(response[0].jsonPath().getString("data.a[0].parent.id")).isEqualTo(destination);
    assertThat(response[0].jsonPath().getString("data.a[0].owner.id"))
        .isEqualTo(FilesStackTestResource.TEST_USER_ID);
    assertThat(response[0].jsonPath().getBoolean("data.b[0].permissions.can_read")).isTrue();
    assertThat(lifecycle).isEqualTo(new Lifecycle(1, 1, 1, 1));
    assertThat(INITIALIZED_ON).hasSize(1).allMatch(name -> name.startsWith(TRANSFER_THREAD));
    assertThat(DESTROYED_ON).hasSize(1).allMatch(name -> name.startsWith(TRANSFER_THREAD));
    assertThat(resolverThreads).isNotEmpty().allMatch(name -> name.startsWith(TRANSFER_THREAD));
  }

  @Test
  void cloneVersionRunsEntirelyOnATransferThreadWithOneRequestContext() {
    String file = createFile("clone.txt", "v1");
    RestAssured.given()
        .header("NodeId", file)
        .header(
            "Filename",
            Base64.getEncoder().encodeToString("clone.txt".getBytes(StandardCharsets.UTF_8)))
        .header("OverwriteVersion", "false")
        .header("Cookie", COOKIE)
        .body("v2".getBytes(StandardCharsets.UTF_8))
        .post("/upload-version");
    recordResolverThreads();
    INITIALIZED_ON.clear();
    Response[] response = new Response[1];

    Lifecycle lifecycle =
        lifecycleOf(
            () ->
                response[0] =
                    post(
                        query(
                            "mutation { cloneVersion(node_id: \""
                                + file
                                + "\", version: 1) { id version cloned_from_version owner { id }"
                                + " permissions { can_read } } }")));

    assertThat(response[0].getStatusCode()).isEqualTo(200);
    assertThat(response[0].jsonPath().getList("errors")).isNull();
    assertThat(response[0].jsonPath().getInt("data.cloneVersion.version")).isEqualTo(3);
    assertThat(response[0].jsonPath().getString("data.cloneVersion.owner.id"))
        .isEqualTo(FilesStackTestResource.TEST_USER_ID);
    assertThat(lifecycle).isEqualTo(new Lifecycle(1, 1, 1, 1));
    assertThat(INITIALIZED_ON).hasSize(1).allMatch(name -> name.startsWith(TRANSFER_THREAD));
    assertThat(resolverThreads).isNotEmpty().allMatch(name -> name.startsWith(TRANSFER_THREAD));
  }

  @Test
  void otherOperationsStayOnTheWorker() {
    createFolder("worker-folder");
    recordResolverThreads();
    INITIALIZED_ON.clear();

    Response response = post(query("{ getNode(node_id: \"LOCAL_ROOT\") { id } }"));

    assertThat(response.getStatusCode()).isEqualTo(200);
    assertThat(INITIALIZED_ON).hasSize(1).noneMatch(name -> name.startsWith(TRANSFER_THREAD));
    assertThat(resolverThreads).isNotEmpty().noneMatch(name -> name.startsWith(TRANSFER_THREAD));
  }

  @Test
  void aFailingLongOperationStillDestroysTheRequestContextOnce() {
    Response[] response = new Response[1];

    Lifecycle lifecycle =
        lifecycleOf(
            () ->
                response[0] =
                    post(
                        query(
                            "mutation { copyNodes(node_ids: [\"missing\"], destination_id:"
                                + " \"missing\") { id } }")));

    assertThat(response[0].getStatusCode()).isEqualTo(200);
    assertThat(response[0].jsonPath().getList("errors")).isNotEmpty();
    assertThat(lifecycle).isEqualTo(new Lifecycle(1, 1, 1, 1));
  }

  @Test
  void aFullPoolAnswers503ForALongOperationAndTheWorkerStaysFree() throws Exception {
    ThreadPoolExecutor pool = (ThreadPoolExecutor) transferPool.get();
    CountDownLatch release = new CountDownLatch(1);
    int capacity = pool.getMaximumPoolSize() + pool.getQueue().remainingCapacity();
    for (int i = 0; i < capacity; i++) {
      pool.execute(
          () -> {
            try {
              release.await();
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
            }
          });
    }
    try {
      Response rejected =
          post(query("mutation { copyNodes(node_ids: [\"a\"], destination_id: \"b\") { id } }"));
      Response other = post(query("{ getNode(node_id: \"LOCAL_ROOT\") { id } }"));

      assertThat(rejected.getStatusCode()).isEqualTo(503);
      assertThat(rejected.getBody().asString()).doesNotContain("data");
      assertThat(other.getStatusCode()).isEqualTo(200);
    } finally {
      release.countDown();
    }
  }
}
