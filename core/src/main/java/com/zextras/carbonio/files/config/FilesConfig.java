// SPDX-FileCopyrightText: 2022 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.config;

import com.google.inject.Singleton;
import com.zextras.carbonio.files.Files;
import com.zextras.carbonio.preview.PreviewClient;
import com.zextras.carbonio.usermanagement.UserManagementClient;
import com.zextras.filestore.api.Filestore;
import com.zextras.storages.api.StoragesClient;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.text.MessageFormat;
import java.util.Properties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Singleton
public class FilesConfig {

  private static Logger logger = LoggerFactory.getLogger(FilesConfig.class);

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
      properties.load(
        configFile.exists()
          ? new FileInputStream(configFile)
          : getClass().getClassLoader().getResourceAsStream("carbonio-files.properties")
      );

    } catch (IOException exception) {
      logger.error("Fail to load the configuration file");
    }

    userManagementURL = MessageFormat.format(
      "http://{0}:{1}",
      properties.getProperty(Files.Config.UserManagement.URL, "127.78.0.2"),
      properties.getProperty(Files.Config.UserManagement.PORT, "20001")
    );

    fileStoreURL = MessageFormat.format(
      "http://{0}:{1}/",
      properties.getProperty(Files.Config.Storages.URL, "127.78.0.2"),
      properties.getProperty(Files.Config.Storages.PORT, "20002")
    );

    previewURL = MessageFormat.format(
      "http://{0}:{1}",
      properties.getProperty(Files.Config.Preview.URL, "127.78.0.2"),
      properties.getProperty(Files.Config.Preview.PORT, "20003")
    );
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

  public PreviewClient getPreviewClient() {
    return PreviewClient.atURL(previewURL);
  }
}
