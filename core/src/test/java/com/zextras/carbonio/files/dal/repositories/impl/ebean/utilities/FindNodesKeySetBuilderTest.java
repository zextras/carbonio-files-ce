// SPDX-FileCopyrightText: 2023 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.dal.repositories.impl.ebean.utilities;

import com.zextras.carbonio.files.dal.dao.ebean.Node;
import com.zextras.carbonio.files.dal.dao.ebean.NodeCategory;
import java.util.Optional;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class FindNodesKeySetBuilderTest {
  @Test
  void givenNodeAndOrderByNameGetKeyset() {
    // Given & When
    Node mockNode = Mockito.mock(Node.class);
    Mockito.when(mockNode.getNodeCategory()).thenReturn(NodeCategory.FILE);
    Mockito.when(mockNode.getId()).thenReturn("nodeId");
    Mockito.when(mockNode.getFullName()).thenReturn("NameFile.txt");

    // Then
    String keyset =
        FindNodeKeySetBuilder.aSearchKeySetBuilder()
            .withNodeSort(Optional.of(NodeSort.NAME_ASC))
            .fromNode(mockNode)
            .build();

    Assertions.assertThat(keyset)
        .isEqualTo(
            "(node_category > 2 OR (node_category = 2 AND LOWER(name) > 'namefile.txt') OR"
                + " (node_category = 2 AND LOWER(name) > 'namefile.txt' AND t0.node_id >"
                + " 'nodeId'))");
  }

  @Test
  void givenNodeAndOrderBySizeGetKeyset() {
    // Given & When
    Node mockNode = Mockito.mock(Node.class);
    Mockito.when(mockNode.getNodeCategory()).thenReturn(NodeCategory.FILE);
    Mockito.when(mockNode.getId()).thenReturn("nodeId");
    Mockito.when(mockNode.getSize()).thenReturn(1L);

    // Then
    String keyset =
        FindNodeKeySetBuilder.aSearchKeySetBuilder()
            .withNodeSort(Optional.of(NodeSort.SIZE_ASC))
            .fromNode(mockNode)
            .build();

    Assertions.assertThat(keyset).isEqualTo("(size > 1 OR (size = 1 AND t0.node_id > 'nodeId'))");
  }

  @Test
  void givenNodeAndOrderByCreatedAtGetKeyset() {
    // Given & When
    Node mockNode = Mockito.mock(Node.class);
    Mockito.when(mockNode.getNodeCategory()).thenReturn(NodeCategory.FILE);
    Mockito.when(mockNode.getId()).thenReturn("nodeId");
    Mockito.when(mockNode.getSortingValueFromColumn(NodeSort.CREATED_AT_ASC.getName()))
        .thenReturn(100L);

    // Then
    String keyset =
        FindNodeKeySetBuilder.aSearchKeySetBuilder()
            .withNodeSort(Optional.of(NodeSort.CREATED_AT_ASC))
            .fromNode(mockNode)
            .build();

    Assertions.assertThat(keyset)
        .isEqualTo(
            "(node_category > 2 OR (node_category = 2 AND creation_timestamp > 100) OR"
                + " (node_category = 2 AND creation_timestamp > 100 AND t0.node_id > 'nodeId'))");
  }

  @Test
  void givenNodeAndOrderEmptyAtGetKeyset() {
    // Given & When
    Node mockNode = Mockito.mock(Node.class);
    Mockito.when(mockNode.getNodeCategory()).thenReturn(NodeCategory.FILE);
    Mockito.when(mockNode.getId()).thenReturn("nodeId");

    // Then
    String keyset =
        FindNodeKeySetBuilder.aSearchKeySetBuilder()
            .withNodeSort(Optional.empty())
            .fromNode(mockNode)
            .build();

    Assertions.assertThat(keyset)
        .isEqualTo("(node_category > 2 OR (node_category = 2 AND t0.node_id > 'nodeId'))");
  }
}
