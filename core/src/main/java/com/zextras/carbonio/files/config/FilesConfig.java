// SPDX-FileCopyrightText: 2022 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.config;

import com.google.inject.Singleton;
import com.zextras.carbonio.files.Constants;
import com.zextras.carbonio.files.Constants.ServiceDiscover;
import com.zextras.carbonio.files.clients.ServiceDiscoverHttpClient;
import com.zextras.carbonio.files.exceptions.InvalidTokenSignException;

import java.io.*;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.Optional;
import java.util.Properties;

import io.vavr.control.Try;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;

@Singleton
public class FilesConfig {

  private static final Logger logger = LoggerFactory.getLogger(FilesConfig.class);
  private final Properties properties;

  public FilesConfig() {
    properties = new Properties();
  }

  // Load config from files or system properties.
  public void loadConfig() throws IOException {
    loadFromEtc() // the official way
        .ifPresent(config -> {
          try {
            properties.load(config);
          } catch (IOException e) {
            logger.warn("Error loading configuration file: {}", e.getMessage());
          }
        });

    properties.putAll(System.getProperties()); // the dev way, overriding existing properties

    // Initialize secret key creation in constructor logic
    initializeSecretKey();
  }

  private Optional<InputStream> loadFromEtc() {
    return loadFile("/etc/carbonio/files/config.properties");
  }

  private Optional<InputStream> loadFile(String path) {
    try {
      return Optional.of(new FileInputStream(path));
    } catch (FileNotFoundException e) {
      return Optional.empty();
    }
  }

  private void initializeSecretKey() {
    // Wrap this in a try catch because we don't want to stop the application if the secret key doesn't get created.
    // This is because the page token is already secured against attacks and the only thing one could do with a
    // not signed token is getting data of already public folders knowing their id and link id; so
    // I prefer to let the application run even if the secret key is not created and use a default useless key to sign
    // the page token.
    try {
      logger.debug("Creating secret key");
      // Create secret key if not exists
      ServiceDiscoverHttpClient client = getServiceDiscoverFilesClient();
      final String configKey = ServiceDiscover.Config.PAGE_TOKEN_SECRET_KEY;

      // Get existing key
      Try<String> existingKey = client.getConfig(configKey);
      if (existingKey.isFailure()) {
        // If key doesn't exist, create a new one
        String newSecretKey = generateHmacSha256Key();
        Try<Boolean> result = client.createConfig(configKey, newSecretKey);
        if (result.isSuccess()) {
          if (!result.get()) {
            logger.debug("Secret key already exists");
          } else {
            logger.debug("Secret key created");
          }
        } else {
          logger.error("Failed to create secret key");
        }
      }
    } catch (Exception e) {
      logger.error("Exception while trying to create secret key", e);
    }
  }

  public String getDatabaseHost() {
    return properties.getProperty(
        Constants.Config.Database.HOST_PROPERTY,
        Constants.Config.Database.DEFAULT_HOST);
  }

  public String getDatabasePort() {
    return properties.getProperty(
        Constants.Config.Database.PORT_PROPERTY,
        Constants.Config.Database.DEFAULT_PORT);
  }

  public String getDatabaseName() {
    return getServiceDiscoverFilesClient()
        .getConfig(ServiceDiscover.Config.Key.DB_NAME)
        .getOrElse(Constants.Config.Database.DEFAULT_NAME);
  }

  public String getDatabaseUsername() {
    return getServiceDiscoverFilesClient()
        .getConfig(ServiceDiscover.Config.Key.DB_USERNAME)
        .getOrElse(Constants.Config.Database.DEFAULT_USERNAME);
  }

  public String getDatabasePassword() {
    return getServiceDiscoverFilesClient()
        .getConfig(ServiceDiscover.Config.Key.DB_PASSWORD)
        .getOrElse("");
  }

  public String getFilesHost() {
    return properties.getProperty(
        Constants.Files.HOST_PROPERTY,
        Constants.Files.DEFAULT_HOST);
  }

  public String getFilesPort() {
    return properties.getProperty(
        Constants.Files.PORT_PROPERTY,
        String.valueOf(Constants.Files.DEFAULT_PORT));
  }

  public String getUserManagementHost() {
    return properties.getProperty(
        Constants.Config.UserManagement.HOST_PROPERTY,
        Constants.Config.UserManagement.DEFAULT_HOST);
  }

