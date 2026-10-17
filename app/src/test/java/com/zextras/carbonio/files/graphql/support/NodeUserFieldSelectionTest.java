// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.graphql.support;

import static org.assertj.core.api.Assertions.assertThat;

import graphql.language.Document;
import graphql.language.FragmentDefinition;
import graphql.language.OperationDefinition;
import graphql.parser.Parser;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class NodeUserFieldSelectionTest {

  private static Set<String> select(String query) {
    Document document = Parser.parse(query);
    OperationDefinition operation =
        document.getFirstDefinitionOfType(OperationDefinition.class).get();
    Map<String, FragmentDefinition> fragments =
        document.getDefinitionsOfType(FragmentDefinition.class).stream()
            .collect(Collectors.toMap(FragmentDefinition::getName, f -> f));
    return NodeUserFieldSelection.of(operation, fragments);
  }

  @Test
  void directFieldsAreCollected() {
    assertThat(select("query { getNode(node_id: \"x\") { id owner { id } } }"))
        .containsExactly("owner");
  }

  @Test
  void fieldsBehindNestedFragmentSpreadsAreCollected() {
    assertThat(
            select(
                "query { getNode(node_id: \"x\") { ...A } }"
                    + " fragment A on Node { ...B } fragment B on Node { last_editor { id } }"))
        .containsExactly("last_editor");
  }

  @Test
  void fieldsInInlineFragmentsAndNestedSelectionsAreCollected() {
    assertThat(
            select(
                "query { getNode(node_id: \"x\") { ... on Folder { children(limit: 1) { nodes {"
                    + " creator { id } } } } } }"))
        .containsExactly("creator");
  }

  @Test
  void recursiveFragmentsTerminate() {
    assertThat(
            select(
                "query { getNode(node_id: \"x\") { ...A } } fragment A on Node { owner { id }"
                    + " ...A }"))
        .containsExactly("owner");
  }

  @Test
  void unrelatedFieldsYieldAnEmptySet() {
    assertThat(select("query { getNode(node_id: \"x\") { id name } }")).isEmpty();
  }
}
