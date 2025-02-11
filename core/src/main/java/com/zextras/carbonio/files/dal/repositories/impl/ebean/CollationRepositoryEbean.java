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

import java.util.Optional;

public class CollationRepositoryEbean implements CollationRepository {

  private static final Logger logger = LoggerFactory.getLogger(CollationRepositoryEbean.class);
  private final EbeanDatabaseManager mDB;
  private final FilesConfig filesConfig;
  private static Optional<String> cachedCollate = null;

  @Inject
  public CollationRepositoryEbean(EbeanDatabaseManager ebeanDatabaseManager, FilesConfig filesConfig) {
    this.mDB = ebeanDatabaseManager;
    this.filesConfig = filesConfig;
  }

  /**
   * Get the valid collation to use in the queries.
   * If default collate is C then use the fallback collation if installed.
   * If default collate is not C or if the fallback collation is not installed then return empty.
   * May be expanded to use the collation defined by the admin in the future.
   *
   * @return the valid collation
   */
  @Override
  public Optional<String> getValidCollate() {
    // Keep this commented because now we want to use the fallback collate if the machine uses C by default
    // but in the future we may want to use the collation defined by the admin
    /*String adminDefinedCollation = filesConfig.getCollation();
    if (isCollationValid(adminDefinedCollation)) {
      validCollation = adminDefinedCollation;
    } else */

    // No need to call every time the database since the default database collation is not going to change,
    // so we can cache the result
    if (cachedCollate != null) {
      logger.info("Using cached collation: {}", cachedCollate.orElse("System default"));
      return cachedCollate;
    }

    Optional<String> defaultCollate = getDefaultCollate();
    // Set collate to the fallback collation if the default collate is C or C.utf8.
    // If the fallback collation is not valid, or we can't get the default one set collate to empty
    logger.info("Default collation: {}", defaultCollate.orElse("Not found"));
    if (
        defaultCollate.isPresent() &&
        (defaultCollate.get().equals("C") || defaultCollate.get().equals("C.utf8")) &&
        isCollationValid(Files.ServiceDiscover.Config.FALLBACK_COLLATE)
    ) {
      cachedCollate = Optional.of(Files.ServiceDiscover.Config.FALLBACK_COLLATE);
      logger.info("Setting collation to {}", Files.ServiceDiscover.Config.FALLBACK_COLLATE);
      return cachedCollate;
    }

    cachedCollate = Optional.empty();
    logger.info("Setting collation to System default");
    return cachedCollate;
  }

  private Optional<String> getDefaultCollate() {
    String datname = Files.ServiceDiscover.Config.Db.DEFAULT_NAME;
    String sql = "SELECT datcollate FROM pg_database WHERE datname = :datname";
    SqlQuery query = mDB.getEbeanDatabase().sqlQuery(sql);
    query.setParameter("datname", datname);
    SqlRow row = query.findOne();
    if (row == null){
      logger.error("Failed to get the default collation for the database {}", datname);
      return Optional.empty();
    } else {
      return Optional.ofNullable(row.getString("datcollate"));
    }
  }

  private boolean isCollationValid(String collation) {
    String sql = "SELECT COUNT(*) AS count FROM pg_collation WHERE collname = :collation";
    SqlQuery query = mDB.getEbeanDatabase().sqlQuery(sql);
    query.setParameter("collation", collation);
    SqlRow row = query.findOne();
    return row != null && row.getInteger("count") > 0;
  }
}
