// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.graphql.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.zextras.carbonio.files.config.FilesConfig;
import com.zextras.carbonio.files.dal.locks.NodeLocks;
import com.zextras.carbonio.files.dal.repositories.impl.Transactions;
import com.zextras.carbonio.files.dal.repositories.interfaces.NodeRepository;
import com.zextras.carbonio.files.dal.repositories.interfaces.NotificationRepository;
import com.zextras.carbonio.files.dal.repositories.interfaces.ShareRepository;
import com.zextras.carbonio.files.rest.services.BlobKey;
import com.zextras.carbonio.files.rest.services.BlobLifecycle;
import com.zextras.carbonio.files.utilities.PermissionsChecker;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

class AccountDeletionTransactionTest {

  private static final String USER = "user-1";

  private NodeRepository nodeRepository;
  private NodeLocks nodeLocks;
  private BlobLifecycle blobLifecycle;
  private NodeCreationHelper helper;
  private Transactions transactions;
  private Map<String, List<BlobKey>> keys;

  @BeforeEach
  void setUp() throws Exception {
    nodeRepository = mock(NodeRepository.class);
    nodeLocks = mock(NodeLocks.class);
    blobLifecycle = mock(BlobLifecycle.class);
    transactions = mock(Transactions.class);
    when(transactions.inNewTransaction(any(Duration.class), any()))
        .thenAnswer(call -> call.<Callable<?>>getArgument(1).call());
    helper =
        new NodeCreationHelper(
            nodeRepository,
            mock(ShareRepository.class),
            mock(NotificationRepository.class),
            mock(FilesConfig.class),
            blobLifecycle,
            mock(PermissionsChecker.class),
            mock(ShareCascadeHelper.class),
            nodeLocks,
            transactions);
    keys = Map.of("owner", List.of(new BlobKey("node-1", 1)));
    when(nodeRepository.getTopMostIdsByOwner(USER)).thenReturn(List.of("node-1"));
  }

  @Test
  void locksThenDeletesInOneSynchronousTransactionAndDeletesBlobsAfterwards() {
    when(nodeRepository.deleteSubtrees(List.of("node-1"))).thenReturn(keys);

    helper.deleteAllNodesAndBlobsForUser(USER);

    InOrder order = inOrder(transactions, nodeLocks, nodeRepository, blobLifecycle);
    order.verify(transactions).inNewTransaction(eq(Duration.ofSeconds(600)), any());
    order.verify(nodeLocks).lockOwnerRowsForDeletion(USER);
    order.verify(nodeRepository).deleteSubtrees(List.of("node-1"));
    order.verify(blobLifecycle).deleteAndClear("owner", keys.get("owner"));
    verify(transactions, times(1)).inNewTransaction(any(Duration.class), any());
  }

  @Test
  void doesNotRetryADeadlockAndDeletesNoBlobs() {
    RuntimeException failure = new RuntimeException(new SQLException("deadlock", "40P01"));
    when(nodeRepository.deleteSubtrees(any())).thenThrow(failure);

    assertThat(catchThrowable(() -> helper.deleteAllNodesAndBlobsForUser(USER))).isSameAs(failure);

    verify(transactions, times(1)).inNewTransaction(any(Duration.class), any());
    verify(nodeLocks, times(1)).lockOwnerRowsForDeletion(USER);
    verify(blobLifecycle, never()).deleteAndClear(any(), any());
  }
}
