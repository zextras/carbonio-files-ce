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
import ch.qos.logback.classic.Logger;
import org.apache.commons.logging.Log;
import org.slf4j.LoggerFactory;
import ch.qos.logback.classic.Level;

public class Boot {

  private static final Logger logger = (Logger) LoggerFactory.getLogger("Files");

  public static void main(String[] args) {
    new Boot().boot();
  }

  public void boot() {
    // Set configuration level
    String logLevel = System.getenv("FILES_LOG_LEVEL");
    /*
    logger.setLevel(
      Level.toLevel(
        logLevel == null
          ? "warn"
          : logLevel
      )
    );
     */

    logger.setLevel(Level.ERROR);
    logger.error("=====================START BOOT=====================");
    logger.debug("DEBUG");
    logger.info("INFO");
    logger.warn("WARN");
    logger.error("ERROR");
    logger.error("=====================END BOOT=====================");

    Injector injector = Guice.createInjector(new FilesModule());

    injector.getInstance(FilesConfig.class);

    EbeanDatabaseManager ebeanDatabaseManager = injector.getInstance(EbeanDatabaseManager.class);
    ebeanDatabaseManager.start();

    PurgeService purgeService = injector.getInstance(PurgeService.class);
    NettyServer nettyServer = injector.getInstance(NettyServer.class);

    try {
      purgeService.start();
      nettyServer.start();
    } catch (RuntimeException exception) {
      logger.error("Service stopped unexpectedly: " + exception.getMessage());
    } finally {
      ebeanDatabaseManager.stop();
      purgeService.stop();
    }
  }
}
