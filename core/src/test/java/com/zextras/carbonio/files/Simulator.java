// SPDX-FileCopyrightText: 2023 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files;

import com.google.inject.AbstractModule;
import com.google.inject.Guice;
import com.google.inject.Injector;
import com.google.inject.Module;
import com.google.inject.Provides;
import com.google.inject.Singleton;
import com.google.inject.util.Modules;
import com.zextras.carbonio.files.Constants.Config.Database;
import com.zextras.carbonio.files.Constants.ServiceDiscover.Config.Key;
import com.zextras.carbonio.files.config.FilesConfig;
import com.zextras.carbonio.files.config.FilesModule;
import com.zextras.carbonio.files.dal.DatabaseManager;
import com.zextras.carbonio.files.dal.dao.ebean.Node;
import com.zextras.carbonio.files.netty.HttpRoutingHandler;
import com.zextras.carbonio.files.utilities.MockFilesConfig;
import com.zextras.carbonio.files.utilities.MockUserManagementService;
import com.zextras.carbonio.user_management.sdk.rest.ApiClient;
import com.zextras.carbonio.user_management.sdk.rest.api.UserResourceApi;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.HttpMethod;
import org.mockserver.client.MockServerClient;
import org.mockserver.integration.ClientAndServer;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.shaded.com.trilead.ssh2.crypto.Base64;

