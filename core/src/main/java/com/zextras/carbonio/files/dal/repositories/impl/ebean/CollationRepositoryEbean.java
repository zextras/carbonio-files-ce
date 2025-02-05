// SPDX-FileCopyrightText: 2022 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.dal.repositories.impl.ebean;

import com.google.inject.Inject;
import com.zextras.carbonio.files.Files;
import com.zextras.carbonio.files.config.FilesConfig;
import com.zextras.carbonio.files.dal.EbeanDatabaseManager;
import com.zextras.carbonio.files.dal.repositories.interfaces.CollationRepository;
import io.ebean.SqlQuery;
import io.ebean.SqlRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CollationRepositoryEbean implements CollationRepository {

  private static final Logger logger = LoggerFactory.getLogger(CollationRepositoryEbean.class);
  private final EbeanDatabaseManager mDB;
  private final FilesConfig filesConfig;

  @Inject
  public CollationRepositoryEbean(EbeanDatabaseManager ebeanDatabaseManager, FilesConfig filesConfig) {
    this.mDB = ebeanDatabaseManager;
    this.filesConfig = filesConfig;
  }

  @Override
  public String getValidCollation() {
    String adminDefinedCollation = filesConfig.getCollation();
    String validCollation;
    if (isCollationValid(adminDefinedCollation)) {
      validCollation = adminDefinedCollation;
    } else if (isCollationValid(Files.ServiceDiscover.Config.FALLBACK_COLLATION)) {
      validCollation = Files.ServiceDiscover.Config.FALLBACK_COLLATION;
    } else {
      validCollation = Files.ServiceDiscover.Config.DEFAULT_COLLATION;
    }
    logger.info("Using collation: {}", validCollation);
    return "\"" + validCollation + "\"";
  }

  private boolean isCollationValid(String collation) {
    String sql = "SELECT COUNT(*) AS count FROM pg_collation WHERE collname = :collation";
    SqlQuery query = mDB.getEbeanDatabase().sqlQuery(sql);
    query.setParameter("collation", collation);
    SqlRow row = query.findOne();
    return row != null && row.getInteger("count") > 0;
  }
}
