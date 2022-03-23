// SPDX-FileCopyrightText: 2022 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.config;

import com.google.inject.Singleton;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.Properties;

@Singleton
public class FilesConfig {

  private final Properties properties;

  public FilesConfig() {
    properties = new Properties();
  }

  public void loadConfig() throws IOException {
    try {
      FileInputStream file = new FileInputStream(new File("/etc/carbonio/files/config.properties"));
      properties.load(file);
    } catch (FileNotFoundException e) {
      properties.load(getClass().getClassLoader().getResourceAsStream("carbonio-files.properties"));
    }
  }

  public Properties getProperties() {
    return properties;
  }
}
