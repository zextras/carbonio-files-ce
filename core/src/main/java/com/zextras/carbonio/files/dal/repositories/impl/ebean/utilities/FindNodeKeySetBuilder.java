// SPDX-FileCopyrightText: 2024 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.dal.repositories.impl.ebean.utilities;

import com.zextras.carbonio.files.Files;
import com.zextras.carbonio.files.dal.dao.ebean.Node;
import java.text.MessageFormat;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.*;

public class FindNodeKeySetBuilder {

  Optional<NodeSort> sort;
  Node node;

  public static FindNodeKeySetBuilder aSearchKeySetBuilder() {
    return new FindNodeKeySetBuilder();
  }

  public FindNodeKeySetBuilder withNodeSort(Optional<NodeSort> sort) {
    this.sort = sort;
    return this;
  }

  public FindNodeKeySetBuilder fromNode(Node node) {
    this.node = node;
    return this;
  }

  private static String orderToSymbol(SortOrder order) {
    switch (order) {
      case ASCENDING:
        return ">";
      case DESCENDING:
        return "<";
      default:
        return "=";
    }
  }

  // util to make build more readable
  private static CompareExpression compareExpression(String key, String symbol, String value) {
    return CompareExpression.aCompareExpression(key, symbol, value);
  }

  public String build() {

    if (this.sort == null) throw new IllegalArgumentException("Set sorting first");
    if (this.node == null) throw new IllegalArgumentException("Set node first");

    String nodeCategory = String.valueOf(node.getNodeCategory().getValue());
    String formattedIdToCompare = "'" + node.getId() + "'"; // add quotes

    AtomicReference<String> result = new AtomicReference<>();

    sort.ifPresentOrElse(
        s -> {
          String nameOfComparison = s.getName();
          String symbolOfComparison = orderToSymbol(s.getOrder());

          if (nameOfComparison.equals(Files.Db.Node.SIZE)) {
            // If sorting by size, get size from node and compose query
            // Compare first the name and then the id if size is equal to last node, this is needed
            // because findNodes by size also orders by name

            String formattedNameToCompare = "'" + node.getName() + "'"; // add quotes
            String valueOfComparison = node.getSize().toString();

            result.set(
                compareExpression(nameOfComparison, symbolOfComparison, valueOfComparison)
                    .or(
                        compareExpression(nameOfComparison, "=", valueOfComparison)
                            .and(compareExpression("t0.name", ">", formattedNameToCompare)))
                    .or(
                        compareExpression(nameOfComparison, "=", valueOfComparison)
                            .and(compareExpression("t0.node_id", ">", formattedIdToCompare)))
                    .toExpression());
          } else {
            // If sorting by any other value get that value and compose the query
            String formattedNameOfComparison;
            Object objOfComparison;

            if (nameOfComparison.equals(Files.Db.Node.NAME)) {
              // Special case: if sorting by name we actually want to compare lowercase names, so
              // get name and adapt query composition
              formattedNameOfComparison = MessageFormat.format("LOWER({0})", Files.Db.Node.NAME);
              objOfComparison = node.getFullName().toLowerCase();
            } else {
              // In any other case get value and name of comparison and compose query
              formattedNameOfComparison = nameOfComparison;
              objOfComparison = node.getSortingValueFromColumn(nameOfComparison);
            }

            String formattedValueOfComparison =
                objOfComparison instanceof String
                    ? "'" + objOfComparison + "'"
                    : objOfComparison.toString(); // add quotes if value of comparison is a string

            result.set(
                compareExpression(Files.Db.Node.CATEGORY, ">", nodeCategory)
                    .or(
                        compareExpression(Files.Db.Node.CATEGORY, "=", nodeCategory)
                            .and(
                                compareExpression(
                                    formattedNameOfComparison,
                                    symbolOfComparison,
                                    formattedValueOfComparison)))
                    .or(
                        compareExpression(Files.Db.Node.CATEGORY, "=", nodeCategory)
                            .and(
                                compareExpression(
                                    formattedNameOfComparison,
                                    symbolOfComparison,
                                    formattedValueOfComparison))
                            .and(compareExpression("t0.node_id", ">", formattedIdToCompare)))
                    .toExpression());
          }
        },
        () -> {
          // If no sort is set, apply category and id by default
          result.set(
              compareExpression(Files.Db.Node.CATEGORY, ">", nodeCategory)
                  .or(
                      compareExpression(Files.Db.Node.CATEGORY, "=", nodeCategory)
                          .and(compareExpression("t0.node_id", ">", formattedIdToCompare)))
                  .toExpression());
        });
    return result.get();
  }
}
