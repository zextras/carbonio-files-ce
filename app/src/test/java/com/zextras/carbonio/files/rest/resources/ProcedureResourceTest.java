// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.rest.resources;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.zextras.carbonio.files.config.TransferPool;
import com.zextras.carbonio.files.dal.dao.UserId;
import com.zextras.carbonio.files.dal.dao.UserMyself;
import com.zextras.carbonio.files.dal.dao.ebean.ACL;
import com.zextras.carbonio.files.dal.dao.ebean.ACL.SharePermission;
import com.zextras.carbonio.files.rest.services.ProcedureService;
import io.vavr.control.Try;
import jakarta.ws.rs.core.Response;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.NoSuchElementException;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ProcedureResourceTest {

  private static final String NODE_ID = "11111111-1111-1111-1111-111111111111";
  private static final String BODY = "{\"nodeId\":\"" + NODE_ID + "\",\"targetModule\":\"MAILS\"}";

  private final ProcedureService procedureService = mock(ProcedureService.class);
  private final com.zextras.carbonio.files.utilities.PermissionsChecker permissionsChecker =
      mock(com.zextras.carbonio.files.utilities.PermissionsChecker.class);
  private final BlobAuthenticator authenticator = mock(BlobAuthenticator.class);
  private final TransferPool transferPool = mock(TransferPool.class);
  private final ProcedureResource resource =
      new ProcedureResource(procedureService, permissionsChecker, authenticator, transferPool);
  private ThreadPoolExecutor pool;

  @BeforeEach
  void setUp() {
    pool =
        new ThreadPoolExecutor(
            1,
            1,
            0L,
            TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(1),
            runnable -> new Thread(runnable, "carbonio-files-transfer-test"),
            new ThreadPoolExecutor.AbortPolicy());
    when(transferPool.get()).thenReturn(pool);
    UserMyself requester = mock(UserMyself.class);
    when(requester.getId()).thenReturn(new UserId("user"));
    when(authenticator.requireUser(any(), any())).thenReturn(requester);
    when(permissionsChecker.getPermissions(anyString(), anyString()))
        .thenReturn(ACL.decode(SharePermission.READ_ONLY));
  }

  @AfterEach
  void tearDown() {
    pool.shutdownNow();
  }

  private static InputStream body(String json) {
    return new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8));
  }

  private Response uploadTo(String json) throws Exception {
    return resource
        .uploadTo("ZM_AUTH_TOKEN=t", "t", body(json))
        .subscribeAsCompletionStage()
        .get(5, TimeUnit.SECONDS);
  }

  @Test
  void theWholeRequestRunsOnATransferThread() throws Exception {
    AtomicReference<String> authThread = new AtomicReference<>();
    AtomicReference<String> uploadThread = new AtomicReference<>();
    when(authenticator.requireUser(any(), any()))
        .thenAnswer(
            invocation -> {
              authThread.set(Thread.currentThread().getName());
              UserMyself requester = mock(UserMyself.class);
              when(requester.getId()).thenReturn(new UserId("user"));
              return requester;
            });
    when(procedureService.uploadToModule(any(), any(), any(), any()))
        .thenAnswer(
            invocation -> {
              uploadThread.set(Thread.currentThread().getName());
              return Try.success("attachment-1");
            });

    Response response = uploadTo(BODY);

    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(response.getEntity().toString()).contains("attachment-1");
    assertThat(authThread).hasValue("carbonio-files-transfer-test");
    assertThat(uploadThread).hasValue("carbonio-files-transfer-test");
  }

  @Test
  void aFullPoolAnswers503BeforeTouchingTheBodyOrTheServices() throws Exception {
    CountDownLatch busy = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    Runnable blocker =
        () -> {
          busy.countDown();
          try {
            release.await();
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
        };
    pool.execute(blocker);
    assertThat(busy.await(5, TimeUnit.SECONDS)).isTrue();
    pool.execute(blocker);

    try {
      assertThatThrownBy(() -> uploadTo(BODY))
          .isInstanceOf(ExecutionException.class)
          .cause()
          .isInstanceOf(RejectedExecutionException.class);
      assertThat(
              new BlobExceptionMapper()
                  .toResponse(new RejectedExecutionException("full"))
                  .getStatus())
          .isEqualTo(503);
      verify(authenticator, never()).requireUser(any(), any());
      verify(procedureService, never()).uploadToModule(any(), any(), any(), any());
    } finally {
      release.countDown();
    }
  }

  @Test
  void anOversizedBodyAnswersAnEmpty413() throws Exception {
    String oversized = "{\"nodeId\":\"" + "a".repeat(300 * 1024) + "\"}";

    Response response = uploadTo(oversized);

    assertThat(response.getStatus()).isEqualTo(413);
    assertThat(response.getEntity()).isNull();
    verify(authenticator, never()).requireUser(any(), any());
  }

  @Test
  void aDeniedNodeFailsWithNoSuchElementWhichMapsTo404() {
    when(permissionsChecker.getPermissions(anyString(), anyString()))
        .thenReturn(ACL.decode(ACL.NONE));

    assertThatThrownBy(() -> uploadTo(BODY)).cause().isInstanceOf(NoSuchElementException.class);
    assertThat(new BlobExceptionMapper().toResponse(new NoSuchElementException("x")).getStatus())
        .isEqualTo(404);
  }

  @Test
  void aFolderTargetFailsWithIllegalArgumentWhichMapsTo400() {
    when(procedureService.uploadToModule(any(), any(), any(), any()))
        .thenReturn(Try.failure(new IllegalArgumentException("folder")));

    assertThatThrownBy(() -> uploadTo(BODY)).cause().isInstanceOf(IllegalArgumentException.class);
    assertThat(new BlobExceptionMapper().toResponse(new IllegalArgumentException("x")).getStatus())
        .isEqualTo(400);
  }

  @Test
  void aMalformedBodyFailsWithAGenericExceptionWhichMapsTo500() {
    assertThatThrownBy(() -> uploadTo("{\"nodeId\": "))
        .cause()
        .isInstanceOf(RuntimeException.class);
    assertThat(
            new BlobExceptionMapper()
                .toResponse(
                    new RuntimeException(
                        "bad", new com.fasterxml.jackson.core.JsonParseException(null, "x")))
                .getStatus())
        .isEqualTo(500);
  }
}
