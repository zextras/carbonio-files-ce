// SPDX-FileCopyrightText: 2022 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import com.google.inject.Guice;
import com.google.inject.Injector;
import com.zextras.carbonio.files.config.FilesConfig;
import com.zextras.carbonio.files.config.FilesModule;
import com.zextras.carbonio.files.dal.DatabaseManager;
import com.zextras.carbonio.files.message_broker.interfaces.MessageBrokerManager;
import com.zextras.carbonio.files.tasks.PurgeService;
import org.slf4j.LoggerFactory;

public class Boot {

  private static final Logger rootLogger = (Logger) LoggerFactory.getLogger("ROOT");
  /*
  The <root> element configures the root logger.
   It supports a single attribute, namely the level attribute.
    It does not allow any other attributes because
     the additivity flag does not apply to the root logger.
   */
  private static final Logger logger = (Logger) LoggerFactory.getLogger(Boot.class);

  private DatabaseManager databaseManager;
  private PurgeService purgeService;
  private NettyServer nettyServer;
  private MessageBrokerManager messageBrokerManager;

  public static void main(String[] args) {
    new Boot().boot();
  }

  public void boot() {
    // Set configuration level
    String logLevel = System.getProperty("FILES_LOG_LEVEL");
    rootLogger.setLevel(Level.toLevel(logLevel == null ? "warn" : logLevel));

    Injector injector = Guice.createInjector(new FilesModule());
    injector.getInstance(FilesConfig.class);

    try {
      databaseManager = injector.getInstance(DatabaseManager.class);
      databaseManager.initialize();

      purgeService = injector.getInstance(PurgeService.class);
      purgeService.start();

      messageBrokerManager = injector.getInstance(MessageBrokerManager.class);
      messageBrokerManager.startAllConsumers();

      nettyServer = injector.getInstance(NettyServer.class);
      nettyServer.start();
    } catch (RuntimeException exception) {
      logger.error("Service stopped unexpectedly: ", exception);
      throw exception;
    } finally {
      databaseManager.stop();
      purgeService.stop();
      messageBrokerManager.close();
    }
  }
}
