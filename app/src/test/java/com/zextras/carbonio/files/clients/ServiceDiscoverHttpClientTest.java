// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.clients;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.sun.net.httpserver.HttpServer;
import com.zextras.carbonio.files.Constants;
import com.zextras.carbonio.quarkus.extensions.bootstrap.NetworkingConfigService;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ServiceDiscoverHttpClientTest {

  private HttpServer server;
  private ServiceDiscoverHttpClient client;

  @BeforeEach
  void setUp() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.setExecutor(Executors.newCachedThreadPool());
    server.createContext(
        "/",
        exchange -> {
          try {
            Thread.sleep(Duration.ofSeconds(8));
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
    client = new ServiceDiscoverHttpClient(networkingConfig);
  }

  @AfterEach
  void tearDown() {
    server.stop(0);
  }

  @Test
  void getConfigShouldGiveUpOnAStalledConsul() {
    long start = System.nanoTime();

    Optional<String> value = client.getConfig("page-token-secret-key");

    assertThat(value).isEmpty();
    assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(6));
  }

  @Test
  void createConfigIfAbsentShouldGiveUpOnAStalledConsul() {
    long start = System.nanoTime();

    boolean created = client.createConfigIfAbsent("page-token-secret-key", "value");

    assertThat(created).isFalse();
    assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(6));
  }
}
