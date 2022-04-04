// SPDX-FileCopyrightText: 2022 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files;

import com.google.inject.Guice;
import com.google.inject.Injector;
import com.zextras.carbonio.files.config.FilesConfig;
import com.zextras.carbonio.files.config.FilesModule;
import com.zextras.carbonio.files.dal.EbeanDatabaseManager;
import com.zextras.carbonio.files.tasks.PurgeService;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Boot {

  private static final Logger logger = LoggerFactory.getLogger(Boot.class);

  public static void main(String[] args) {
    new Boot().boot();
  }

  public void boot() {
    Injector injector = Guice.createInjector(new FilesModule());
    FilesConfig filesConfig = injector.getInstance(FilesConfig.class);
    EbeanDatabaseManager ebeanDatabaseManager = injector.getInstance(EbeanDatabaseManager.class);
    PurgeService purgeService = injector.getInstance(PurgeService.class);
    NettyServer nettyServer = injector.getInstance(NettyServer.class);

    try {
      filesConfig.loadConfig();
      ebeanDatabaseManager.start();
      purgeService.start();
      nettyServer.start();
    } catch (IOException exception) {
      logger.error("Fail to load the configuration");
    } catch (RuntimeException exception) {
      logger.error("Service stopped unexpectedly: " + exception.getMessage());
    } finally {
      ebeanDatabaseManager.stop();
      purgeService.stop();
    }
  }
}
