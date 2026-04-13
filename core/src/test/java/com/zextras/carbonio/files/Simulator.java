// SPDX-FileCopyrightText: 2023 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files;

import com.google.inject.*;
import com.google.inject.Module;
import com.google.inject.util.Modules;
import com.zextras.carbonio.files.Constants.Config.Database;
import com.zextras.carbonio.files.Constants.ServiceDiscover.Config.Key;
import com.zextras.carbonio.files.cache.CacheHandler;
import com.zextras.carbonio.files.config.FilesConfig;
import com.zextras.carbonio.files.config.FilesModule;
import com.zextras.carbonio.files.dal.DatabaseManager;
import com.zextras.carbonio.files.dal.dao.ebean.Node;
import com.zextras.carbonio.files.netty.HttpRoutingHandler;
import com.zextras.carbonio.files.utilities.MockFilesConfig;
import com.zextras.carbonio.user_management.sdk.grpc.GetUserByEmailRequest;
import com.zextras.carbonio.user_management.sdk.grpc.GetUserByIdRequest;
import com.zextras.carbonio.user_management.sdk.grpc.GetUserMyselfRequest;
import com.zextras.carbonio.user_management.sdk.grpc.UserInfoProto;
import com.zextras.carbonio.user_management.sdk.grpc.UserInfoResponse;
import com.zextras.carbonio.user_management.sdk.grpc.UserManagementServiceGrpc;
import com.zextras.carbonio.user_management.sdk.grpc.UserManagementServiceGrpc.UserManagementServiceBlockingStub;
import com.zextras.carbonio.user_management.sdk.grpc.UserManagementServiceGrpc.UserManagementServiceImplBase;
import com.zextras.carbonio.user_management.sdk.grpc.UserMyselfProto;
import com.zextras.carbonio.user_management.sdk.grpc.UserMyselfResponse;
import com.zextras.carbonio.user_management.sdk.grpc.UserTypeProto;
import com.zextras.storages.internal.pojo.Query;
import com.zextras.storages.internal.pojo.StoragesBulkDeleteResponse;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.Status;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.HttpMethod;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.mockserver.client.MockServerClient;
import org.mockserver.integration.ClientAndServer;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;
import org.mockserver.model.JsonBody;
import org.mockserver.model.Parameter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.shaded.com.trilead.ssh2.crypto.Base64;

public class Simulator implements AutoCloseable {

  private static final Logger logger = LoggerFactory.getLogger(Simulator.class);
  private static final String UM_INPROCESS_BASE_NAME = "um-files-test";
  private static final AtomicInteger UM_COUNTER = new AtomicInteger();
  private String umInProcessName;

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

  private final Set<String> managedProperties = new HashSet<>();

  private Injector injector;
  private DatabaseManager databaseManager;
  private MockServerClient serviceDiscoverMock;
  private MockServerClient storagesMock;
  private MockServerClient previewServiceMock;
  private MockServerClient docsConnectorServiceMock;

  // gRPC in-process UM mock
  private MockUserManagementService mockUmService;
  private ManagedChannel umChannel;
  private Server umGrpcServer;

  //
  // Private methods
  //

  private void setManagedProperty(String key, String value) {
    System.setProperty(key, value);
    managedProperties.add(key);
  }

