// SPDX-FileCopyrightText: 2023 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.dal;

import com.zextras.carbonio.files.dal.dao.ebean.CollaborationLink;
import com.zextras.carbonio.files.dal.dao.ebean.DbInfo;
import com.zextras.carbonio.files.dal.dao.ebean.FileVersion;
import com.zextras.carbonio.files.dal.dao.ebean.FileVersionPK;
import com.zextras.carbonio.files.dal.dao.ebean.Link;
import com.zextras.carbonio.files.dal.dao.ebean.Node;
import com.zextras.carbonio.files.dal.dao.ebean.NodeCustomAttributes;
import com.zextras.carbonio.files.dal.dao.ebean.NodeCustomAttributesPK;
import com.zextras.carbonio.files.dal.dao.ebean.Share;
import com.zextras.carbonio.files.dal.dao.ebean.SharePK;
import com.zextras.carbonio.files.dal.dao.ebean.Tombstone;
import com.zextras.carbonio.files.dal.dao.ebean.TombstonePK;
import com.zextras.carbonio.files.dal.dao.ebean.TrashedNode;
import io.ebean.Database;
import io.ebean.DatabaseFactory;
import io.ebean.config.DatabaseConfig;
import io.ebean.datasource.DataSourceConfig;
import java.io.Closeable;
import java.util.ArrayList;
import java.util.List;

/**
 * Utility to unit test DAO entities that extends {@link io.ebean.Model} class. To properly test
 * this type of classes it is necessary to have a database and a connection up & running. This class
 * fires up an HSQLDB database initialized with a tailored sql schema almost identical to the sql
 * schemas used in the production environment.
 */
public final class EbeanWithInMemoryDatabase implements Closeable {

  private final Database database;

  public EbeanWithInMemoryDatabase() {
    final List<Class<?>> entities = new ArrayList<>();
    entities.add(DbInfo.class);
    entities.add(Node.class);
    entities.add(NodeCustomAttributesPK.class);
    entities.add(NodeCustomAttributes.class);
    entities.add(FileVersionPK.class);
    entities.add(FileVersion.class);
    entities.add(SharePK.class);
    entities.add(Share.class);
    entities.add(Link.class);
    entities.add(CollaborationLink.class);
    entities.add(TombstonePK.class);
    entities.add(Tombstone.class);
    entities.add(TrashedNode.class);

    // Properties to connect to an in memory hsqldb database
    final DataSourceConfig dataSourceConfig = new DataSourceConfig();
    dataSourceConfig.setUrl("jdbc:hsqldb:mem:test;hsqldb.log_data=false");
    dataSourceConfig.setUsername("test");
    dataSourceConfig.setPassword("test");
    dataSourceConfig.setDriver("org.hsqldb.jdbcDriver");

    final DatabaseConfig config = new DatabaseConfig();
    config.setDataSourceConfig(dataSourceConfig);
    config.addAll(entities);
    database = DatabaseFactory.create(config);

    // Initialize the database
    database.script().run(getClass().getClassLoader().getResource("sql/postgresql.sql"));
  }

  /**
   * @return the {@link Database} instance necessary to make sql queries.
   */
  public final Database getDatabase() {
    return database;
  }

  @Override
  public void close() {
    database.shutdown();
  }
}
