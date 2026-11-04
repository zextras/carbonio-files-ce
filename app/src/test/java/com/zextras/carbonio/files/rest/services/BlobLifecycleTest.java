// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.rest.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.zextras.carbonio.files.dal.repositories.interfaces.TombstoneRepository;
import com.zextras.carbonio.files.dal.repositories.interfaces.VersionReservationRepository;
import com.zextras.filestore.api.Filestore;
import com.zextras.filestore.model.BulkDeleteResponseItem;
import com.zextras.filestore.model.IdentifierType;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class BlobLifecycleTest {

  private Filestore fileStore;
  private TombstoneRepository tombstones;
  private VersionReservationRepository reservations;
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
    blobLifecycle = new BlobLifecycle(fileStore, tombstones, reservations);
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
}
