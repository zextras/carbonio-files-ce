// SPDX-FileCopyrightText: 2022 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.config;

import com.google.inject.Singleton;
import com.zextras.carbonio.files.Files;
import com.zextras.carbonio.files.Files.ServiceDiscover;
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
      ServiceDiscoverHttpClient client = ServiceDiscoverHttpClient.defaultURL(ServiceDiscover.SERVICE_NAME);
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

  public Properties getProperties() {
    return properties;
  }

  // URL Building Methods
  private String buildUrlFromProperties(String urlPropertyName, String portPropertyName, String defaultPort) {
    return String.format(
        "http://%s:%s",
        properties.getProperty(urlPropertyName, Constants.Config.Default.IP),
        properties.getProperty(portPropertyName, defaultPort));
  }

  public String getDatabaseUrl() {
    return properties.getProperty(
        Constants.Config.Properties.DATABASE_URL,
        Constants.Config.Default.DATABASE_URL);
  }

  public String getDatabasePort() {
    return properties.getProperty(
        Constants.Config.Properties.DATABASE_PORT,
        Constants.Config.Default.DATABASE_PORT);
  }

  public String getDatabaseName() {
    return ServiceDiscoverHttpClient.defaultURL(ServiceDiscover.SERVICE_NAME)
        .getConfig(ServiceDiscover.Config.DB_NAME)
        .getOrElse(Constants.Config.Default.DATABASE_NAME);
  }

  public String getDatabaseUsername() {
    return ServiceDiscoverHttpClient.defaultURL(ServiceDiscover.SERVICE_NAME)
        .getConfig(ServiceDiscover.Config.DB_USERNAME)
        .getOrElse(Constants.Config.Default.DATABASE_USERNAME);
  }

  public String getDatabasePassword() {
    return ServiceDiscoverHttpClient.defaultURL(ServiceDiscover.SERVICE_NAME)
        .getConfig(ServiceDiscover.Config.DB_PASSWORD)
        .getOrElse("");
  }

  public String getUserManagementUrl() {
    return buildUrlFromProperties(
        Constants.Config.Properties.USER_MANAGEMENT_URL,
        Constants.Config.Properties.USER_MANAGEMENT_PORT,
        Constants.Config.Default.USER_MANAGEMENT_PORT);
  }

  public String getStoragesUrl() {
    return buildUrlFromProperties(
        Constants.Config.Properties.STORAGES_URL,
        Constants.Config.Properties.STORAGES_PORT,
        Constants.Config.Default.STORAGES_PORT) + "/";
  }

  public String getPreviewUrl() {
    return buildUrlFromProperties(
        Constants.Config.Properties.PREVIEW_URL,
        Constants.Config.Properties.PREVIEW_PORT,
        Constants.Config.Default.PREVIEW_PORT);
  }

  public String getMailboxUrl() {
    return buildUrlFromProperties(
        Constants.Config.Properties.MAILBOX_URL,
        Constants.Config.Properties.MAILBOX_PORT,
        Constants.Config.Default.MAILBOX_PORT) + "/";
  }

  public String getDocsConnectorUrl() {
    return buildUrlFromProperties(
        Constants.Config.Properties.DOCS_CONNECTOR_URL,
        Constants.Config.Properties.DOCS_CONNECTOR_PORT,
        Constants.Config.Default.DOCS_CONNECTOR_PORT);
  }

  public String getMessageBrokerUrl() {
    return Optional.ofNullable(System.getProperty(Constants.Config.Properties.MESSAGE_BROKER_URL))
        .orElse(properties.getProperty(Constants.Config.Properties.MESSAGE_BROKER_URL, Constants.Config.Default.MESSAGE_BROKER_URL));
  }

  public Integer getMessageBrokerPort() {
    String messageBrokerPort = Optional.ofNullable(System.getProperty(Constants.Config.Properties.MESSAGE_BROKER_PORT))
        .orElse(properties.getProperty(Constants.Config.Properties.MESSAGE_BROKER_PORT, Constants.Config.Default.MESSAGE_BROKER_PORT));
    return Integer.valueOf(messageBrokerPort);
  }

  public String getMessageBrokerPassword() {
    return ServiceDiscoverHttpClient.defaultURL(ServiceDiscover.MESSAGE_BROKER_SERVICE_NAME)
        .getConfig("default/password")
        .getOrElse(Constants.Config.Default.MESSAGE_BROKER_PASSWORD);
  }

  public String getMessageBrokerUsername() {
    return ServiceDiscoverHttpClient.defaultURL(ServiceDiscover.MESSAGE_BROKER_SERVICE_NAME)
        .getConfig("default/username")
        .getOrElse(Constants.Config.Default.MESSAGE_BROKER_USERNAME);
  }

  // ================================================================================
  // Application Configuration Methods (non-connectivity related)
  // ================================================================================

  public int getMaxNumberOfFileVersion() {
    try {
      return Integer.parseInt(
          ServiceDiscoverHttpClient.defaultURL(ServiceDiscover.SERVICE_NAME)
              .getConfig(ServiceDiscover.Config.MAX_VERSIONS)
              .getOrElse(String.valueOf(ServiceDiscover.Config.DEFAULT_MAX_VERSIONS)));
    } catch (NumberFormatException e) {
      return ServiceDiscover.Config.DEFAULT_MAX_VERSIONS;
    }
  }

  public String getPageTokenSecretKey() {
    // The default secret key is obviously useless since it's public, but since the security implications are minimal
    // (only used for page token and already protected against attacks) I prefer to let the application run.
    return ServiceDiscoverHttpClient.defaultURL(ServiceDiscover.SERVICE_NAME)
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
        ServiceDiscoverHttpClient.defaultURL(ServiceDiscover.SERVICE_NAME)
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
    String systemValue = System.getProperty(Files.Config.Service.ENABLE_NOTIFICATIONS, "true");
    boolean systemEnabled = !systemValue.equalsIgnoreCase("false");

    String propValue = properties.getProperty(Files.Config.Service.ENABLE_NOTIFICATIONS, "true");
    boolean propEnabled = !propValue.equalsIgnoreCase("false");

    return systemEnabled && propEnabled;
  }
}