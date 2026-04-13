// SPDX-FileCopyrightText: 2022 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.dal.repositories.impl.ebean;

import com.zextras.carbonio.files.dal.FilesPostgreSQLContainer;
import com.zextras.carbonio.files.utilities.MockFilesConfig;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * This is only an example of test. It must be changed
 */
class NodeRepositoryEbeanIT {

  private static FilesPostgreSQLContainer database;

  @BeforeAll
  static void setup() {
    database = new FilesPostgreSQLContainer(new MockFilesConfig());
  }

  @BeforeEach
  void setupEachTest() {
    database.start();
  }

  @Test
  void test(){
    database.start();
    Assertions.assertEquals("password", database.getPassword());
    System.out.println(database.getExposedPorts() + " " +database.getPassword());
  }
}
