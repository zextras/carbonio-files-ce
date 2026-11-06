// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.rest.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.zextras.carbonio.files.dal.repositories.impl.Transactions;
import com.zextras.carbonio.files.dal.repositories.interfaces.TombstoneRepository;
import com.zextras.carbonio.files.dal.repositories.interfaces.VersionReservationRepository;
import com.zextras.carbonio.files.rest.services.VersionReservations.Reservation;
import com.zextras.filestore.api.Filestore;
import com.zextras.filestore.api.UploadResponse;
import com.zextras.filestore.model.BulkDeleteRequestItem;
import com.zextras.filestore.model.BulkDeleteResponseItem;
import com.zextras.filestore.model.FilesIdentifier;
import com.zextras.filestore.model.IdentifierType;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

class BlobLifecycleTest {

  private Filestore fileStore;
  private TombstoneRepository tombstones;
  private VersionReservationRepository reservations;
  private Transactions transactions;
  private BlobLifecycle blobLifecycle;

  private static BulkDeleteResponseItem failed(String nodeId, Integer version) {
    BulkDeleteResponseItem item = mock(BulkDeleteResponseItem.class);
    when(item.getNode()).thenReturn(nodeId);
    when(item.getVersion()).thenReturn(Optional.ofNullable(version));
    return item;
  }

  @BeforeEach
  void setUp() {
    fileStore = mock(Filestore.class);
    tombstones = mock(TombstoneRepository.class);
    reservations = mock(VersionReservationRepository.class);
    transactions = mock(Transactions.class);
    when(transactions.inNewTransaction(any()))
        .thenAnswer(invocation -> invocation.<Callable<?>>getArgument(0).call());
    blobLifecycle = new BlobLifecycle(fileStore, tombstones, reservations, transactions);
  }

  @Test
  void clearsOnlyTheTombstonesOfKeysNotReportedAsFailed() throws Exception {
    List<BulkDeleteResponseItem> failed = List.of(failed("n1", 2));
    when(fileStore.bulkDelete(eq(IdentifierType.files), eq("owner"), anyList())).thenReturn(failed);

    blobLifecycle.deleteAndClear("owner", List.of(new BlobKey("n1", 2), new BlobKey("n1", 3)));

    verify(tombstones).deleteTombstonesByNodeAndVersion("n1", 3);
    verify(tombstones, never()).deleteTombstonesByNodeAndVersion("n1", 2);
  }

  @Test
  void anItemWithoutVersionFailsEveryVersionOfThatNode() throws Exception {
    List<BulkDeleteResponseItem> failed = List.of(failed("n1", null));
    when(fileStore.bulkDelete(eq(IdentifierType.files), eq("owner"), anyList())).thenReturn(failed);

    blobLifecycle.deleteAndClear(
        "owner", List.of(new BlobKey("n1", 1), new BlobKey("n1", 2), new BlobKey("n2", 1)));

    verify(tombstones).deleteTombstonesByNodeAndVersion("n2", 1);
    verifyNoMoreInteractions(tombstones);
  }

  @Test
  void anEmptyListDoesNotCallTheStorage() {
    assertThat(blobLifecycle.delete("owner", List.of())).contains(Set.of());

    verifyNoMoreInteractions(fileStore);
  }

