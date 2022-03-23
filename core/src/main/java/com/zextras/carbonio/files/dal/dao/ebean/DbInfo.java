// SPDX-FileCopyrightText: 2022 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.dal.dao.ebean;

import com.zextras.carbonio.files.Files;
import com.zextras.carbonio.files.Files.Db.Tables;
import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Table;

/**
 * Represents an Ebean {@link DbInfo} entity that matches a record of the {@link
 * Files.Db.Tables#DB_INFO} table.
 */
@Entity
@Table(name = Tables.DB_INFO)
public class DbInfo {

  @Column(name = "version", nullable = false)
  private int version;

  public int getVersion() {
    return version;
  }

  public void setVersion(int version) {
    this.version = version;
  }
}
