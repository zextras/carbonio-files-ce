// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.clients;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.sun.net.httpserver.HttpServer;
import com.zextras.carbonio.files.Constants;
import com.zextras.carbonio.quarkus.extensions.bootstrap.NetworkingConfigService;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ServiceDiscoverHttpClientTest {

  private static final Duration GIVE_UP_WITHIN = Duration.ofSeconds(2);

  private final CountDownLatch stalled = new CountDownLatch(1);
  private ExecutorService executor;
  private HttpServer server;
  private ServiceDiscoverHttpClient client;

  @BeforeEach
  void setUp() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    executor = Executors.newCachedThreadPool();
    server.setExecutor(executor);
    server.createContext(
        "/",
        exchange -> {
          try {
            stalled.await();
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
          exchange.sendResponseHeaders(404, -1);
          exchange.close();
        });
    server.start();

    NetworkingConfigService networkingConfig = mock(NetworkingConfigService.class);
    when(networkingConfig.get(Constants.ServiceDiscover.HOST_PROPERTY))
        .thenReturn(Optional.of("127.0.0.1"));
    when(networkingConfig.get(Constants.ServiceDiscover.PORT_PROPERTY))
        .thenReturn(Optional.of(String.valueOf(server.getAddress().getPort())));
    client =
        new ServiceDiscoverHttpClient(
            networkingConfig, Duration.ofMillis(100), Duration.ofMillis(100));
  }

  @AfterEach
  void tearDown() {
    stalled.countDown();
    server.stop(0);
    executor.shutdownNow();
  }

  @Test
  void getConfigShouldGiveUpOnAStalledConsul() {
    Optional<String> value =
        assertTimeoutPreemptively(GIVE_UP_WITHIN, () -> client.getConfig("page-token-secret-key"));

    assertThat(value).isEmpty();
  }

  @Test
  void createConfigIfAbsentShouldGiveUpOnAStalledConsul() {
    boolean created =
        assertTimeoutPreemptively(
            GIVE_UP_WITHIN, () -> client.createConfigIfAbsent("page-token-secret-key", "value"));

    assertThat(created).isFalse();
  }
}
