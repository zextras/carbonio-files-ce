// SPDX-FileCopyrightText: 2023 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.dal.repositories.impl.ebean.utilities;

import com.zextras.carbonio.files.Files;
import com.zextras.carbonio.files.dal.dao.ebean.Node;
import com.zextras.carbonio.files.dal.dao.ebean.NodeCategory;

import java.util.List;
import java.util.Optional;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import shaded_package.org.xmlunit.util.Nodes;

import static com.zextras.carbonio.files.dal.repositories.impl.ebean.NodeRepositoryEbean.getRealSortingsToApply;

class FindNodesKeySetBuilderTest {
  @Test
  void givenNodeAndOrderByNameGetKeyset() {
    // Given & When
    Node mockNode = Mockito.mock(Node.class);
    Mockito.when(mockNode.getSortingValueFromColumn(Files.Db.Node.CATEGORY)).thenReturn(NodeCategory.FILE.getValue());
    Mockito.when(mockNode.getId()).thenReturn("nodeId");
    Mockito.when(mockNode.getFullName()).thenReturn("NameFile.txt");
    List<NodeSort> realSortsToApply = getRealSortingsToApply(Optional.of(NodeSort.NAME_ASC));

    // Then
    String keyset =
        FindNodeKeySetBuilder.aSearchKeySetBuilder()
            .withNodeSorts(realSortsToApply)
            .fromNode(mockNode)
            .build();

    Assertions.assertThat(keyset)
        .isEqualTo(
            "node_category > 2 OR (node_category = 2 AND LOWER(name) > 'namefile.txt') OR (node_category = 2 AND LOWER(name) = 'namefile.txt' AND t0.node_id > 'nodeId')");
  }

  @Test
  void givenNodeAndOrderBySizeGetKeyset() {
    // Given & When
    Node mockNode = Mockito.mock(Node.class);
    Mockito.when(mockNode.getSortingValueFromColumn(Files.Db.Node.CATEGORY)).thenReturn(NodeCategory.FILE.getValue());
    Mockito.when(mockNode.getId()).thenReturn("nodeId");
    Mockito.when(mockNode.getSortingValueFromColumn(Files.Db.Node.SIZE)).thenReturn(1L);
    Mockito.when(mockNode.getFullName()).thenReturn("file.txt");
    List<NodeSort> realSortsToApply = getRealSortingsToApply(Optional.of(NodeSort.SIZE_ASC));

    // Then
    String keyset =
        FindNodeKeySetBuilder.aSearchKeySetBuilder()
            .withNodeSorts(realSortsToApply)
            .fromNode(mockNode)
            .build();

    Assertions.assertThat(keyset)
        .isEqualTo(
            "node_category > 2 OR (node_category = 2 AND size > 1) OR (node_category = 2 AND size = 1 AND LOWER(name) > 'file.txt') OR (node_category = 2 AND size = 1 AND LOWER(name) = 'file.txt' AND t0.node_id > 'nodeId')");
  }

  @Test
  void givenNodeAndOrderByCreatedAtGetKeyset() {
    // Given & When
    Node mockNode = Mockito.mock(Node.class);
    Mockito.when(mockNode.getSortingValueFromColumn(Files.Db.Node.CATEGORY)).thenReturn(NodeCategory.FILE.getValue());
    Mockito.when(mockNode.getId()).thenReturn("nodeId");
    Mockito.when(mockNode.getSortingValueFromColumn(NodeSort.CREATED_AT_ASC.getName()))
        .thenReturn(100L);
    List<NodeSort> realSortsToApply = getRealSortingsToApply(Optional.of(NodeSort.CREATED_AT_ASC));

    // Then
    String keyset =
        FindNodeKeySetBuilder.aSearchKeySetBuilder()
            .withNodeSorts(realSortsToApply)
            .fromNode(mockNode)
            .build();

    Assertions.assertThat(keyset)
        .isEqualTo(
            "node_category > 2 OR (node_category = 2 AND creation_timestamp > 100) OR (node_category = 2 AND creation_timestamp = 100 AND t0.node_id > 'nodeId')");
  }

  @Test
  void givenNodeAndOrderEmptyAtGetKeyset() {
    // Given & When
    Node mockNode = Mockito.mock(Node.class);
    Mockito.when(mockNode.getSortingValueFromColumn(Files.Db.Node.CATEGORY)).thenReturn(NodeCategory.FILE.getValue());
    Mockito.when(mockNode.getId()).thenReturn("nodeId");
    List<NodeSort> realSortsToApply = getRealSortingsToApply(Optional.empty());

    // Then
    String keyset =
        FindNodeKeySetBuilder.aSearchKeySetBuilder()
            .withNodeSorts(realSortsToApply)
            .fromNode(mockNode)
            .build();

    Assertions.assertThat(keyset)
        .isEqualTo("node_category > 2 OR (node_category = 2 AND t0.node_id > 'nodeId')");
  }
}
