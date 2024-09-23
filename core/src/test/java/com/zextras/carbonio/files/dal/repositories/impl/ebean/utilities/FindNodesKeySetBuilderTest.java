// SPDX-FileCopyrightText: 2024 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.dal.repositories.impl.ebean.utilities;

import static com.zextras.carbonio.files.dal.repositories.impl.ebean.NodeRepositoryEbean.getRealSortingsToApply;

import com.zextras.carbonio.files.Files;
import com.zextras.carbonio.files.dal.dao.ebean.Node;
import com.zextras.carbonio.files.dal.dao.ebean.NodeCategory;
import java.util.List;
import java.util.Optional;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class FindNodesKeySetBuilderTest {

  @Test
  void givenNodeAndOrderByNameTheBuildShouldReturnAValidSqlExpression() {
    // Given
    Node mockNode = Mockito.mock(Node.class);
    Mockito.when(mockNode.getSortingValueFromColumn(Files.Db.Node.CATEGORY))
      .thenReturn(NodeCategory.FILE.getValue());
    Mockito.when(mockNode.getSortingValueFromColumn(Files.Db.Node.ID)).thenReturn("nodeId");
    Mockito.when(mockNode.getFullName()).thenReturn("NameFile.txt");
    List<NodeSort> realSortsToApply = getRealSortingsToApply(Optional.of(NodeSort.NAME_ASC));

    // When
    SQLExpression keySet =
      FindNodeKeySetBuilder.aSearchKeySetBuilder()
        .withNodeSorts(realSortsToApply)
        .fromNode(mockNode)
        .build();

    // Then
    Assertions.assertThat(keySet.toExpression())
      .isEqualTo(
        "((node_category > ?) OR (node_category = ? AND LOWER(name) > ?) OR"
          + " (node_category = ? AND LOWER(name) = ? AND t0.node_id > ?))");
    Assertions
      .assertThat(keySet.getParameters())
      .containsExactly((short) 2, (short) 2, "namefile.txt", (short) 2, "namefile.txt", "nodeId");
  }

  @Test
  void givenNodeAndOrderBySizeTheBuildShouldReturnAValidSqlExpression() {
    // Given
    Node mockNode = Mockito.mock(Node.class);
    Mockito.when(mockNode.getSortingValueFromColumn(Files.Db.Node.CATEGORY))
      .thenReturn(NodeCategory.FILE.getValue());
    Mockito.when(mockNode.getSortingValueFromColumn(Files.Db.Node.SIZE)).thenReturn(1L);
    Mockito.when(mockNode.getSortingValueFromColumn(Files.Db.Node.ID)).thenReturn("nodeId");
    Mockito.when(mockNode.getFullName()).thenReturn("file.txt");
    List<NodeSort> realSortsToApply = getRealSortingsToApply(Optional.of(NodeSort.SIZE_ASC));

    // When
    SQLExpression keySet =
      FindNodeKeySetBuilder.aSearchKeySetBuilder()
        .withNodeSorts(realSortsToApply)
        .fromNode(mockNode)
        .build();

    // Then
    Assertions
      .assertThat(keySet.toExpression())
      .isEqualTo(
        "((node_category > ?) OR (node_category = ? AND size > ?) OR (node_category = ? AND size ="
          + " ? AND LOWER(name) > ?) OR (node_category = ? AND size = ? AND LOWER(name) = ? AND"
          + " t0.node_id > ?))");

    Assertions
      .assertThat(keySet.getParameters())
      .containsExactly(
        (short) 2,
        (short) 2,
        1L,
        (short) 2,
        1L,
        "file.txt",
        (short) 2,
        1L,
        "file.txt",
        "nodeId");
  }

  @Test
  void givenNodeAndOrderByCreatedAtTheBuildShouldReturnAValidSqlExpression() {
    // Given
    Node mockNode = Mockito.mock(Node.class);
    Mockito.when(mockNode.getSortingValueFromColumn(Files.Db.Node.CATEGORY))
      .thenReturn(NodeCategory.FILE.getValue());
    Mockito.when(mockNode.getSortingValueFromColumn(Files.Db.Node.CREATED_AT)).thenReturn(100L);
    Mockito.when(mockNode.getSortingValueFromColumn(Files.Db.Node.ID)).thenReturn("nodeId");
    List<NodeSort> realSortsToApply = getRealSortingsToApply(Optional.of(NodeSort.CREATED_AT_ASC));

    // When
    SQLExpression keySet =
      FindNodeKeySetBuilder.aSearchKeySetBuilder()
        .withNodeSorts(realSortsToApply)
        .fromNode(mockNode)
        .build();

    // Then
    Assertions.assertThat(keySet.toExpression())
      .isEqualTo(
        "((node_category > ?) OR (node_category = ? AND creation_timestamp > ?) OR"
          + " (node_category = ? AND creation_timestamp = ? AND t0.node_id > ?))");

    Assertions
      .assertThat(keySet.getParameters())
      .containsExactly((short) 2, (short) 2, 100L, (short) 2, 100L, "nodeId");
  }

  @Test
  void givenNodeAndOrderEmptyAtTheBuildShouldReturnAValidSqlExpression() {
    // Given
    Node mockNode = Mockito.mock(Node.class);
    Mockito.when(mockNode.getSortingValueFromColumn(Files.Db.Node.CATEGORY))
      .thenReturn(NodeCategory.FILE.getValue());
    Mockito.when(mockNode.getSortingValueFromColumn(Files.Db.Node.ID)).thenReturn("nodeId");
    List<NodeSort> realSortsToApply = getRealSortingsToApply(Optional.empty());

    // When
    SQLExpression keySet =
      FindNodeKeySetBuilder.aSearchKeySetBuilder()
        .withNodeSorts(realSortsToApply)
        .fromNode(mockNode)
        .build();

    // Then
    Assertions.assertThat(keySet.toExpression())
      .isEqualTo("((node_category > ?) OR (node_category = ? AND t0.node_id > ?))");

    Assertions.assertThat(keySet.getParameters()).containsExactly((short) 2, (short) 2, "nodeId");
  }
}
