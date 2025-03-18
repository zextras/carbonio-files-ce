// SPDX-FileCopyrightText: 2022 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.config.impl;

import com.google.inject.Singleton;
import com.zextras.carbonio.files.Files;
import com.zextras.carbonio.files.Files.Config.Database;
import com.zextras.carbonio.files.Files.Config.DocsConnector;
import com.zextras.carbonio.files.Files.Config.Mailbox;
import com.zextras.carbonio.files.Files.Config.Preview;
import com.zextras.carbonio.files.Files.Config.Storages;
import com.zextras.carbonio.files.Files.Config.UserManagement;
import com.zextras.carbonio.files.Files.ServiceDiscover;
import com.zextras.carbonio.files.clients.ServiceDiscoverHttpClient;
import com.zextras.carbonio.files.config.FilesConfig;
import com.zextras.carbonio.files.exceptions.InvalidTokenSignException;
import com.zextras.carbonio.preview.PreviewClient;
import com.zextras.carbonio.usermanagement.UserManagementClient;
import com.zextras.filestore.api.Filestore;
import com.zextras.storages.api.StoragesClient;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
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
public class FilesConfigImpl implements FilesConfig {

  private static final Logger logger = LoggerFactory.getLogger(FilesConfigImpl.class);
  private final Properties properties;
  private String userManagementURL;
  private String fileStoreURL;
  private String previewURL;

  public FilesConfigImpl() {
    properties = new Properties();
    loadConfig();

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

  private String buildUrlFromProperties(String urlPropertyName, String portPropertyName, String defaultPort) {
    return String.format(
        "http://%s:%s",
        properties.getProperty(urlPropertyName, Files.Service.IP),
        properties.getProperty(portPropertyName, defaultPort));
  }

  public void loadConfig() {
    try {
      File configFile = new File("/etc/carbonio/files/config.properties");

      if (configFile.exists()) {
        try (InputStream inputStream = new FileInputStream(configFile)) {
          properties.load(inputStream);
        }
      } else {
        properties.load(
            getClass().getClassLoader().getResourceAsStream("carbonio-files.properties"));
      }
    } catch (IOException exception) {
      logger.error("Fail to load the configuration file");
    }

    userManagementURL = buildUrlFromProperties(UserManagement.URL, UserManagement.PORT, "20001");
    fileStoreURL = buildUrlFromProperties(Storages.URL, Storages.PORT, "20002") + "/";
    previewURL = buildUrlFromProperties(Preview.URL, Preview.PORT, "20003");
 }

  public Properties getProperties() {
    return properties;
  }

  public UserManagementClient getUserManagementClient() {
    return UserManagementClient.atURL(userManagementURL);
  }

  public Filestore getStoragesClient() {
    return StoragesClient.atUrl(fileStoreURL);
  }

  public PreviewClient getPreviewClient() {
    return PreviewClient.atURL(previewURL);
  }

  public int getMaxNumberOfFileVersion() {
    return Integer.parseInt(
        ServiceDiscoverHttpClient.defaultURL(ServiceDiscover.SERVICE_NAME)
            .getConfig(ServiceDiscover.Config.MAX_VERSIONS)
            .getOrElse(String.valueOf(ServiceDiscover.Config.DEFAULT_MAX_VERSIONS)));
  }

  public String getDatabaseUrl() {
    final String databaseHost =
        Optional.ofNullable(System.getProperty(Database.URL))
            .orElse(getProperties().getProperty(Database.URL, Files.Service.IP));

    final String databasePort =
        Optional.ofNullable(System.getProperty(Database.PORT))
            .orElse(getProperties().getProperty(Database.PORT, "20000"));

    return String.format("%s:%s", databaseHost, databasePort);
  }

  public String getMailboxUrl() {
    return buildUrlFromProperties(Mailbox.URL, Mailbox.PORT, "20004") + "/";
  }

  public String getDocsConnectorUrl() {
    return buildUrlFromProperties(DocsConnector.URL, DocsConnector.PORT, "20005");
  }

  public String getMessageBrokerUrl() {
    return Optional.ofNullable(System.getProperty(Files.Config.MessageBroker.URL))
        .orElse(properties.getProperty(Files.Config.MessageBroker.URL, "127.78.0.2"));
  }

  public Integer getMessageBrokerPort() {
    String messageBrokerPort = Optional.ofNullable(System.getProperty(Files.Config.MessageBroker.PORT))
        .orElse(properties.getProperty(Files.Config.MessageBroker.PORT, "20006"));
    return Integer.valueOf(messageBrokerPort);
  }

  public String getMessageBrokerPassword() {
    return ServiceDiscoverHttpClient.defaultURL(ServiceDiscover.MESSAGE_BROKER_SERVICE_NAME)
        .getConfig("default/password")
        .getOrElse(Files.MessageBroker.Config.DEFAULT_PASSWORD);
  }

  public String getMessageBrokerUsername() {
    return ServiceDiscoverHttpClient.defaultURL(ServiceDiscover.MESSAGE_BROKER_SERVICE_NAME)
        .getConfig("default/username")
        .getOrElse(Files.MessageBroker.Config.DEFAULT_USERNAME);
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
}
