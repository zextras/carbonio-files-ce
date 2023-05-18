// SPDX-FileCopyrightText: 2023 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.dal.repositories.impl.ebean.utilities;

import com.zextras.carbonio.files.dal.dao.ebean.Node;
import io.ebean.OrderBy;
import io.ebean.Query;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class NodeSortTest {

  private Query<Node> queryNodeMock;
  private OrderBy<Node> orderByNodeMock;

  @BeforeEach
  // It is necessary since we are mocking and casting a generic class
  @SuppressWarnings("unchecked")
  void setUp() {
    queryNodeMock = (Query<Node>) Mockito.mock(Query.class);
    orderByNodeMock = (OrderBy<Node>) Mockito.mock(OrderBy.class);
    Mockito.when(queryNodeMock.order()).thenReturn(orderByNodeMock);
  }

  @Test
  void givenALastEditorAscNodeSortTheGetOrderEbeanQueryShouldApplyTheOrderByCorrectly() {
    // Given && When
    NodeSort.LAST_EDITOR_ASC.getOrderEbeanQuery(queryNodeMock);

    // Then
    Mockito.verify(queryNodeMock, Mockito.times(1)).order();
    Mockito.verify(orderByNodeMock, Mockito.times(1)).asc("t0.editor_id");

    Mockito.verifyNoMoreInteractions(queryNodeMock);
    Mockito.verifyNoMoreInteractions(orderByNodeMock);
  }

  @Test
  void givenALastEditorDescNodeSortTheGetOrderEbeanQueryShouldApplyTheOrderByCorrectly() {
    // Given && When
    NodeSort.LAST_EDITOR_DESC.getOrderEbeanQuery(queryNodeMock);

    // Then
    Mockito.verify(queryNodeMock, Mockito.times(1)).order();
    Mockito.verify(orderByNodeMock, Mockito.times(1)).desc("t0.editor_id");

    Mockito.verifyNoMoreInteractions(queryNodeMock);
    Mockito.verifyNoMoreInteractions(orderByNodeMock);
  }

  @Test
  void givenANameAscNodeSortTheGetOrderEbeanQueryShouldApplyTheOrderByCorrectly() {
    // Given && When
    NodeSort.NAME_ASC.getOrderEbeanQuery(queryNodeMock);

    // Then
    Mockito.verify(queryNodeMock, Mockito.times(1)).order();
    Mockito.verify(orderByNodeMock, Mockito.times(1)).asc("t0.name");

    Mockito.verifyNoMoreInteractions(queryNodeMock);
    Mockito.verifyNoMoreInteractions(orderByNodeMock);
  }

  @Test
  void givenANameDescNodeSortTheGetOrderEbeanQueryShouldApplyTheOrderByCorrectly() {
    // Given && When
    NodeSort.NAME_DESC.getOrderEbeanQuery(queryNodeMock);

    // Then
    Mockito.verify(queryNodeMock, Mockito.times(1)).order();
    Mockito.verify(orderByNodeMock, Mockito.times(1)).desc("t0.name");

    Mockito.verifyNoMoreInteractions(queryNodeMock);
    Mockito.verifyNoMoreInteractions(orderByNodeMock);
  }

  @Test
  void givenAnOwnerAscNodeSortTheGetOrderEbeanQueryShouldApplyTheOrderByCorrectly() {
    // Given && When
    NodeSort.OWNER_ASC.getOrderEbeanQuery(queryNodeMock);

    // Then
    Mockito.verify(queryNodeMock, Mockito.times(1)).order();
    Mockito.verify(orderByNodeMock, Mockito.times(1)).asc("t0.owner_id");

    Mockito.verifyNoMoreInteractions(queryNodeMock);
    Mockito.verifyNoMoreInteractions(orderByNodeMock);
  }

  @Test
  void givenAnOwnerDescNodeSortTheGetOrderEbeanQueryShouldApplyTheOrderByCorrectly() {
    // Given && When
    NodeSort.OWNER_DESC.getOrderEbeanQuery(queryNodeMock);

    // Then
    Mockito.verify(queryNodeMock, Mockito.times(1)).order();
    Mockito.verify(orderByNodeMock, Mockito.times(1)).desc("t0.owner_id");

    Mockito.verifyNoMoreInteractions(queryNodeMock);
    Mockito.verifyNoMoreInteractions(orderByNodeMock);
  }

  @Test
  void givenATypeAscNodeSortTheGetOrderEbeanQueryShouldApplyTheOrderByCorrectly() {
    // Given && When
    NodeSort.TYPE_ASC.getOrderEbeanQuery(queryNodeMock);

    // Then
    Mockito.verify(queryNodeMock, Mockito.times(1)).order();
    Mockito.verify(orderByNodeMock, Mockito.times(1)).asc("t0.node_category");

    Mockito.verifyNoMoreInteractions(queryNodeMock);
    Mockito.verifyNoMoreInteractions(orderByNodeMock);
  }

  @Test
  void givenATypeDescNodeSortTheGetOrderEbeanQueryShouldApplyTheOrderByCorrectly() {
    // Given && When
    NodeSort.TYPE_DESC.getOrderEbeanQuery(queryNodeMock);

    // Then
    Mockito.verify(queryNodeMock, Mockito.times(1)).order();
    Mockito.verify(orderByNodeMock, Mockito.times(1)).desc("t0.node_category");

    Mockito.verifyNoMoreInteractions(queryNodeMock);
    Mockito.verifyNoMoreInteractions(orderByNodeMock);
  }

  @Test
  void givenAnUpdatedAtAscNodeSortTheGetOrderEbeanQueryShouldApplyTheOrderByCorrectly() {
    // Given && When
    NodeSort.UPDATED_AT_ASC.getOrderEbeanQuery(queryNodeMock);

    // Then
    Mockito.verify(queryNodeMock, Mockito.times(1)).order();
    Mockito.verify(orderByNodeMock, Mockito.times(1)).asc("t0.updated_timestamp");

    Mockito.verifyNoMoreInteractions(queryNodeMock);
    Mockito.verifyNoMoreInteractions(orderByNodeMock);
  }

  @Test
  void givenAnUpdatedAtDescNodeSortTheGetOrderEbeanQueryShouldApplyTheOrderByCorrectly() {
    // Given && When
    NodeSort.UPDATED_AT_DESC.getOrderEbeanQuery(queryNodeMock);

    // Then
    Mockito.verify(queryNodeMock, Mockito.times(1)).order();
    Mockito.verify(orderByNodeMock, Mockito.times(1)).desc("t0.updated_timestamp");

    Mockito.verifyNoMoreInteractions(queryNodeMock);
    Mockito.verifyNoMoreInteractions(orderByNodeMock);
  }

  @Test
  void givenACreatedAtAscNodeSortTheGetOrderEbeanQueryShouldApplyTheOrderByCorrectly() {
    // Given && When
    NodeSort.CREATED_AT_ASC.getOrderEbeanQuery(queryNodeMock);

    // Then
    Mockito.verify(queryNodeMock, Mockito.times(1)).order();
    Mockito.verify(orderByNodeMock, Mockito.times(1)).asc("t0.creation_timestamp");

    Mockito.verifyNoMoreInteractions(queryNodeMock);
    Mockito.verifyNoMoreInteractions(orderByNodeMock);
  }

  @Test
  void givenACreatedAtDescNodeSortTheGetOrderEbeanQueryShouldApplyTheOrderByCorrectly() {
    // Given && When
    NodeSort.CREATED_AT_DESC.getOrderEbeanQuery(queryNodeMock);

    // Then
    Mockito.verify(queryNodeMock, Mockito.times(1)).order();
    Mockito.verify(orderByNodeMock, Mockito.times(1)).desc("t0.creation_timestamp");

    Mockito.verifyNoMoreInteractions(queryNodeMock);
    Mockito.verifyNoMoreInteractions(orderByNodeMock);
  }

  @Test
  void givenASizeAscNodeSortTheGetOrderEbeanQueryShouldApplyTheOrderByCorrectly() {
    // Given && When
    NodeSort.SIZE_ASC.getOrderEbeanQuery(queryNodeMock);

    // Then
    Mockito.verify(queryNodeMock, Mockito.times(1)).order();
    Mockito.verify(orderByNodeMock, Mockito.times(1)).asc("t0.size");

    Mockito.verifyNoMoreInteractions(queryNodeMock);
    Mockito.verifyNoMoreInteractions(orderByNodeMock);
  }

  @Test
  void givenASizeDescNodeSortTheGetOrderEbeanQueryShouldApplyTheOrderByCorrectly() {
    // Given && When
    NodeSort.SIZE_DESC.getOrderEbeanQuery(queryNodeMock);

    // Then
    Mockito.verify(queryNodeMock, Mockito.times(1)).order();
    Mockito.verify(orderByNodeMock, Mockito.times(1)).desc("t0.size");

    Mockito.verifyNoMoreInteractions(queryNodeMock);
    Mockito.verifyNoMoreInteractions(orderByNodeMock);
  }
}

