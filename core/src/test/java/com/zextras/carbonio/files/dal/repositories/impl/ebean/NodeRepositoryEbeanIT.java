// SPDX-FileCopyrightText: 2022 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.dal.repositories.impl.ebean;

import com.zextras.carbonio.files.config.FilesConfig;
import com.zextras.carbonio.files.dal.FilesPostgreSQLContainer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * This is only an example of test. It must be changed
 */
@Testcontainers
public class NodeRepositoryEbeanIT {

  private static FilesPostgreSQLContainer database;

  @BeforeAll
  public static void setup() {
    database = new FilesPostgreSQLContainer(new FilesConfig());
  }

  @BeforeEach
  public void setupEachTest() {
    database.start();
  }

  @Test
  public void test(){
    database.start();
    System.out.println(database.getExposedPorts() + " " +database.getPassword());
  }
}
