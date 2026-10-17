// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.graphql.support;

import graphql.language.Field;
import graphql.language.FragmentDefinition;
import graphql.language.FragmentSpread;
import graphql.language.InlineFragment;
import graphql.language.OperationDefinition;
import graphql.language.Selection;
import graphql.language.SelectionSet;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public final class NodeUserFieldSelection {

  public static final String CREATOR = "creator";
  public static final String OWNER = "owner";
  public static final String LAST_EDITOR = "last_editor";

  private static final Set<String> USER_FIELDS = Set.of(CREATOR, OWNER, LAST_EDITOR);

  private NodeUserFieldSelection() {}

  public static Set<String> of(
      OperationDefinition operation, Map<String, FragmentDefinition> fragments) {
    Set<String> found = new HashSet<>();
    collect(operation.getSelectionSet(), fragments, new HashSet<>(), found);
    return found;
  }

  private static void collect(
      SelectionSet selectionSet,
      Map<String, FragmentDefinition> fragments,
      Set<String> visitedFragments,
      Set<String> found) {
    if (selectionSet == null) {
      return;
    }
    for (Selection<?> selection : selectionSet.getSelections()) {
      if (selection instanceof Field field) {
        if (USER_FIELDS.contains(field.getName())) {
          found.add(field.getName());
        }
        collect(field.getSelectionSet(), fragments, visitedFragments, found);
      } else if (selection instanceof InlineFragment inline) {
        collect(inline.getSelectionSet(), fragments, visitedFragments, found);
      } else if (selection instanceof FragmentSpread spread
          && visitedFragments.add(spread.getName())) {
        FragmentDefinition definition = fragments.get(spread.getName());
        if (definition != null) {
          collect(definition.getSelectionSet(), fragments, visitedFragments, found);
        }
      }
    }
  }
}
