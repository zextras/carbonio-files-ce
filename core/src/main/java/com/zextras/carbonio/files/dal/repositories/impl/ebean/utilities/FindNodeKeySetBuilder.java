// SPDX-FileCopyrightText: 2024 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.dal.repositories.impl.ebean.utilities;

import com.zextras.carbonio.files.Files;
import com.zextras.carbonio.files.dal.dao.ebean.Node;
import java.util.ArrayList;
import java.util.List;

public class FindNodeKeySetBuilder {

  List<NodeSort> sorts;
  Node node;

  public static FindNodeKeySetBuilder aSearchKeySetBuilder() {
    return new FindNodeKeySetBuilder();
  }

  public FindNodeKeySetBuilder withNodeSorts(List<NodeSort> sorts) {
    if (sorts.isEmpty()) {
      throw new IllegalArgumentException("A default sort option should always be present");
    }
    this.sorts = sorts;
    return this;
  }

  public FindNodeKeySetBuilder fromNode(Node node) {
    this.node = node;
    return this;
  }

  private NodeSQLCondition createSQLCondition(NodeSort sort, SortOrder order) {
    String columnNameToCompare = sort.getName();
    // Special case: if sorting by name -> compare the lowercase of the fullName
    Object parameterToCompare = Files.Db.Node.NAME.equals(columnNameToCompare)
      ? node.getFullName().toLowerCase()
      : node.getSortingValueFromColumn(columnNameToCompare);

    return new NodeSQLCondition(columnNameToCompare, order, parameterToCompare);
  }

  /**
   * This essentially concatenates conditions to put in the WHERE of the find nodes query. The key
   * set is constructed following the order of the sorting to apply while querying. For example,
   * given the last page's node and using sort by category and then by size, the key set returned
   * will be CATEGORY > lastNodeCategory OR (CATEGORY = lastNodeCategory AND SIZE > lastNodeSize).
   * This builder works with N sorts, following the pattern A>B OR (A=B AND B>C) OR (A=B AND B=C AND
   * C>D) and so on.
   */
  public SQLExpression build() {

    if (this.sorts == null || this.sorts.isEmpty()) {
      throw new IllegalArgumentException("Set sorting first");
    }

    if (this.node == null) {
      throw new IllegalArgumentException("Set node first");
    }

    List<SQLPart> orConditions = new ArrayList<>();

    for (NodeSort s : sorts) {
      List<SQLPart> andConditions = new ArrayList<>();

      for (NodeSort t : sorts.subList(0, sorts.indexOf(s))) {
        andConditions.add(createSQLCondition(t, SortOrder.EQUAL));
      }

      andConditions.add(createSQLCondition(s, s.getOrder()));
      orConditions.add(SQLExpression.and(andConditions));
    }

    return SQLExpression.or(orConditions);
  }
}
