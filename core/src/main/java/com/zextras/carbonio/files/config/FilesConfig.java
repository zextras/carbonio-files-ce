// SPDX-FileCopyrightText: 2022 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.config;

import com.google.inject.Singleton;
import com.zextras.carbonio.files.Files;
import com.zextras.carbonio.files.Files.Config.Database;
import com.zextras.carbonio.files.Files.Config.DocsConnector;
import com.zextras.carbonio.files.Files.Config.Mailbox;
import com.zextras.carbonio.files.Files.ServiceDiscover;
import com.zextras.carbonio.files.clients.ServiceDiscoverHttpClient;
import com.zextras.carbonio.preview.PreviewClient;
import com.zextras.carbonio.usermanagement.UserManagementClient;
import com.zextras.filestore.api.Filestore;
import com.zextras.storages.api.StoragesClient;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.text.MessageFormat;
import java.util.Optional;
import java.util.Properties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Singleton
public class FilesConfig {

  private static final Logger logger = LoggerFactory.getLogger(FilesConfig.class);
  private final Properties properties;
  private String userManagementURL;
  private String fileStoreURL;
  private String previewURL;

  public FilesConfig() {
    properties = new Properties();
    loadConfig();
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

    userManagementURL =
        MessageFormat.format(
            "http://{0}:{1}",
            properties.getProperty(Files.Config.UserManagement.URL, Files.Service.IP),
            properties.getProperty(Files.Config.UserManagement.PORT, "20001"));

    fileStoreURL =
        MessageFormat.format(
            "http://{0}:{1}/",
            properties.getProperty(Files.Config.Storages.URL, Files.Service.IP),
            properties.getProperty(Files.Config.Storages.PORT, "20002"));

    previewURL =
        MessageFormat.format(
            "http://{0}:{1}",
            properties.getProperty(Files.Config.Preview.URL, Files.Service.IP),
            properties.getProperty(Files.Config.Preview.PORT, "20003"));
  }

  public Properties getProperties() {
    return properties;
  }

  public UserManagementClient getUserManagementClient() {
    return UserManagementClient.atURL(userManagementURL);
  }

  public Filestore getFileStoreClient() {
    return StoragesClient.atUrl(fileStoreURL);
  }

  public String getFileStoreUrl() {
    return String.format(
        "http://%s:%s/",
        properties.getProperty(Files.Config.Storages.URL, Files.Service.IP),
        properties.getProperty(Files.Config.Storages.PORT, "20002"));
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
    return String.format(
        "http://%s:%s/",
        properties.getProperty(Mailbox.URL, Files.Service.IP),
        properties.getProperty(Mailbox.PORT, "20004"));
  }

  public String getDocsConnectorUrl() {
    return String.format(
        "http://%s:%s/",
        properties.getProperty(DocsConnector.URL, Files.Service.IP),
        properties.getProperty(DocsConnector.PORT, "20005"));
  }
}
