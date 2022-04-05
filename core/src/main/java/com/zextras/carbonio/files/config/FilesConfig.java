// SPDX-FileCopyrightText: 2022 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.config;

import com.google.inject.Singleton;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.Properties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Singleton
public class FilesConfig {

  private static Logger logger = LoggerFactory.getLogger(FilesConfig.class);

  private final Properties properties;

  public FilesConfig() {
    properties = new Properties();
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
  }

  public Properties getProperties() {
    return properties;
  }
}