  private Simulator createInjector() {
    // Always create the InProcess channel so Guice can inject ManagedChannel and BlockingStub.
    // The InProcess server is only started when withUserManagement() is called; without it,
    // the channel will be in TRANSIENT_FAILURE state (simulating UM being unreachable).
    if (umChannel == null) {
      if (umInProcessName == null) {
        umInProcessName = UM_INPROCESS_BASE_NAME + "-" + UM_COUNTER.incrementAndGet();
      }
      umChannel = InProcessChannelBuilder.forName(umInProcessName).directExecutor().build();
    }
    if (mockUmService == null) {
      mockUmService = new MockUserManagementService();
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

    Module umOverride = new UmOverrideModule(umChannel);
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
    mockUmService = new MockUserManagementService();
    umInProcessName = UM_INPROCESS_BASE_NAME + "-" + UM_COUNTER.incrementAndGet();
    umChannel = InProcessChannelBuilder.forName(umInProcessName).directExecutor().build();

    try {
      umGrpcServer =
          InProcessServerBuilder.forName(umInProcessName)
              .directExecutor()
              .addService(mockUmService)
              .build()
              .start();
    } catch (IOException e) {
      throw new RuntimeException("Failed to start gRPC InProcessServer for UM", e);
    }

    return this;
  }

  /**
   * Registers a token-to-userId mapping so that subsequent gRPC {@code getUserMyself} calls
   * with the given token will return a valid user with the "carbonioFeatureFilesEnabled" feature.
   */
  private void registerUser(String cookie, String userId) {
    mockUmService.registerToken(cookie, userId);
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
    if (umGrpcServer != null) {
      umGrpcServer.shutdownNow();
      umGrpcServer = null;
    }
    if (umChannel != null) {
      umChannel.shutdownNow();
      umChannel = null;
    }
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
    // Container lifecycle (PostgreSQL, RabbitMQ) and the shared MockServer are JVM-scoped
    // singletons managed by Testcontainers' Ryuk — they are NOT stopped here.
    // This method only tears down per-Simulator resources: Ebean/HikariCP, gRPC UM server,
    // and MockServer expectations (reset, not stopped).
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

  public MockServerClient getServiceDiscoverMock() {
    return serviceDiscoverMock;
  }

  /**
   * Returns the mock UM gRPC service, allowing tests to register additional users
   * (e.g. for getUserById lookups in transfer ownership scenarios).
   */
  public MockUserManagementService getUserManagementService() {
    return mockUmService;
  }

  /**
   * Shuts down the UM gRPC InProcess server (but keeps the channel alive) to simulate
   * user-management being unreachable. After this call, the ManagedChannel will transition
   * to TRANSIENT_FAILURE state, causing health checks to report UM as unhealthy.
   */
  public void shutdownUserManagementServer() {
    if (umGrpcServer != null) {
      umGrpcServer.shutdownNow();
      try {
        umGrpcServer.awaitTermination(5, TimeUnit.SECONDS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
      umGrpcServer = null;
    }
    // Shut down the channel so getState() returns SHUTDOWN,
    // which the HealthService treats as unhealthy.
    if (umChannel != null) {
      umChannel.shutdownNow();
    }
  }

  public MockServerClient getStoragesMock() {
    return storagesMock;
  }

  public MockServerClient getPreviewServiceMock() {
    return previewServiceMock;
  }

  public MockServerClient getDocsConnectorServiceMock() {
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
    db.sqlUpdate("TRUNCATE user_notification_interest, notification, snapshot_node, snapshot_user, user_notifications_info, tombstone CASCADE").execute();
  }

  public void clearFileVersionCache() {
    injector.getInstance(CacheHandler.class).getFileVersionCache().flushAll();
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

  public void getBlob(String nodeId, int version) {
    storagesMock
        .when(
            HttpRequest.request()
                .withMethod(HttpMethod.GET.toString())
                .withPath("/download")
                .withQueryStringParameter(Parameter.param("node", nodeId))
                .withQueryStringParameter(Parameter.param("version", String.valueOf(version)))
                .withQueryStringParameter(Parameter.param("type", "files")))
        .respond(
            HttpResponse.response()
                .withStatusCode(200)
                .withBody((nodeId + version).getBytes(StandardCharsets.UTF_8)));
  }

  public void bulkDelete(List<String> ids) {
    final StoragesBulkDeleteResponse response = new StoragesBulkDeleteResponse();
    Query queryList = new Query();
    for (String id : ids) {
      queryList.setNode(id);
      queryList.setType("files");
    }
    response.setIds(List.of(queryList));
    storagesMock
        .when(
            HttpRequest.request()
                .withMethod(HttpMethod.POST.toString())
                .withPath("/bulk-delete"))
        .respond(
            HttpResponse.response()
                .withStatusCode(200)
                .withBody(JsonBody.json(response)));
  }

  /**
   * Guice module that overrides the ManagedChannel and BlockingStub bindings
   * to use the gRPC InProcess transport for testing.
   */
  private static class UmOverrideModule extends AbstractModule {

    private final ManagedChannel channel;

    UmOverrideModule(ManagedChannel channel) {
      this.channel = channel;
    }

    @Provides
    @Singleton
    public ManagedChannel provideUserManagementChannel() {
      return channel;
    }

    @Provides
    @Singleton
    public UserManagementServiceBlockingStub provideUserManagementStub() {
      return UserManagementServiceGrpc.newBlockingStub(channel);
    }
  }

  /**
   * In-memory gRPC service implementation for UserManagement. Supports
   * {@code getUserMyself} (token lookup), {@code getUserById} (userId lookup),
   * and {@code getUserByEmail} (email lookup).
   */
  public static class MockUserManagementService extends UserManagementServiceImplBase {

    private final Map<String, UserMyselfResponse> tokenToMyself = new ConcurrentHashMap<>();
    private final Map<String, UserInfoProto> userIdToInfo = new ConcurrentHashMap<>();

    /**
     * Registers a minimal token-to-userId mapping. A full {@link UserMyselfResponse} is built
     * with default values for email, name, domain, status, locale, and the
     * "carbonioFeatureFilesEnabled" feature enabled.
     */
    void registerToken(String token, String userId) {
      if (!tokenToMyself.containsKey(token)) {
        UserInfoProto info = UserInfoProto.newBuilder()
            .setUserId(userId)
            .setEmail("fake-email@example.com")
            .setFullName("Fake User")
            .setDomain("example.com")
            .setStatus("active")
            .setType(UserTypeProto.INTERNAL)
            .build();
        UserMyselfProto myself = UserMyselfProto.newBuilder()
            .setInfo(info)
            .setLocale("en")
            .addFeatures("carbonioFeatureFilesEnabled")
            .build();
        tokenToMyself.put(token, UserMyselfResponse.newBuilder().setUser(myself).build());
        userIdToInfo.put(userId, info);
      }
    }

    /**
     * Removes a userId from the {@code getUserById} lookup map so that subsequent
     * {@code getUserById} calls for this user will return NOT_FOUND.
     */
    public void unregisterUserById(String userId) {
      userIdToInfo.remove(userId);
    }

    /**
     * Registers a user profile for lookup by userId via {@code getUserById}.
     * This is used by integration tests that need to look up users other than
     * the requester (e.g. transfer ownership target user).
     */
    public void registerUserById(String userId, String email, String fullName,
        String domain, String status) {
      UserInfoProto info = UserInfoProto.newBuilder()
          .setUserId(userId)
          .setEmail(email)
          .setFullName(fullName)
          .setDomain(domain)
          .setStatus(status)
          .setType(UserTypeProto.INTERNAL)
          .build();
      userIdToInfo.put(userId, info);
    }

    void clearAll() {
      tokenToMyself.clear();
      userIdToInfo.clear();
    }

    @Override
    public void getUserMyself(GetUserMyselfRequest request,
        StreamObserver<UserMyselfResponse> responseObserver) {
      String token = request.getToken();
      UserMyselfResponse response = tokenToMyself.get(token);
      if (response != null) {
        responseObserver.onNext(response);
        responseObserver.onCompleted();
      } else {
        responseObserver.onError(
            Status.UNAUTHENTICATED.withDescription("Invalid token").asRuntimeException());
      }
    }

    @Override
    public void getUserById(GetUserByIdRequest request,
        StreamObserver<UserInfoResponse> responseObserver) {
      String userId = request.getUserId();
      UserInfoProto info = userIdToInfo.get(userId);
      if (info != null) {
        responseObserver.onNext(UserInfoResponse.newBuilder().setUser(info).build());
        responseObserver.onCompleted();
      } else {
        responseObserver.onError(
            Status.NOT_FOUND.withDescription("User not found").asRuntimeException());
      }
    }

    @Override
    public void getUserByEmail(GetUserByEmailRequest request,
        StreamObserver<UserInfoResponse> responseObserver) {
      String email = request.getUserEmail();
      // Search by email across registered users
      for (UserInfoProto info : userIdToInfo.values()) {
        if (info.getEmail().equals(email)) {
          responseObserver.onNext(UserInfoResponse.newBuilder().setUser(info).build());
          responseObserver.onCompleted();
          return;
        }
      }
      responseObserver.onError(
          Status.NOT_FOUND.withDescription("User not found by email").asRuntimeException());
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
            simulator.registerUser(cookie, userId);
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
