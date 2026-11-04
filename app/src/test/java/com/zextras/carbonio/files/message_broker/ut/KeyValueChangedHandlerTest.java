// SPDX-FileCopyrightText: 2024 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.message_broker.ut;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import com.zextras.carbonio.files.dal.dao.ebean.FileVersion;
import com.zextras.carbonio.files.dal.dao.ebean.Node;
import com.zextras.carbonio.files.dal.repositories.impl.Transactions;
import com.zextras.carbonio.files.dal.repositories.impl.ebean.utilities.FileVersionSort;
import com.zextras.carbonio.files.dal.repositories.interfaces.FileVersionRepository;
import com.zextras.carbonio.files.dal.repositories.interfaces.NodeRepository;
import com.zextras.carbonio.files.dal.repositories.interfaces.TombstoneRepository;
import com.zextras.carbonio.files.message_broker.consumers.KeyValueChangedConsumer;
import com.zextras.carbonio.files.rest.services.BlobKey;
import com.zextras.carbonio.files.rest.services.BlobLifecycle;
import com.zextras.carbonio.message_broker.events.services.service_discover.KeyValueChanged;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

class KeyValueChangedHandlerTest {

  private FileVersionRepository fileVersionRepository;
  private NodeRepository nodeRepository;
  private TombstoneRepository tombstoneRepository;
  private Transactions transactions;
  private BlobLifecycle blobLifecycle;
  private KeyValueChangedConsumer consumer;

  @BeforeEach
  void setUp() throws Exception {
    fileVersionRepository = mock(FileVersionRepository.class);
    nodeRepository = mock(NodeRepository.class);
    tombstoneRepository = mock(TombstoneRepository.class);
    transactions = mock(Transactions.class);
    blobLifecycle = mock(BlobLifecycle.class);
    when(transactions.inNewTransaction(any()))
        .thenAnswer(invocation -> invocation.<Callable<?>>getArgument(0).call());
    consumer =
        new KeyValueChangedConsumer(
            fileVersionRepository,
            nodeRepository,
            tombstoneRepository,
            transactions,
            blobLifecycle);
  }

  private static FileVersion version(int number, boolean keptForever) {
    FileVersion fileVersion = mock(FileVersion.class);
    when(fileVersion.getNodeId()).thenReturn("node-1");
    when(fileVersion.getVersion()).thenReturn(number);
    when(fileVersion.isKeptForever()).thenReturn(keptForever);
    return fileVersion;
  }

  private void givenNodeWithVersions(List<FileVersion> versions) {
    Node node = mock(Node.class);
    when(node.getOwnerId()).thenReturn("owner-1");
    when(nodeRepository.getNode("node-1")).thenReturn(Optional.of(node));
    when(fileVersionRepository.getFileVersionsRelatedToNodesHavingVersionsGreaterThan(anyInt()))
        .thenReturn(Map.of("node-1", versions));
    when(fileVersionRepository.getFileVersions("node-1", List.of(FileVersionSort.VERSION_ASC)))
        .thenReturn(versions);
    when(fileVersionRepository.removeVersionNotKeptForever(eq("node-1"), anyInt()))
        .thenReturn(true);
  }

  /**
   * Only the correct versions are removed; the corner case where more versions than the new max
   * remain is covered too: some are keepforever and the current version is never removed.
   */
  @Test
  void givenMaxVersionNumberEventAndExceedingFileVersionsDoHandleShouldDeleteCorrectVersions() {
    givenNodeWithVersions(
        List.of(version(1, true), version(2, false), version(3, true), version(4, false)));

    consumer.doHandle(new KeyValueChanged("carbonio-files/max-number-of-versions", "1"));

    verify(fileVersionRepository).removeVersionNotKeptForever("node-1", 2);
    verify(fileVersionRepository, times(1)).removeVersionNotKeptForever(eq("node-1"), anyInt());
    verify(blobLifecycle).deleteAndClear("owner-1", List.of(new BlobKey("node-1", 2)));
  }

  @Test
  void givenMaxVersionNumberEventTheTrimmedVersionsAreTombstonedInOneTransactionBeforeTheBlobsGo() {
    givenNodeWithVersions(
        List.of(
            version(1, false),
            version(2, true),
            version(3, false),
            version(4, false),
            version(5, false)));

    consumer.doHandle(new KeyValueChanged("carbonio-files/max-number-of-versions", "2"));

    InOrder order =
        inOrder(transactions, fileVersionRepository, tombstoneRepository, blobLifecycle);
    order.verify(transactions).inNewTransaction(any());
    order.verify(fileVersionRepository).removeVersionNotKeptForever("node-1", 1);
    order.verify(tombstoneRepository).createNewTombstone("node-1", "owner-1", 1);
    order.verify(fileVersionRepository).removeVersionNotKeptForever("node-1", 3);
    order.verify(tombstoneRepository).createNewTombstone("node-1", "owner-1", 3);
    order.verify(fileVersionRepository).removeVersionNotKeptForever("node-1", 4);
    order.verify(tombstoneRepository).createNewTombstone("node-1", "owner-1", 4);
    order
        .verify(blobLifecycle)
        .deleteAndClear(
            "owner-1",
            List.of(new BlobKey("node-1", 1), new BlobKey("node-1", 3), new BlobKey("node-1", 4)));
    verify(fileVersionRepository, never()).removeVersionNotKeptForever("node-1", 2);
    verify(fileVersionRepository, never()).removeVersionNotKeptForever("node-1", 5);
    verify(transactions, times(1)).inNewTransaction(any());
  }

  @Test
  void aVersionRemovedByAConcurrentTransactionGetsNoTombstone() {
    givenNodeWithVersions(List.of(version(1, false), version(2, false), version(3, false)));
    when(fileVersionRepository.removeVersionNotKeptForever("node-1", 1)).thenReturn(false);

    consumer.doHandle(new KeyValueChanged("carbonio-files/max-number-of-versions", "2"));

    verify(tombstoneRepository, never()).createNewTombstone(any(), any(), any());
  }

  @Test
  void givenKvChangedEventNotHandledNoOperationShouldBePerformed() {
    // Given
    consumer.doHandle(new KeyValueChanged("fake", "fake"));

    // Then
    verifyNoInteractions(fileVersionRepository);
  }
}
