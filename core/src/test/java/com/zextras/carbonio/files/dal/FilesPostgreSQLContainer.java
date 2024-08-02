// SPDX-FileCopyrightText: 2022 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.dal;

import com.zextras.carbonio.files.config.interfaces.FilesConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

public class FilesPostgreSQLContainer extends PostgreSQLContainer<FilesPostgreSQLContainer> {

  private final static Logger logger = LoggerFactory.getLogger(FilesPostgreSQLContainer.class);

  private final static DockerImageName POSTGRESQL_IMAGE_NAME = DockerImageName
    .parse(PostgreSQLContainer.IMAGE)
    .withTag(PostgreSQLContainer.DEFAULT_TAG);

  private final FilesConfig filesConfig;

  public FilesPostgreSQLContainer(FilesConfig filesConfig) {
    super(POSTGRESQL_IMAGE_NAME);

    this.filesConfig = filesConfig;
  }

  @Override
  public void start() {
    try (FilesPostgreSQLContainer databaseContainer = self()) {
      // Avoid hardcoded values. Must be replaced after the refactor of FilesConfig
      databaseContainer
        .withDatabaseName("carbonio-files-db")
        .withUsername("carbonio-files-db")
        .withPassword("password");
    }
  }

  @Override
  public void stop() {}
}
