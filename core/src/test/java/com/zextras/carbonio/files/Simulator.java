// SPDX-FileCopyrightText: 2023 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files;

import com.google.inject.Guice;
import com.google.inject.Injector;
import com.zextras.carbonio.files.Files.Config.Database;
import com.zextras.carbonio.files.Files.Config.DocsConnector;
import com.zextras.carbonio.files.Files.Config.Preview;
import com.zextras.carbonio.files.Files.Config.Storages;
import com.zextras.carbonio.files.Files.Config.UserManagement;
import com.zextras.carbonio.files.Files.ServiceDiscover.Config.Db;
import com.zextras.carbonio.files.config.FilesConfig;
import com.zextras.carbonio.files.config.FilesModule;
import com.zextras.carbonio.files.dal.EbeanDatabaseManager;
import com.zextras.carbonio.files.dal.dao.ebean.Node;
import com.zextras.carbonio.files.netty.HttpRoutingHandler;
import com.zextras.carbonio.usermanagement.entities.UserId;
import com.zextras.carbonio.usermanagement.entities.UserInfo;
import com.zextras.carbonio.usermanagement.enumerations.UserStatus;
import com.zextras.carbonio.usermanagement.enumerations.UserType;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.HttpMethod;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Properties;
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
import org.testcontainers.utility.DockerImageName;

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
    injector = Guice.createInjector(new FilesModule(new FilesConfig()));
    return this;
  }

  private Simulator startDatabase() {
    if (postgreSQLContainer == null) {
      postgreSQLContainer = new PostgreSQLContainer<>("postgres:12.14");
    }

    postgreSQLContainer.start();

    // Set the System.properties for the dynamic database url and port
    System.setProperty(Database.URL, postgreSQLContainer.getHost());
    System.setProperty(Database.PORT, String.valueOf(postgreSQLContainer.getFirstMappedPort()));

    return this;
  }

  private Simulator startMessageBroker() {
    if (messageBrokerContainer == null) {
      messageBrokerContainer = new RabbitMQContainer("rabbitmq:3.7.25-management-alpine");
    }
    messageBrokerContainer.start();

    // Set the System.properties for the dynamic rabbit url and port
    System.setProperty(Files.Config.MessageBroker.URL, messageBrokerContainer.getHost());
    System.setProperty(Files.Config.MessageBroker.PORT, String.valueOf(messageBrokerContainer.getFirstMappedPort()));

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

      dbName = Db.NAME;
      dbUsername = Db.USERNAME;
      dbPassword = Db.PASSWORD;
    }

    if (messageBrokerContainer != null && messageBrokerContainer.isRunning()) {
      adminUsername = messageBrokerContainer.getAdminUsername();
      adminPassword = messageBrokerContainer.getAdminPassword();
    } else {
      logger.warn("The ServiceDiscover will be mocked without a rabbitMQ container");

      adminUsername = Files.MessageBroker.Config.DEFAULT_USERNAME;
      adminPassword = Files.MessageBroker.Config.DEFAULT_PASSWORD;
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
            filesConfig.getProperties().getProperty(UserManagement.URL),
            Integer.parseInt(filesConfig.getProperties().getProperty(UserManagement.PORT)));

    return this;
  }

  private void validateUser(String cookie, String userId) {
    userManagementMock
        .when(
            HttpRequest.request()
                .withMethod(HttpMethod.GET.toString())
                .withPath("/auth/token/" + cookie))
        .respond(
            HttpResponse.response()
                .withStatusCode(200)
                .withBody("{\"userId\":\"" + userId + "\"}"));
  }

  private void getUser(String cookie, String userId) {
    final UserInfo userInfo =
        new UserInfo(
            new UserId(userId),
            "fake-email@example.com",
            "Fake User",
            "example.com",
            UserStatus.ACTIVE,
            UserType.INTERNAL);

    userManagementMock
        .when(
            HttpRequest.request()
                .withMethod(HttpMethod.GET.toString())
                .withPath("/users/id/" + userId)
                .withCookie(Cookie.cookie("ZM_AUTH_TOKEN", cookie)))
        .respond(HttpResponse.response().withStatusCode(200).withBody(JsonBody.json(userInfo)));
  }

  private Simulator startStorages() {
    startMockServer();

    final FilesConfig filesConfig = injector.getInstance(FilesConfig.class);
    storagesMock =
        new MockServerClient(
            filesConfig.getProperties().getProperty(Storages.URL),
            Integer.parseInt(filesConfig.getProperties().getProperty(Storages.PORT)));

    return this;
  }

  private Simulator startPreviewService() {
    startMockServer();

    final FilesConfig filesConfig = injector.getInstance(FilesConfig.class);
    previewServiceMock = new MockServerClient(
      filesConfig.getProperties().getProperty(Preview.URL),
      Integer.parseInt(filesConfig.getProperties().getProperty(Preview.PORT))
    );

    return this;
  }

  private Simulator startDocsConnectorService() {
    startMockServer();

    final FilesConfig filesConfig = injector.getInstance(FilesConfig.class);
    docsConnectorServiceMock = new MockServerClient(
      filesConfig.getProperties().getProperty(DocsConnector.URL),
      Integer.parseInt(filesConfig.getProperties().getProperty(DocsConnector.PORT))
    );

    return this;
  }

  private void startMockServer() {
    if (clientAndServer == null) {
      final Properties properties = injector.getInstance(FilesConfig.class).getProperties();
      final int userManagementPort = Integer.parseInt(properties.getProperty(UserManagement.PORT));
      final int storagesPort = Integer.parseInt(properties.getProperty(Storages.PORT));
      final int previewServicePort = Integer.parseInt(properties.getProperty(Preview.PORT));
      final int docsConnectorServicePort = Integer.parseInt(properties.getProperty(DocsConnector.PORT));

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
            simulator.validateUser(cookie, userId);
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