  static Stream<Arguments> unknownAnswers() {
    return Stream.of(
        Arguments.of("exception", new RuntimeException("down")),
        Arguments.of("null", null),
        Arguments.of("npe", new NullPointerException("malformed")));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("unknownAnswers")
  void anUnknownAnswerKeepsEveryTombstone(String name, Throwable failure) throws Exception {
    if (failure != null) {
      when(fileStore.bulkDelete(any(), anyString(), anyList())).thenThrow(failure);
    } else {
      when(fileStore.bulkDelete(any(), anyString(), anyList())).thenReturn(null);
    }
    List<BlobKey> keys = List.of(new BlobKey("n1", 1));

    assertThat(blobLifecycle.delete("owner", keys)).isEmpty();
    blobLifecycle.deleteAndClear("owner", keys);

    verifyNoMoreInteractions(tombstones);
  }

  @Test
  void discardTombstonesTheConsumedReservationThenDeletesItsBlob() throws Exception {
    when(reservations.consume("n1", 2)).thenReturn(true);
    when(fileStore.bulkDelete(eq(IdentifierType.files), eq("owner"), anyList()))
        .thenReturn(List.of());

    blobLifecycle.discard(new Reservation("n1", 2, 1L, "owner"));

    InOrder order = inOrder(transactions, reservations, tombstones, fileStore);
    order.verify(transactions).inNewTransaction(any());
    order.verify(reservations).consume("n1", 2);
    order.verify(tombstones).createNewTombstone("n1", "owner", 2);
    order.verify(fileStore).bulkDelete(eq(IdentifierType.files), eq("owner"), anyList());
    order.verify(tombstones).deleteTombstonesByNodeAndVersion("n1", 2);
  }

  @Test
  void discardLeavesAReservationTheCleanupAlreadyClaimedToThePurge() {
    when(reservations.consume("n1", 2)).thenReturn(false);

    blobLifecycle.discard(new Reservation("n1", 2, 1L, "owner"));

    verifyNoMoreInteractions(tombstones, fileStore);
  }

  @Test
  void discardDoesNotThrowAndDeletesNothingWhenTheTombstoneCannotBeWritten() {
    when(reservations.consume("n1", 2)).thenReturn(true);
    when(tombstones.createNewTombstone("n1", "owner", 2)).thenThrow(new RuntimeException("db"));

    blobLifecycle.discard(new Reservation("n1", 2, 1L, "owner"));

    verifyNoMoreInteractions(fileStore);
  }

  @Test
  void abandonTombstonesTheConsumedReservationAndLeavesTheDeleteToThePurge() {
    when(reservations.consume("n1", 2)).thenReturn(true);

    blobLifecycle.abandon(new Reservation("n1", 2, 1L, "owner"));

    verify(tombstones).createNewTombstone("n1", "owner", 2);
    verifyNoInteractions(fileStore);
    verify(tombstones, never()).deleteTombstonesByNodeAndVersion(anyString(), any());
  }

  @Test
  void abandonDoesNotThrowWhenTheTombstoneCannotBeWritten() {
    when(reservations.consume("n1", 2)).thenThrow(new RuntimeException("db"));

    blobLifecycle.abandon(new Reservation("n1", 2, 1L, "owner"));

    verifyNoInteractions(fileStore);
  }

  @Test
  void deleteAndClearSendsAtMostOneBatchOfKeysPerCall() throws Exception {
    when(fileStore.bulkDelete(eq(IdentifierType.files), eq("owner"), anyList()))
        .thenReturn(List.of());
    List<BlobKey> keys =
        IntStream.rangeClosed(1, BlobLifecycle.DELETE_BATCH_SIZE + 1)
            .mapToObj(v -> new BlobKey("n1", v))
            .toList();

    blobLifecycle.deleteAndClear("owner", keys);

    ArgumentCaptor<List<BulkDeleteRequestItem>> batches = ArgumentCaptor.captor();
    verify(fileStore, times(2))
        .bulkDelete(eq(IdentifierType.files), eq("owner"), batches.capture());
    assertThat(batches.getAllValues())
        .extracting(List::size)
        .containsExactly(BlobLifecycle.DELETE_BATCH_SIZE, 1);
    verify(tombstones, times(keys.size())).deleteTombstonesByNodeAndVersion(eq("n1"), any());
  }

  @Test
  void anUnknownAnswerForOneBatchStillClearsTheOtherBatches() throws Exception {
    when(fileStore.bulkDelete(eq(IdentifierType.files), eq("owner"), anyList()))
        .thenThrow(new RuntimeException("down"))
        .thenReturn(List.of());
    List<BlobKey> keys =
        IntStream.rangeClosed(1, BlobLifecycle.DELETE_BATCH_SIZE + 1)
            .mapToObj(v -> new BlobKey("n1", v))
            .toList();

    blobLifecycle.deleteAndClear("owner", keys);

    verify(tombstones, times(1)).deleteTombstonesByNodeAndVersion(eq("n1"), any());
    verify(tombstones).deleteTombstonesByNodeAndVersion("n1", BlobLifecycle.DELETE_BATCH_SIZE + 1);
  }

  @Test
  void existsIsTrueWhenTheStoreStreamsTheBlob() throws Exception {
    when(fileStore.download(any())).thenReturn(new java.io.ByteArrayInputStream(new byte[] {1}));

    assertThat(
            blobLifecycle.exists(
                "11111111-1111-1111-1111-111111111111", 1, "22222222-2222-2222-2222-222222222222"))
        .isTrue();
  }

  @Test
  void existsIsFalseWhenTheStoreThrows() throws Exception {
    when(fileStore.download(any())).thenThrow(new RuntimeException("404"));

    assertThat(
            blobLifecycle.exists(
                "11111111-1111-1111-1111-111111111111", 1, "22222222-2222-2222-2222-222222222222"))
        .isFalse();
  }

  private static final FilesIdentifier SOURCE =
      FilesIdentifier.of(
          "00000000-0000-0000-0000-000000000001", 1, "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
  private static final FilesIdentifier DESTINATION =
      FilesIdentifier.of(
          "00000000-0000-0000-0000-000000000002", 1, "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");

  @Test
  void aCopyDoesNotOverrideAndReturnsTheCopyResponse() throws Exception {
    UploadResponse response = mock(UploadResponse.class);
    when(fileStore.copy(SOURCE, DESTINATION, false)).thenReturn(response);

    assertThat(blobLifecycle.copy(SOURCE, DESTINATION).get()).isSameAs(response);
    verify(fileStore, never()).download(any());
  }

  @Test
  void aFailedCopyFailsWithItsCause() throws Exception {
    RuntimeException cause = new RuntimeException("500");
    when(fileStore.copy(SOURCE, DESTINATION, false)).thenThrow(cause);

    assertThat(blobLifecycle.copy(SOURCE, DESTINATION).getCause()).isSameAs(cause);
  }

  @Test
  void aFailureReportedForTheNodeWithoutVersionKeepsEveryVersionOfThatNode() throws Exception {
    List<BulkDeleteResponseItem> failed = List.of(failed("n1", null), failed("n2", 2));
    when(fileStore.bulkDelete(eq(IdentifierType.files), eq("owner"), anyList())).thenReturn(failed);

    assertThat(
            blobLifecycle.delete(
                "owner",
                List.of(
                    new BlobKey("n1", 1),
                    new BlobKey("n1", 5),
                    new BlobKey("n2", 1),
                    new BlobKey("n2", 2))))
        .contains(Set.of(new BlobKey("n1", 1), new BlobKey("n1", 5), new BlobKey("n2", 2)));
  }
}
