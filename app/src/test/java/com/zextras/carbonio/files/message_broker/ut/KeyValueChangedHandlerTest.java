// SPDX-FileCopyrightText: 2024 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.message_broker.ut;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.zextras.carbonio.files.dal.dao.ebean.FileVersion;
import com.zextras.carbonio.files.dal.dao.ebean.Node;
import com.zextras.carbonio.files.dal.locks.LockRetry;
import com.zextras.carbonio.files.dal.locks.NodeLocks;
import com.zextras.carbonio.files.dal.repositories.impl.ebean.utilities.FileVersionSort;
import com.zextras.carbonio.files.dal.repositories.interfaces.FileVersionRepository;
import com.zextras.carbonio.files.dal.repositories.interfaces.NodeRepository;
import com.zextras.carbonio.files.dal.repositories.interfaces.TombstoneRepository;
import com.zextras.carbonio.files.message_broker.consumers.KeyValueChangedConsumer;
import com.zextras.carbonio.message_broker.events.services.service_discover.KeyValueChanged;
import com.zextras.filestore.api.Filestore;
import com.zextras.filestore.model.BulkDeleteRequestItem;
import com.zextras.filestore.model.BulkDeleteResponseItem;
import com.zextras.filestore.model.IdentifierType;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

class KeyValueChangedHandlerTest {

  private static final String KEY = "carbonio-files/max-number-of-versions";

  private FileVersionRepository fileVersionRepository;
  private NodeRepository nodeRepository;
  private TombstoneRepository tombstoneRepository;
  private LockRetry lockRetry;
  private Filestore filestore;
  private KeyValueChangedConsumer consumer;

  private static FileVersion version(int number, boolean keptForever) {
    FileVersion fileVersion = mock(FileVersion.class);
    when(fileVersion.getNodeId()).thenReturn("node-1");
    when(fileVersion.getVersion()).thenReturn(number);
    when(fileVersion.isKeptForever()).thenReturn(keptForever);
    return fileVersion;
  }

  private void nodeWithVersions(int current, FileVersion... versions) {
    Node node = mock(Node.class);
    when(node.getCurrentVersion()).thenReturn(current);
    when(node.getOwnerId()).thenReturn("owner-1");
    when(nodeRepository.getNode("node-1")).thenReturn(Optional.of(node));
    when(fileVersionRepository.getFileVersionsRelatedToNodesHavingVersionsGreaterThan(anyInt()))
        .thenReturn(Map.of("node-1", List.of(versions)));
    when(fileVersionRepository.getFileVersions("node-1", List.of(FileVersionSort.VERSION_ASC)))
        .thenReturn(List.of(versions));
  }

  @BeforeEach
  @SuppressWarnings("unchecked")
  void setUp() {
    fileVersionRepository = mock(FileVersionRepository.class);
    nodeRepository = mock(NodeRepository.class);
    tombstoneRepository = mock(TombstoneRepository.class);
    lockRetry = mock(LockRetry.class);
    filestore = mock(Filestore.class);
    when(lockRetry.inNewTransaction(any()))
        .thenAnswer(invocation -> invocation.<Supplier<Object>>getArgument(0).get());
    consumer =
        new KeyValueChangedConsumer(
            fileVersionRepository,
            nodeRepository,
            tombstoneRepository,
            mock(NodeLocks.class),
            lockRetry,
            filestore);
  }