  public String getUserManagementPort() {
    return properties.getProperty(
        Constants.Config.UserManagement.PORT_PROPERTY,
        String.valueOf(Constants.Config.UserManagement.DEFAULT_PORT));
  }

  public String getStoragesHost() {
    return properties.getProperty(
        Constants.Config.Storages.HOST_PROPERTY,
        Constants.Config.Storages.DEFAULT_HOST);
  }

  public String getStoragesPort() {
    return properties.getProperty(
        Constants.Config.Storages.PORT_PROPERTY,
        String.valueOf(Constants.Config.Storages.DEFAULT_PORT));
  }

  public String getPreviewHost() {
    return properties.getProperty(
        Constants.Config.Preview.HOST_PROPERTY,
        Constants.Config.Preview.DEFAULT_HOST);
  }

  public String getPreviewPort() {
    return properties.getProperty(
        Constants.Config.Preview.PORT_PROPERTY,
        String.valueOf(Constants.Config.Preview.DEFAULT_PORT));
  }

  public String getMailboxHost() {
    return properties.getProperty(
        Constants.Config.Mailbox.HOST_PROPERTY,
        Constants.Config.Mailbox.DEFAULT_HOST);
  }

  public String getMailboxPort() {
    return properties.getProperty(
        Constants.Config.Mailbox.PORT_PROPERTY,
        String.valueOf(Constants.Config.Mailbox.DEFAULT_PORT));
  }

  public String getDocsConnectorHost() {
    return properties.getProperty(
        Constants.Config.DocsConnector.HOST_PROPERTY,
        Constants.Config.DocsConnector.DEFAULT_HOST);
  }

  public String getDocsConnectorPort() {
    return properties.getProperty(
        Constants.Config.DocsConnector.PORT_PROPERTY,
        String.valueOf(Constants.Config.DocsConnector.DEFAULT_PORT));
  }

  public String getMessageBrokerHost() {
    return Optional.ofNullable(System.getProperty(Constants.Config.MessageBroker.HOST_PROPERTY))
        .orElse(properties.getProperty(Constants.Config.MessageBroker.HOST_PROPERTY, Constants.Config.MessageBroker.DEFAULT_HOST));
  }

  public Integer getMessageBrokerPort() {
    String messageBrokerPort = Optional.ofNullable(System.getProperty(Constants.Config.MessageBroker.PORT_PROPERTY))
        .orElse(properties.getProperty(Constants.Config.MessageBroker.PORT_PROPERTY, String.valueOf(Constants.Config.MessageBroker.DEFAULT_PORT)));
    return Integer.valueOf(messageBrokerPort);
  }

  public String getMessageBrokerPassword() {
    return getServiceDiscoverBrokerClient()
        .getConfig("default/password")
        .getOrElse(Constants.MessageBroker.Config.DEFAULT_PASSWORD);
  }

  public String getMessageBrokerUsername() {
    return getServiceDiscoverBrokerClient()
        .getConfig("default/username")
        .getOrElse(Constants.MessageBroker.Config.DEFAULT_USERNAME);
  }

  public int getHikariMaxPoolSize() {
    return getServiceDiscoverFilesClient()
        .getConfig(ServiceDiscover.Config.Key.HIKARI_MAX_POOL_SIZE)
        .map(Integer::parseInt)
        .getOrElse(Constants.Config.Hikari.MAX_POOL_SIZE);
  }

  public int getHikariMinIdleConnections() {
    int maxPoolSize = getHikariMaxPoolSize();
    return getServiceDiscoverFilesClient()
        .getConfig(ServiceDiscover.Config.Key.HIKARI_MIN_IDLE_CONNECTIONS)
        .map(Integer::parseInt)
        .map(minIdleConnections -> Math.min(minIdleConnections, maxPoolSize))
        .getOrElse(Constants.Config.Hikari.MIN_IDLE_CONNECTIONS);
  }

  public int getHikariIdleTimeout() {
    return getServiceDiscoverFilesClient()
      .getConfig(ServiceDiscover.Config.Key.HIKARI_IDLE_TIMEOUT)
      .map(Integer::parseInt)
      .getOrElse(Constants.Config.Hikari.IDLE_TIMEOUT);
  }

