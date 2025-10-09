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
import com.zextras.carbonio.files.dal.EbeanDatabaseManager;
import com.zextras.carbonio.files.dal.dao.ebean.Node;
import com.zextras.carbonio.files.netty.HttpRoutingHandler;
import com.zextras.carbonio.files.utilities.MockFilesConfig;
import com.zextras.carbonio.usermanagement.entities.UserId;
import com.zextras.carbonio.usermanagement.entities.UserInfo;
import com.zextras.carbonio.usermanagement.entities.UserMyself;
import com.zextras.carbonio.usermanagement.enumerations.UserStatus;
import com.zextras.carbonio.usermanagement.enumerations.UserType;
import com.zextras.storages.internal.pojo.Query;
import com.zextras.storages.internal.pojo.StoragesBulkDeleteResponse;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.HttpMethod;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.mockserver.client.MockServerClient;
import org.mockserver.integration.ClientAndServer;
import org.mockserver.model.Cookie;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;
import org.mockserver.model.JsonBody;
import org.mockserver.model.Parameter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.shaded.com.trilead.ssh2.crypto.Base64;
import org.testcontainers.containers.RabbitMQContainer;

@Testcontainers
public class Simulator implements AutoCloseable {

  private static final Logger logger = LoggerFactory.getLogger(Simulator.class);
  private Injector injector;
  private PostgreSQLContainer<?> postgreSQLContainer;
  private RabbitMQContainer messageBrokerContainer;
  private EbeanDatabaseManager ebeanDatabaseManager;
  private ClientAndServer clientAndServer;
  private MockServerClient serviceDiscoverMock;
  private MockServerClient userManagementMock;
  private MockServerClient storagesMock;
  private MockServerClient previewServiceMock;
  private MockServerClient docsConnectorServiceMock;

  //
  // Private methods
  //

  private Simulator createInjector() {
    Module overrideModule = new AbstractModule() {
        @Provides
        @Singleton
        public FilesConfig provideFilesConfig() throws Exception {
            final MockFilesConfig config = new MockFilesConfig();
            config.loadConfig();
            return config;
        }
    };
    injector = Guice.createInjector(
        Modules.override(new FilesModule()).with(overrideModule)
    );
    return this;
  }

  private Simulator startDatabase() {
    if (postgreSQLContainer == null) {
      postgreSQLContainer = new PostgreSQLContainer<>("postgres:16.6");
    }

    postgreSQLContainer.start();

    // Set the System.properties for the dynamic database url and port
    System.setProperty(Database.HOST_PROPERTY, postgreSQLContainer.getHost());
    System.setProperty(Database.PORT_PROPERTY, String.valueOf(postgreSQLContainer.getFirstMappedPort()));

    return this;
  }

  private Simulator startMessageBroker() {
    if (messageBrokerContainer == null) {
      messageBrokerContainer = new RabbitMQContainer("rabbitmq:3.13.4");
    }
    messageBrokerContainer.start();

    // Set the System.properties for the dynamic rabbit url and port
    System.setProperty(Constants.Config.MessageBroker.HOST_PROPERTY, messageBrokerContainer.getHost());
    System.setProperty(Constants.Config.MessageBroker.PORT_PROPERTY, String.valueOf(messageBrokerContainer.getFirstMappedPort()));

    return this;
  }

