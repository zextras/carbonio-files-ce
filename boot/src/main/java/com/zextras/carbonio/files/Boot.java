// SPDX-FileCopyrightText: 2022 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files;

import com.google.common.util.concurrent.ServiceManager;
import com.google.inject.Guice;
import com.google.inject.Injector;
import com.google.inject.Key;
import com.google.inject.TypeLiteral;
import com.zextras.carbonio.files.config.FilesConfig;
import com.zextras.carbonio.files.config.FilesModule;
import com.zextras.carbonio.files.dal.EbeanDatabaseManager;
import com.zextras.carbonio.files.graphql.subscriptions.AbstractEventPublisher;
import com.zextras.carbonio.files.graphql.subscriptions.FolderContentUpdatedPublisher;
import com.zextras.carbonio.files.graphql.types.NodeEvent;
import com.zextras.carbonio.files.tasks.PurgeService;
import ch.qos.logback.classic.Logger;
import java.util.Collections;
import org.slf4j.LoggerFactory;
import ch.qos.logback.classic.Level;

public class Boot {

  private static final Logger rootLogger = (Logger) LoggerFactory.getLogger("ROOT");
  /*
  The <root> element configures the root logger.
   It supports a single attribute, namely the level attribute.
    It does not allow any other attributes because
     the additivity flag does not apply to the root logger.
   */
  private static final Logger logger     = (Logger) LoggerFactory.getLogger(Boot.class);

  private EbeanDatabaseManager ebeanDatabaseManager;
  private ServiceManager       serviceManager;
  private PurgeService         purgeService;
  private NettyServer          nettyServer;

  public static void main(String[] args) {
    new Boot().boot();
  }

  public void boot() {
    // Set configuration level
    String logLevel = System.getProperty("FILES_LOG_LEVEL");
    rootLogger.setLevel(
      Level.toLevel(
        logLevel == null
          ? "warn"
          : logLevel
      )
    );

    Injector injector = Guice.createInjector(new FilesModule());
    injector.getInstance(FilesConfig.class);

    try {
      serviceManager = new ServiceManager(
        Collections.singletonList(injector.getInstance(FolderContentUpdatedPublisher.class))
      );
      serviceManager.startAsync();

      ebeanDatabaseManager = injector.getInstance(EbeanDatabaseManager.class);
      ebeanDatabaseManager.start();

      purgeService = injector.getInstance(PurgeService.class);
      purgeService.start();

      nettyServer = injector.getInstance(NettyServer.class);
      nettyServer.start();
    } catch (RuntimeException exception) {
      logger.error("Service stopped unexpectedly: " + exception.getMessage());
    } finally {
      serviceManager.stopAsync().awaitStopped();
      ebeanDatabaseManager.stop();
      purgeService.stop();
    }
  }
}
