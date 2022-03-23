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

public class Boot {

  private Injector injector;

  public static void main(String[] args) throws Exception {
    new Boot().boot();
  }

  public void boot() throws InterruptedException, IOException {
    injector = Guice.createInjector(new FilesModule());
    injector.getInstance(FilesConfig.class)
      .loadConfig();
    injector.getInstance(EbeanDatabaseManager.class)
      .start();
    injector.getInstance(PurgeService.class)
      .start();
    injector.getInstance(NettyServer.class)
      .start();
  }
}
