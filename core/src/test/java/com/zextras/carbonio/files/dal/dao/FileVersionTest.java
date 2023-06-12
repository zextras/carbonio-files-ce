// SPDX-FileCopyrightText: 2023 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.dal.dao;

import com.zextras.carbonio.files.dal.dao.ebean.FileVersion;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

class FileVersionTest {

  @Test
  void givenAllFileVersionAttributesTheConstructorShouldCreateFileVersionObjectCorrectly() {
    // Given && When
    FileVersion fileVersion = new FileVersion(
      "868b43cc-3a8f-4c14-a66d-f520d8e7e8bd",
      "c6bf990d-86b9-49ad-a6c0-12260308b7c5",
      5L,
      1,
      "text/plain",
      10L,
      "fake-digest",
      false
    );

    // Then
    Assertions.assertThat(fileVersion.getNodeId())
      .isEqualTo("868b43cc-3a8f-4c14-a66d-f520d8e7e8bd");
    Assertions.assertThat(fileVersion.getLastEditorId())
      .isEqualTo("c6bf990d-86b9-49ad-a6c0-12260308b7c5");
    Assertions.assertThat(fileVersion.getUpdatedAt()).isEqualTo(5L);
    Assertions.assertThat(fileVersion.getVersion()).isEqualTo(1);
    Assertions.assertThat(fileVersion.getMimeType()).isEqualTo("text/plain");
    Assertions.assertThat(fileVersion.getSize()).isEqualTo(10L);
    Assertions.assertThat(fileVersion.getDigest()).isEqualTo("fake-digest");
    Assertions.assertThat(fileVersion.isAutosave()).isFalse();
    Assertions.assertThat(fileVersion.getClonedFromVersion()).isEmpty();
    Assertions.assertThat(fileVersion.isKeptForever()).isFalse();
  }

  @Test
  void givenDifferentFileVersionAttributesTheSettersShouldUpdateFileVersionObjectCorrectly() {
    // Given && When
    FileVersion fileVersion = new FileVersion(
      "868b43cc-3a8f-4c14-a66d-f520d8e7e8bd",
      "c6bf990d-86b9-49ad-a6c0-12260308b7c5",
      5L,
      1,
      "text/plain",
      10L,
      "fake-digest",
      false
    );

    // When
    fileVersion
      .setClonedFromVersion(5)
      .keepForever(true);

    // Then
    Assertions.assertThat(fileVersion.getClonedFromVersion()).isPresent().contains(5);
    Assertions.assertThat(fileVersion.isKeptForever()).isTrue();
  }
}