  public int getHikariLeakDetectionThreshold() {
    return getServiceDiscoverFilesClient()
      .getConfig(ServiceDiscover.Config.Key.HIKARI_LEAK_DETECTION_THRESHOLD)
      .map(Integer::parseInt)
      .getOrElse(Constants.Config.Hikari.LEAK_DETECTION_THRESHOLD);
  }

  public int getHikariMaxLifetime() {
    return getServiceDiscoverFilesClient()
      .getConfig(ServiceDiscover.Config.Key.HIKARI_MAX_LIFETIME)
      .map(Integer::parseInt)
      .getOrElse(Constants.Config.Hikari.MAX_LIFETIME);
  }

  public String getServiceDiscoverEndpoint() {
    return "http://" + properties.getProperty(
        ServiceDiscover.HOST_PROPERTY,
        ServiceDiscover.DEFAULT_HOST) + ":" + properties.getProperty(ServiceDiscover.PORT_PROPERTY,
        String.valueOf(ServiceDiscover.DEFAULT_PORT));
  }

  // ================================================================================
  // Application Configuration Methods (non-connectivity related)
  // ================================================================================

  public int getMaxNumberOfFileVersion() {
    try {
      return Integer.parseInt(
          getServiceDiscoverFilesClient()
              .getConfig(ServiceDiscover.Config.MAX_VERSIONS)
              .getOrElse(String.valueOf(ServiceDiscover.Config.DEFAULT_MAX_VERSIONS)));
    } catch (NumberFormatException e) {
      return ServiceDiscover.Config.DEFAULT_MAX_VERSIONS;
    }
  }

  private ServiceDiscoverHttpClient getServiceDiscoverFilesClient() {
    return ServiceDiscoverHttpClient.atURL(this.getServiceDiscoverEndpoint(), ServiceDiscover.SERVICE_NAME);
  }

  private ServiceDiscoverHttpClient getServiceDiscoverBrokerClient() {
    return ServiceDiscoverHttpClient.atURL(this.getServiceDiscoverEndpoint(), ServiceDiscover.MESSAGE_BROKER_SERVICE_NAME);
  }

  public String getPageTokenSecretKey() {
    // The default secret key is obviously useless since it's public, but since the security implications are minimal
    // (only used for page token and already protected against attacks) I prefer to let the application run.
    return getServiceDiscoverFilesClient()
        .getConfig(ServiceDiscover.Config.PAGE_TOKEN_SECRET_KEY)
        .getOrElse(ServiceDiscover.Config.DEFAULT_PAGE_TOKEN_SECRET_KEY);
  }

  private String generateHmacSha256Key() {
    try {
      KeyGenerator keyGen = KeyGenerator.getInstance("HmacSHA256");
      SecretKey secretKey = keyGen.generateKey();
      return Base64.getEncoder().encodeToString(secretKey.getEncoded());
    } catch (NoSuchAlgorithmException e) {
      throw new InvalidTokenSignException("Failed to generate HMAC key");
    }
  }

  // Returns the maximum uploadable file size in MB or optional.empty if not found or malformed
  public Optional<Integer> getMaxUploadableFileSizeInMb() {
    return Optional.ofNullable(
        getServiceDiscoverFilesClient()
            .getConfig(ServiceDiscover.Config.MAX_UPLOADABLE_SIZE_IN_MB)
            .getOrElse((String) null)
    )
    .flatMap(s -> {
      try {
        return Optional.of(Integer.parseInt(s));
      } catch (NumberFormatException e) {
        return Optional.empty();
      }
    });
  }

  // Returns true as default since the notifications are a required feature, but opens the way to disable them if
  // needed in the future
  public boolean areNotificationsEnabled() {
    return Boolean.parseBoolean(
        properties.getProperty(
            Constants.Files.ENABLE_NOTIFICATIONS_PROPERTY, String.valueOf(Constants.Files.DEFAULT_ENABLE_NOTIFICATIONS)));
  }

  public Optional<Integer> getMaxDownloadableFileSizeInMb() {
    return Optional.ofNullable(
        getServiceDiscoverFilesClient()
            .getConfig(ServiceDiscover.Config.MAX_DOWNLOADABLE_SIZE_IN_MB)
            .getOrElse((String) null)
    )
    .flatMap(s -> {
      try {
        return Optional.of(Integer.parseInt(s));
      } catch (NumberFormatException e) {
        return Optional.empty();
      }
    });
  }
}