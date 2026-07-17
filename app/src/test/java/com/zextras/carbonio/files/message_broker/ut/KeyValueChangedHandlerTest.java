// SPDX-FileCopyrightText: 2024 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.message_broker.ut;

import com.zextras.carbonio.files.dal.dao.ebean.FileVersion;
import com.zextras.carbonio.files.dal.repositories.interfaces.FileVersionRepository;
import com.zextras.carbonio.files.message_broker.consumers.KeyValueChangedConsumer;
import com.zextras.carbonio.message_broker.events.services.service_discover.KeyValueChanged;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Map;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

class KeyValueChangedHandlerTest {

  /**
   * This tests that only the correct versions are deleted and results in the corner case where
   * the remaining file versions are more than the new maxversionnumber: this is because
   * some of them are keepforever, and we can't ever delete the current version.
   * While this is a corner case, this test implicitly covers also the generic behaviour
   * since this corner case is just a particular ending situation caused by the normal flow of
   * deleting the least recent file versions.
   */
  @Test
  void givenMaxVersionNumberEventAndExceedingFileVersionsDoHandleShouldDeleteCorrectVersions() {
    // Given
    FileVersionRepository fileVersionRepository = mock(FileVersionRepository.class);
    FileVersion fileVersion1 = mock(FileVersion.class); // should not delete as it is keepforever
    FileVersion fileVersion2 = mock(FileVersion.class); // should delete as it is the least recent that is not keepforever
    FileVersion fileVersion3 = mock(FileVersion.class); // should not delete as it is keepforever
    FileVersion fileVersion4 = mock(FileVersion.class); // should not delete as it is current version

    when(fileVersion1.isKeptForever()).thenReturn(true);
    when(fileVersion2.isKeptForever()).thenReturn(false);
    when(fileVersion3.isKeptForever()).thenReturn(true);
    when(fileVersion4.isKeptForever()).thenReturn(false);

    when(fileVersionRepository.getFileVersionsRelatedToNodesHavingVersionsGreaterThan(anyInt()))
        .thenReturn(Map.of("key", Arrays.asList(fileVersion1, fileVersion2, fileVersion3, fileVersion4)));

    KeyValueChangedConsumer keyValueChangedConsumer = new KeyValueChangedConsumer(fileVersionRepository);

    // When
    keyValueChangedConsumer.doHandle(new KeyValueChanged("carbonio-files/max-number-of-versions", "1"));

    // Then
    verify(fileVersionRepository, times(1)).deleteFileVersion(fileVersion2);
    verify(fileVersionRepository, never()).deleteFileVersion(fileVersion1);
    verify(fileVersionRepository, never()).deleteFileVersion(fileVersion3);
    verify(fileVersionRepository, never()).deleteFileVersion(fileVersion4);
  }

  @Test
  void givenKvChangedEventNotHandledNoOperationShouldBePerformed() {
    // Given
    FileVersionRepository fileVersionRepository = mock(FileVersionRepository.class);
    KeyValueChangedConsumer keyValueChangedConsumer = new KeyValueChangedConsumer(fileVersionRepository);

    // When
    keyValueChangedConsumer.doHandle(new KeyValueChanged("fake", "fake"));

    // Then
    verifyNoInteractions(fileVersionRepository);
  }

}
