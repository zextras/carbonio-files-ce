// SPDX-FileCopyrightText: 2024 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.dal.repositories.impl.ebean.utilities;

import com.zextras.carbonio.files.Files.Db.Node;

public class NodeSQLCondition extends SQLCondition {

  public NodeSQLCondition(String field, SortOrder operator, Object parameter) {
    super(field, operator, parameter);
    setField(field);
 }

  @Override
  public void setField(String field) {
    if (!Node.ALLOWED_CONDITIONAL_COLUMN_NAMES.contains(field)) {
      throw new IllegalArgumentException("The " + field + " field is invalid");
    }

    if (Node.ID.equals(field)) {
      super.setField("t0." + field);
      return;
    }

    if (Node.NAME.equals(field)) {
      super.setField("LOWER(" + field + ")");
      return;
    }

    super.setField(field);
  }
}