import java.net.http.HttpClient;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class Simulator implements AutoCloseable {

  private static final Logger logger = LoggerFactory.getLogger(Simulator.class);

  // Singleton containers: started once per JVM, reused across all test classes.
  // Testcontainers' Ryuk will clean them up when the JVM exits.
  private static final PostgreSQLContainer<?> SHARED_POSTGRES =
      new PostgreSQLContainer<>("postgres:16.6");
  private static final RabbitMQContainer SHARED_RABBITMQ =
      new RabbitMQContainer("rabbitmq:3.13.4");

  // Singleton MockServer: started once per JVM on fixed ports, reused across all test classes.
  // Unlike the container fields (static final, never null, safe to lock on directly),
  // sharedMockServer starts null — so we need a separate lock object to synchronize on.
  private static volatile ClientAndServer sharedMockServer;
  private static final Object MOCK_SERVER_LOCK = new Object();

  // Dedicated MockServer instance for the UM REST fake, bound only to
  // Constants.Config.UserManagement.DEFAULT_PORT. It is NOT folded into sharedMockServer: that
  // instance answers on multiple fixed ports (storages/preview/docs-connector) but MockServer
  // matches expectations by path across ALL of an instance's bound ports regardless of which port
  // the request physically arrived on, so a shared "/q/health/live" path (used by both
  // docs-connector and user-management) would collide between the two fakes. A separate instance
  // avoids that entirely.
  private static volatile ClientAndServer sharedUserManagementMockServer;
  private static final Object UM_MOCK_SERVER_LOCK = new Object();

  private final Set<String> managedProperties = new HashSet<>();

  private Injector injector;
  private DatabaseManager databaseManager;
  private MockServerClient serviceDiscoverMock;
  private MockServerClient storagesMock;
  private MockServerClient previewServiceMock;
  private MockServerClient docsConnectorServiceMock;

  // REST UM mock (MockServer-backed) + the UserResourceApi override pointed at it.
  private MockUserManagementService mockUmService;
  private UserResourceApi userManagementApi;
  private boolean userManagementStarted;

  //
  // Private methods
  //

  private void setManagedProperty(String key, String value) {
    System.setProperty(key, value);
    managedProperties.add(key);
  }

  private Simulator createInjector() {
    // Always create the UserResourceApi override so Guice can inject it. The MockServer instance
    // it points at is only started when withUserManagement() is called; without it, every call
    // through userManagementApi fails with a connection-refused ApiException, simulating UM being
    // unreachable — same intent as the old gRPC InProcess channel left without a server behind it.
    if (userManagementApi == null) {
      HttpClient.Builder httpClientBuilder =
          HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1);
      ApiClient apiClient =
          new ApiClient(
              httpClientBuilder,
              ApiClient.createDefaultObjectMapper(),
              "http://localhost:" + Constants.Config.UserManagement.DEFAULT_PORT);
      userManagementApi = new UserResourceApi(apiClient);
    }
    if (mockUmService == null) {
      mockUmService =
          new MockUserManagementService(
              new MockServerClient("localhost", Constants.Config.UserManagement.DEFAULT_PORT));
    }

    Module overrideModule = new AbstractModule() {
        @Provides
        @Singleton
        public FilesConfig provideFilesConfig() throws Exception {
            final MockFilesConfig config = new MockFilesConfig();
            config.loadConfig();
            return config;
        }
    };

    Module umOverride = new UmOverrideModule(userManagementApi);
    Module finalOverride = Modules.combine(overrideModule, umOverride);

    injector = Guice.createInjector(
        Modules.override(new FilesModule()).with(finalOverride)
    );
    return this;
  }

  private Simulator startDatabase() {
    synchronized (SHARED_POSTGRES) {
      if (!SHARED_POSTGRES.isRunning()) {
        SHARED_POSTGRES.start();
      }
    }
    // Set the System.properties for the dynamic database url and port
    setManagedProperty(Database.HOST_PROPERTY, SHARED_POSTGRES.getHost());
    setManagedProperty(Database.PORT_PROPERTY, String.valueOf(SHARED_POSTGRES.getFirstMappedPort()));

    return this;
  }

  private Simulator startMessageBroker() {
    synchronized (SHARED_RABBITMQ) {
      if (!SHARED_RABBITMQ.isRunning()) {
        SHARED_RABBITMQ.start();
      }
    }
    // Set the System.properties for the dynamic rabbit url and port
    setManagedProperty(Constants.Config.MessageBroker.HOST_PROPERTY, SHARED_RABBITMQ.getHost());
    setManagedProperty(Constants.Config.MessageBroker.PORT_PROPERTY, String.valueOf(SHARED_RABBITMQ.getFirstMappedPort()));

    return this;
  }

  private Simulator startEbeanDatabaseManager() {
    if (databaseManager == null) {
      databaseManager = injector.getInstance(DatabaseManager.class);
      databaseManager.initialize();
    }

    return this;
  }

  private Simulator startServiceDiscover() {
    startMockServer();
    serviceDiscoverMock = new MockServerClient("localhost", 8500);

    String dbName;
    String dbUsername;
    String dbPassword;

    String adminUsername;
    String adminPassword;

    if (SHARED_POSTGRES.isRunning()) {
      dbName = SHARED_POSTGRES.getDatabaseName();
      dbUsername = SHARED_POSTGRES.getUsername();
      dbPassword = SHARED_POSTGRES.getPassword();
    } else {
      logger.warn(
          "The ServiceDiscover will be mocked without a database container. The database "
              + "credentials are the default one specified in the Constants class");

      dbName = Key.DB_NAME;
      dbUsername = Key.DB_USERNAME;
      dbPassword = Key.DB_PASSWORD;
    }

    if (SHARED_RABBITMQ.isRunning()) {
      adminUsername = SHARED_RABBITMQ.getAdminUsername();
      adminPassword = SHARED_RABBITMQ.getAdminPassword();
    } else {
      logger.warn("The ServiceDiscover will be mocked without a rabbitMQ container");

      adminUsername = Constants.MessageBroker.Config.DEFAULT_USERNAME;
      adminPassword = Constants.MessageBroker.Config.DEFAULT_PASSWORD;
    }

    final String encodedDbName = new String(Base64.encode(dbName.getBytes()));
    final String encodedDbUsername = new String(Base64.encode(dbUsername.getBytes()));
    final String encodedDbPassword = new String(Base64.encode(dbPassword.getBytes()));
    final String encodedAdminUsername = new String(Base64.encode(adminUsername.getBytes()));
    final String encodedAdminPassword = new String(Base64.encode(adminPassword.getBytes()));
    final String bodyPayloadFormat = "[{\"Key\":\"%s\",\"Value\":\"%s\"}]";

    serviceDiscoverMock
        .when(
            HttpRequest.request()
                .withMethod(HttpMethod.GET.toString())
                .withPath("/v1/kv/carbonio-files/db-name")
                .withHeader("X-Consul-Token", ""))
        .respond(
            HttpResponse.response()
                .withStatusCode(200)
                .withBody(
                    String.format(bodyPayloadFormat, "carbonio-files/db-name", encodedDbName)));

    serviceDiscoverMock
        .when(
            HttpRequest.request()
                .withMethod(HttpMethod.GET.toString())
                .withPath("/v1/kv/carbonio-files/db-username")
                .withHeader("X-Consul-Token", ""))
        .respond(
            HttpResponse.response()
                .withStatusCode(200)
                .withBody(
                    String.format(
                        bodyPayloadFormat, "carbonio-files/db-username", encodedDbUsername)));

    serviceDiscoverMock
        .when(
            HttpRequest.request()
                .withMethod(HttpMethod.GET.toString())
                .withPath("/v1/kv/carbonio-files/db-password")
                .withHeader("X-Consul-Token", ""))
        .respond(
            HttpResponse.response()
                .withStatusCode(200)
                .withBody(
                    String.format(
                        bodyPayloadFormat, "carbonio-files/db-password", encodedDbPassword)));

    serviceDiscoverMock
        .when(
            HttpRequest.request()
                .withMethod(HttpMethod.GET.toString())
                .withPath("/v1/kv/carbonio-message-broker/default/password")
                .withHeader("X-Consul-Token", ""))
        .respond(
            HttpResponse.response()
                .withStatusCode(200)
                .withBody(
                    String.format(
                        bodyPayloadFormat, "carbonio-message-broker/default/password", encodedAdminPassword)));

    serviceDiscoverMock
        .when(
            HttpRequest.request()
                .withMethod(HttpMethod.GET.toString())
                .withPath("/v1/kv/carbonio-message-broker/default/username")
                .withHeader("X-Consul-Token", ""))
        .respond(
            HttpResponse.response()
                .withStatusCode(200)
                .withBody(
                    String.format(
                        bodyPayloadFormat, "carbonio-message-broker/default/username", encodedAdminUsername)));

    return this;
  }

  private Simulator startUserManagement() {
    startUserManagementMockServer();

    MockServerClient client =
        new MockServerClient("localhost", Constants.Config.UserManagement.DEFAULT_PORT);
    mockUmService = new MockUserManagementService(client);
    // UserManagementHttpClient (used by HealthService) reads host/port from FilesConfig (unlike
    // userManagementApi, which is overridden directly via Guice), so it needs the same redirect
    // storages/preview/docsConnector rely on.
    setManagedProperty(Constants.Config.UserManagement.HOST_PROPERTY, "localhost");
    stubUserManagementHealthLive(client, 200);
    userManagementStarted = true;

    return this;
  }

  private void startUserManagementMockServer() {
    synchronized (UM_MOCK_SERVER_LOCK) {
      if (sharedUserManagementMockServer == null || !sharedUserManagementMockServer.isRunning()) {
        sharedUserManagementMockServer =
            ClientAndServer.startClientAndServer(Constants.Config.UserManagement.DEFAULT_PORT);
      }
    }
  }

  private void stubUserManagementHealthLive(MockServerClient client, int statusCode) {
    HttpRequest healthRequest =
        HttpRequest.request().withMethod(HttpMethod.GET.toString()).withPath("/q/health/live");
    client.clear(healthRequest);
    client.when(healthRequest).respond(HttpResponse.response().withStatusCode(statusCode));
  }

  private Simulator startStorages() {
    startMockServer();

    storagesMock =
        new MockServerClient(
            "localhost",
            Constants.Config.Storages.DEFAULT_PORT);
    setManagedProperty(Constants.Config.Storages.HOST_PROPERTY, "localhost");

    return this;
  }

  private Simulator startPreviewService() {
    startMockServer();

    previewServiceMock = new MockServerClient(
      "localhost",
      Constants.Config.Preview.DEFAULT_PORT
    );
    setManagedProperty(Constants.Config.Preview.HOST_PROPERTY, "localhost");

    return this;
  }

  private Simulator startDocsConnectorService() {
    startMockServer();

    docsConnectorServiceMock = new MockServerClient(
      "localhost",
      Constants.Config.DocsConnector.DEFAULT_PORT
    );
    setManagedProperty(Constants.Config.DocsConnector.HOST_PROPERTY, "localhost");

    return this;
  }

  private void startMockServer() {
    synchronized (MOCK_SERVER_LOCK) {
      if (sharedMockServer == null || !sharedMockServer.isRunning()) {
        final int storagesPort = Constants.Config.Storages.DEFAULT_PORT;
        final int previewServicePort = Constants.Config.Preview.DEFAULT_PORT;
        final int docsConnectorServicePort = Constants.Config.DocsConnector.DEFAULT_PORT;

        sharedMockServer =
            ClientAndServer.startClientAndServer(8500, storagesPort, previewServicePort, docsConnectorServicePort);
      }
    }
  }

  private void stopEbeanDatabaseManager() {
    if (databaseManager != null) {
      databaseManager.stop();
    }
  }

  private void resetServiceDiscoverMock() {
    if (serviceDiscoverMock != null && serviceDiscoverMock.hasStarted()) {
      serviceDiscoverMock.reset();
    }
    serviceDiscoverMock = null;
  }

  private void stopUserManagement() {
    // The shared MockServer instance is JVM-scoped (like sharedMockServer) and is NOT stopped
    // here; only per-Simulator state (expectations registered via mockUmService) is reset. Guard
    // on userManagementStarted: if withUserManagement() was never called, mockUmService's
    // MockServerClient points at a port nothing is listening on, and resetting it would attempt a
    // real admin HTTP call and throw.
    if (userManagementStarted && mockUmService != null) {
      mockUmService.clearAll();
    }
    mockUmService = null;
    userManagementStarted = false;
  }

  private void resetStoragesMock() {
    if (storagesMock != null && storagesMock.hasStarted()) {
      storagesMock.reset();
    }
    storagesMock = null;
  }

  private void resetPreviewMock() {
    if (previewServiceMock != null && previewServiceMock.hasStarted()) {
      previewServiceMock.reset();
    }
    previewServiceMock = null;
  }

  private void resetDocsConnectorMock() {
    if (docsConnectorServiceMock != null && docsConnectorServiceMock.hasStarted()) {
      docsConnectorServiceMock.reset();
    }
    docsConnectorServiceMock = null;
  }

  //
  // Public methods
  //

  public Simulator() {}

  public Simulator start() {
    return startEbeanDatabaseManager();
  }

  public void stopAll() {
    // Container lifecycle (PostgreSQL, RabbitMQ) and the shared MockServer instances are
    // JVM-scoped singletons managed by Testcontainers' Ryuk — they are NOT stopped here.
    // This method only tears down per-Simulator resources: Ebean/HikariCP and MockServer
    // expectations (reset, not stopped).
    resetDocsConnectorMock();
    resetPreviewMock();
    resetStoragesMock();
    stopUserManagement();
    resetServiceDiscoverMock();
    stopEbeanDatabaseManager();
    // Clear all System properties set by start methods to prevent leaking config to the next Simulator.
    managedProperties.forEach(System::clearProperty);
  }

  @Override
  public void close() {
    stopAll();
  }

  public Injector getInjector() {
    return injector;
  }

  /**
   * Returns the mock UM REST service, allowing tests to register additional users
   * (e.g. for getUserById lookups in transfer ownership scenarios).
   */
  public MockUserManagementService getUserManagementService() {
    return mockUmService;
  }

  /**
   * Simulates user-management being unreachable: re-stubs {@code GET /q/health/live} on the UM
   * MockServer to a non-2xx status, causing {@code UserManagementHttpClient#healthLiveCheck()}
   * (and therefore {@code HealthService#isUserManagementLive()}) to report UM as unhealthy.
   */
  public void shutdownUserManagementServer() {
    if (userManagementStarted) {
      MockServerClient client =
          new MockServerClient("localhost", Constants.Config.UserManagement.DEFAULT_PORT);
      stubUserManagementHealthLive(client, 503);
    }
  }

  public MockServerClient getStoragesMock() {
    return storagesMock;
  }

  public MockServerClient getPreviewMock() {
    return previewServiceMock;
  }

  public MockServerClient getDocsConnectorMock() {
    return docsConnectorServiceMock;
  }

  public EmbeddedChannel getNettyChannel() {
    return new EmbeddedChannel(injector.getInstance(HttpRoutingHandler.class));
  }

  public void resetDatabase() {
    var db = databaseManager.getEbeanDatabase();
    // Delete test nodes but preserve ROOT nodes (LOCAL_ROOT, TRASH_ROOT) which have null owner_id.
    db.find(Node.class)
            .where()
            .isNotNull("mOwnerId")
            .delete();
    // Wipe notification and snapshot tables (not FK-linked to node, so not cascade-deleted above).
    db.sqlUpdate("TRUNCATE user_notification_interest, notification, snapshot_node, snapshot_user, user_notifications_info CASCADE").execute();
  }

  public void reinitializeMocks() {
    if (serviceDiscoverMock != null && serviceDiscoverMock.hasStarted()) {
      serviceDiscoverMock.reset();
      startServiceDiscover();
    }
    if (storagesMock != null && storagesMock.hasStarted()) {
      storagesMock.reset();
    }
    if (previewServiceMock != null && previewServiceMock.hasStarted()) {
      previewServiceMock.reset();
    }
    if (docsConnectorServiceMock != null && docsConnectorServiceMock.hasStarted()) {
      docsConnectorServiceMock.reset();
    }
  }

  /**
   * Guice module that overrides the {@link UserResourceApi} binding to point at the MockServer
   * fake used for testing.
   */
  private static class UmOverrideModule extends AbstractModule {

    private final UserResourceApi userResourceApi;

    UmOverrideModule(UserResourceApi userResourceApi) {
      this.userResourceApi = userResourceApi;
    }

    @Provides
    @Singleton
    public UserResourceApi provideUserManagementApi() {
      return userResourceApi;
    }
  }

  public static class SimulatorBuilder {

    private Simulator simulator;

    public static SimulatorBuilder aSimulator() {
      return new SimulatorBuilder();
    }

    public SimulatorBuilder init() {
      simulator = new Simulator();
      return this;
    }

    public SimulatorBuilder withDatabase() {
      simulator.startDatabase();
      return this;
    }

    public SimulatorBuilder withMessageBroker() {
      simulator.startMessageBroker();
      return this;
    }

    public SimulatorBuilder withServiceDiscover() {
      simulator.startServiceDiscover();
      return this;
    }

    public SimulatorBuilder withUserManagement(Map<String, String> users) {
      simulator.startUserManagement();
      users.forEach(
          (cookie, userId) -> {
            simulator.mockUmService.registerToken(cookie, userId);
          });
      return this;
    }

    public SimulatorBuilder withStorages() {
      simulator.startStorages();
      return this;
    }

    public SimulatorBuilder withPreview() {
      simulator.startPreviewService();
      return this;
    }

    public SimulatorBuilder withDocsConnector() {
      simulator.startDocsConnectorService();
      return this;
    }

    public Simulator build() {
      simulator.createInjector();
      return simulator;
    }
  }
}
