// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.graphql.support;

import static org.assertj.core.api.Assertions.assertThat;

import graphql.language.Document;
import graphql.language.Field;
import graphql.language.FragmentDefinition;
import graphql.language.OperationDefinition;
import graphql.language.Selection;
import graphql.parser.Parser;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class NodeUserFieldSelectionTest {

  private static Set<String> siblingsOf(String query, String parentFieldName) {
    Document document = Parser.parse(query);
    OperationDefinition operation =
        document.getFirstDefinitionOfType(OperationDefinition.class).get();
    Map<String, FragmentDefinition> fragments =
        document.getDefinitionsOfType(FragmentDefinition.class).stream()
            .collect(Collectors.toMap(FragmentDefinition::getName, f -> f));
    Field parent = find(operation.getSelectionSet().getSelections(), parentFieldName);
    return NodeUserFieldSelection.of(List.of(parent), fragments);
  }

  private static Field find(Iterable<? extends Selection> selections, String name) {
    for (Selection<?> selection : selections) {
      if (selection instanceof Field field) {
        if (field.getName().equals(name)) {
          return field;
        }
        if (field.getSelectionSet() != null) {
          Field nested = find(field.getSelectionSet().getSelections(), name);
          if (nested != null) {
            return nested;
          }
        }
      }
    }
    return null;
  }

  @Test
  void directSiblingsAreCollected() {
    assertThat(siblingsOf("query { getNode(node_id: \"x\") { id owner { id } } }", "getNode"))
        .containsExactly("owner");
  }

  @Test
  void fieldsBehindNestedFragmentSpreadsAreCollected() {
    assertThat(
            siblingsOf(
                "query { getNode(node_id: \"x\") { ...A } }"
                    + " fragment A on Node { ...B } fragment B on Node { last_editor { id } }",
                "getNode"))
        .containsExactly("last_editor");
  }

  @Test
  void fieldsInInlineFragmentsAreCollected() {
    assertThat(
            siblingsOf(
                "query { getNode(node_id: \"x\") { ... on Folder { creator { id } } } }",
                "getNode"))
        .containsExactly("creator");
  }

  @Test
  void aliasesDoNotHideTheRealFieldName() {
    assertThat(siblingsOf("query { getNode(node_id: \"x\") { who: owner { id } } }", "getNode"))
        .containsExactly("owner");
  }

  @Test
  void subFieldsAreNotCrossed() {
    assertThat(
            siblingsOf(
                "query { getNode(node_id: \"x\") { owner { id } parent { last_editor { id } } } }",
                "getNode"))
        .containsExactly("owner");
  }

  @Test
  void siblingsOfANestedFieldIgnoreTheOuterLevel() {
    assertThat(
            siblingsOf(
                "query { getNode(node_id: \"x\") { last_editor { id } parent { owner { id } } } }",
                "parent"))
        .containsExactly("owner");
  }

  @Test
  void recursiveFragmentsTerminate() {
    assertThat(
            siblingsOf(
                "query { getNode(node_id: \"x\") { ...A } } fragment A on Node { owner { id }"
                    + " ...A }",
                "getNode"))
        .containsExactly("owner");
  }
}