  private Simulator startEbeanDatabaseManager() {
    if (ebeanDatabaseManager == null) {
      ebeanDatabaseManager = injector.getInstance(EbeanDatabaseManager.class);
      ebeanDatabaseManager.start();
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

    if (postgreSQLContainer != null && postgreSQLContainer.isRunning()) {
      dbName = postgreSQLContainer.getDatabaseName();
      dbUsername = postgreSQLContainer.getUsername();
      dbPassword = postgreSQLContainer.getPassword();
    } else {
      logger.warn(
          "The ServiceDiscover will be mocked without a database container. The database "
              + "credentials are the default one specified in the Constants class");

      dbName = Key.DB_NAME;
      dbUsername = Key.DB_USERNAME;
      dbPassword = Key.DB_PASSWORD;
    }

    if (messageBrokerContainer != null && messageBrokerContainer.isRunning()) {
      adminUsername = messageBrokerContainer.getAdminUsername();
      adminPassword = messageBrokerContainer.getAdminPassword();
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
    startMockServer();

    final FilesConfig filesConfig = injector.getInstance(FilesConfig.class);
    userManagementMock =
        new MockServerClient(
            filesConfig.getUserManagementHost(),
            Integer.parseInt(filesConfig.getUserManagementPort()));

    return this;
  }

  private void getUser(String cookie, String userId) {
    final UserMyself userInfo =
        new UserMyself(
            new UserId(userId),
            "fake-email@example.com",
            "Fake User",
            "example.com",
            Locale.ENGLISH,
            UserType.INTERNAL);

    userManagementMock
        .when(
            HttpRequest.request()
                .withMethod(HttpMethod.GET.toString())
                .withPath("/users/myself/")
                .withCookie(Cookie.cookie("ZM_AUTH_TOKEN", cookie)))
        .respond(HttpResponse.response().withStatusCode(200).withBody(JsonBody.json(userInfo)));
  }

  private Simulator startStorages() {
    startMockServer();

    final FilesConfig filesConfig = injector.getInstance(FilesConfig.class);
    storagesMock =
        new MockServerClient(
            filesConfig.getStoragesHost(),
            Integer.parseInt(filesConfig.getStoragesPort()));

    return this;
  }

  private Simulator startPreviewService() {
    startMockServer();

    final FilesConfig filesConfig = injector.getInstance(FilesConfig.class);
    previewServiceMock = new MockServerClient(
      filesConfig.getPreviewHost(),
      Integer.parseInt(filesConfig.getPreviewPort())
    );

    return this;
  }

  private Simulator startDocsConnectorService() {
    startMockServer();

    final FilesConfig filesConfig = injector.getInstance(FilesConfig.class);
    docsConnectorServiceMock = new MockServerClient(
      filesConfig.getDocsConnectorHost(),
      Integer.parseInt(filesConfig.getDocsConnectorPort())
    );

    return this;
  }

  private void startMockServer() {
    if (clientAndServer == null) {
      final FilesConfig filesConfig = injector.getInstance(FilesConfig.class);
      final int userManagementPort = Integer.parseInt(filesConfig.getUserManagementPort());
      final int storagesPort = Integer.parseInt(filesConfig.getStoragesPort());
      final int previewServicePort = Integer.parseInt(filesConfig.getPreviewPort());
      final int docsConnectorServicePort = Integer.parseInt(filesConfig.getDocsConnectorPort());

      clientAndServer =
          ClientAndServer.startClientAndServer(8500, userManagementPort, storagesPort, previewServicePort, docsConnectorServicePort);
    }
  }

  private void stopDatabase() {
    if (postgreSQLContainer != null && postgreSQLContainer.isRunning()) {
      postgreSQLContainer.stop();
    }
  }

  private void stopRabbitMq() {
    if (messageBrokerContainer != null && messageBrokerContainer.isRunning()) {
      messageBrokerContainer.stop();
    }
  }

  private void stopEbeanDatabaseManager() {
    if (ebeanDatabaseManager != null) {
      ebeanDatabaseManager.stop();
    }
  }

  private void stopServiceDiscover() {
    if (serviceDiscoverMock != null && serviceDiscoverMock.hasStarted()) {
      serviceDiscoverMock.stop();
    }
  }

  private void stopUserManagement() {
    if (userManagementMock != null && userManagementMock.hasStarted()) {
      userManagementMock.stop();
    }
  }

  private void stopStoragesService() {
    if (previewServiceMock != null && previewServiceMock.hasStarted()) {
      previewServiceMock.stop();
    }
  }

  private void stopPreviewService() {
    if (previewServiceMock != null && previewServiceMock.hasStarted()) {
      previewServiceMock.stop();
    }
  }

  private void stopDocsConnectorService() {
    if (docsConnectorServiceMock != null && docsConnectorServiceMock.hasStarted()) {
      docsConnectorServiceMock.stop();
    }
  }

  //
  // Public methods
  //

  public Simulator() {}

  public Simulator start() {
    return startEbeanDatabaseManager();
  }

  public void stopAll() {
    stopDocsConnectorService();
    stopPreviewService();
    stopStoragesService();
    stopUserManagement();
    stopServiceDiscover();
    stopDatabase();
    stopEbeanDatabaseManager();
    stopRabbitMq();
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

  public MockServerClient getUserManagementMock() {
    return userManagementMock;
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

  public RabbitMQContainer getMessageBrokerContainer() {
    return messageBrokerContainer;
  }

  public void resetDatabase() {
    ebeanDatabaseManager.getEbeanDatabase().find(Node.class).delete();
  }

  public void clearFileVersionCache() {
    injector.getInstance(CacheHandler.class).getFileVersionCache().flushAll();
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

  public static class SimulatorBuilder {

    private Simulator simulator;

    public static SimulatorBuilder aSimulator() {
      return new SimulatorBuilder();
    }

    public SimulatorBuilder init() {
      simulator = new Simulator();
      simulator.createInjector();
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
            simulator.getUser(cookie, userId);
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
      return simulator;
    }
  }
}