  @Test
  void
      givenAnExceedingNodeThenTheOldestNotKeptNotCurrentVersionsAreTombstonedAndDeletedAfterCommit()
          throws Exception {
    FileVersion kept = version(1, true);
    FileVersion second = version(2, false);
    FileVersion third = version(3, false);
    FileVersion fourth = version(4, false);
    FileVersion current = version(5, false);
    nodeWithVersions(5, kept, second, third, fourth, current);
    when(filestore.bulkDelete(eq(IdentifierType.files), eq("owner-1"), anyList()))
        .thenReturn(List.of());

    consumer.doHandle(new KeyValueChanged(KEY, "2"));

    InOrder order = inOrder(tombstoneRepository, fileVersionRepository, filestore);
    order
        .verify(tombstoneRepository)
        .createTombstonesBulk(List.of(second, third, fourth), "owner-1");
    order.verify(fileVersionRepository).deleteFileVersions("node-1", List.of(2, 3, 4));
    order
        .verify(filestore)
        .bulkDelete(
            eq(IdentifierType.files),
            eq("owner-1"),
            argThat(
                (List<BulkDeleteRequestItem> items) ->
                    items.stream()
                        .map(BulkDeleteRequestItem::getVersion)
                        .toList()
                        .equals(List.of(Optional.of(2), Optional.of(3), Optional.of(4)))));
    order.verify(tombstoneRepository).deleteTombstonesByNodeAndVersion("node-1", 2);
    order.verify(tombstoneRepository).deleteTombstonesByNodeAndVersion("node-1", 3);
    order.verify(tombstoneRepository).deleteTombstonesByNodeAndVersion("node-1", 4);
    verify(tombstoneRepository, never()).deleteTombstonesByNodeAndVersion("node-1", 1);
    verify(tombstoneRepository, never()).deleteTombstonesByNodeAndVersion("node-1", 5);
  }

  @Test
  void givenOnlyKeptForeverAndCurrentVersionsAboveTheMaxThenOnlyTheOtherOneIsDeleted()
      throws Exception {
    FileVersion keptFirst = version(1, true);
    FileVersion second = version(2, false);
    FileVersion keptThird = version(3, true);
    FileVersion current = version(4, false);
    nodeWithVersions(4, keptFirst, second, keptThird, current);
    when(filestore.bulkDelete(any(), anyString(), anyList())).thenReturn(List.of());

    consumer.doHandle(new KeyValueChanged(KEY, "1"));

    verify(tombstoneRepository).createTombstonesBulk(List.of(second), "owner-1");
    verify(fileVersionRepository).deleteFileVersions("node-1", List.of(2));
  }

  @Test
  void givenAVersionTheStorageFailedToDeleteThenItsTombstoneStays() throws Exception {
    FileVersion second = version(2, false);
    FileVersion third = version(3, false);
    FileVersion current = version(4, false);
    nodeWithVersions(4, version(1, true), second, third, current);
    BulkDeleteResponseItem failed = mock(BulkDeleteResponseItem.class);
    when(failed.getNode()).thenReturn("node-1");
    when(failed.getVersion()).thenReturn(Optional.of(3));
    when(filestore.bulkDelete(any(), anyString(), anyList())).thenReturn(List.of(failed));

    consumer.doHandle(new KeyValueChanged(KEY, "2"));

    verify(tombstoneRepository).deleteTombstonesByNodeAndVersion("node-1", 2);
    verify(tombstoneRepository, never()).deleteTombstonesByNodeAndVersion("node-1", 3);
  }

  @Test
  void givenAStorageOutageThenTheTombstonesStay() throws Exception {
    nodeWithVersions(3, version(1, false), version(2, false), version(3, false));
    when(filestore.bulkDelete(any(), anyString(), anyList()))
        .thenThrow(new RuntimeException("storages down"));

    consumer.doHandle(new KeyValueChanged(KEY, "2"));

    verify(tombstoneRepository).createTombstonesBulk(anyList(), eq("owner-1"));
    verify(tombstoneRepository, never()).deleteTombstonesByNodeAndVersion(any(), any());
  }

  @Test
  void givenANodeDeletedMeanwhileThenNothingIsDone() throws Exception {
    nodeWithVersions(3, version(1, false), version(2, false), version(3, false));
    when(nodeRepository.getNode("node-1")).thenReturn(Optional.empty());

    consumer.doHandle(new KeyValueChanged(KEY, "1"));

    verifyNoInteractions(tombstoneRepository, filestore);
    verify(fileVersionRepository, never()).deleteFileVersions(any(), any());
  }

  @Test
  void givenKvChangedEventNotHandledNoOperationShouldBePerformed() {
    consumer.doHandle(new KeyValueChanged("fake", "fake"));

    verifyNoInteractions(fileVersionRepository, nodeRepository, tombstoneRepository, filestore);
  }
}
