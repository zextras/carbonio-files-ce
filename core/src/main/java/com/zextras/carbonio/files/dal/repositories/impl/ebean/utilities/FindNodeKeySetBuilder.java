// SPDX-FileCopyrightText: 2024 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.dal.repositories.impl.ebean.utilities;

import com.zextras.carbonio.files.Files;
import com.zextras.carbonio.files.dal.dao.ebean.Node;
import java.text.MessageFormat;
import java.util.List;

public class FindNodeKeySetBuilder {

  List<NodeSort> sorts;
  Node node;

  public static FindNodeKeySetBuilder aSearchKeySetBuilder() {
    return new FindNodeKeySetBuilder();
  }

  public FindNodeKeySetBuilder withNodeSorts(List<NodeSort> sorts) {
    if (sorts.isEmpty())
      throw new IllegalArgumentException("A default sort option should always be present");
    this.sorts = sorts;
    return this;
  }

  public FindNodeKeySetBuilder fromNode(Node node) {
    this.node = node;
    return this;
  }

  private String formatObjectSql(Object obj) {
    return obj instanceof String ? "'" + obj.toString() + "'" : String.valueOf(obj);
  }

  private CompareExpression buildSortingExpression(NodeSort sort, SortOrder order) {
    String nameToCompare = sort.getName();
    String compareSymbol = order.getSymbol();
    Object objToCompare;

    if (Files.Db.Node.NAME.equals(nameToCompare)) {
      // Special case: if sorting by name we actually want to compare lowercase names, so
      // get name and adapt query composition
      nameToCompare = MessageFormat.format("LOWER({0})", Files.Db.Node.NAME);
      objToCompare = node.getFullName().toLowerCase();
    } else if (Files.Db.Node.ID.equals(nameToCompare)) {
      nameToCompare = "t0.node_id"; // since findnodes makes a join node_id would be ambiguous
      objToCompare = node.getId();
    } else {
      objToCompare = node.getSortingValueFromColumn(nameToCompare);
    }
    String valueToCompare = formatObjectSql(objToCompare);
    return CompareExpression.aCompareExpression(nameToCompare, compareSymbol, valueToCompare);
  }

  /**
   * This essentially concatenates conditions to put in the WHERE of the find nodes query. The
   * keyset is constructed following the order of the sortings to apply while querying. For example,
   * given the last page's node and using sort by category and then by size, the keyset returned
   * will be CATEGORY > lastNodeCategory OR (CATEGORY = lastNodeCategory AND SIZE > lastNodeSize).
   * This builder works with N sorts, following the pattern A>B OR (A=B AND B>C) OR (A=B AND B=C AND
   * C>D) and so on.
   */
  public String build() {

    if (this.sorts == null || this.sorts.isEmpty())
      throw new IllegalArgumentException("Set sorting first");
    if (this.node == null) throw new IllegalArgumentException("Set node first");

    NodeSort firstSort = sorts.get(0); // there always is a first sort
    CompareExpression keysetExpression = buildSortingExpression(firstSort, firstSort.getOrder());

    for (NodeSort s : sorts.subList(1, sorts.size())) {
      NodeSort temp = sorts.get(0);
      CompareExpression tempEx = buildSortingExpression(temp, SortOrder.EQUAL);
      for (NodeSort t : sorts.subList(1, sorts.indexOf(s))) {
        tempEx.and(buildSortingExpression(t, SortOrder.EQUAL));
      }
      tempEx.and(
          buildSortingExpression(
              sorts.get(sorts.indexOf(s)), sorts.get(sorts.indexOf(s)).getOrder()));
      keysetExpression.or(tempEx.encapsulate());
    }

    return keysetExpression.toExpression();
  }
}
