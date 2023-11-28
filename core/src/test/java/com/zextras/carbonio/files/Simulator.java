// SPDX-FileCopyrightText: 2023 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files;

import com.google.inject.Guice;
import com.google.inject.Injector;
import com.zextras.carbonio.files.Files.Config.Database;
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

@Testcontainers
public class Simulator implements AutoCloseable {

  private static final Logger logger = LoggerFactory.getLogger(Simulator.class);
  private Injector injector;
  private PostgreSQLContainer<?> postgreSQLContainer;
  private EbeanDatabaseManager ebeanDatabaseManager;
  private ClientAndServer clientAndServer;
  private MockServerClient serviceDiscoverMock;
  private MockServerClient userManagementMock;
  private MockServerClient storagesMock;

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

    final String encodedDbName = new String(Base64.encode(dbName.getBytes()));
    final String encodedDbUsername = new String(Base64.encode(dbUsername.getBytes()));
    final String encodedDbPassword = new String(Base64.encode(dbPassword.getBytes()));
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
        new UserInfo(new UserId(userId), "fake-email@example.com", "Fake User", "example.com");

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

  private void startMockServer() {
    if (clientAndServer == null) {
      final Properties properties = injector.getInstance(FilesConfig.class).getProperties();
      final int userManagementPort = Integer.parseInt(properties.getProperty(UserManagement.PORT));
      final int storagesPort = Integer.parseInt(properties.getProperty(Storages.PORT));
      clientAndServer =
          ClientAndServer.startClientAndServer(8500, userManagementPort, storagesPort);
    }
  }

  private void stopDatabase() {
    if (postgreSQLContainer != null && postgreSQLContainer.isRunning()) {
      postgreSQLContainer.stop();
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

  //
  // Public methods
  //

  public Simulator() {}

  public Simulator start() {
    return startEbeanDatabaseManager();
  }

  public void stopAll() {
    stopUserManagement();
    stopServiceDiscover();
    stopDatabase();
    stopEbeanDatabaseManager();
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

  public EmbeddedChannel getNettyChannel() {
    return new EmbeddedChannel(injector.getInstance(HttpRoutingHandler.class));
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

    public Simulator build() {
      return simulator;
    }
  }
}
