// SPDX-FileCopyrightText: 2024 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.config;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

class FilesConfigTest {

  @BeforeEach
  void setUp() {}

  // This test will pass when the config.ini is removed or when the FilesConfig
  // uses also the System.getEnv() to retrieve the configuration values.
  @Disabled("Disabled until a refactor on the FilesConfig is done")
  @Test
  void
      givenAMailboxUrlAndAPortSetInPropertiesTheGetMailboxUrlShouldReturnTheFullMailboxUrlString() {
    // Given
    System.setProperty("carbonio.mailbox.url", "1.2.3.4");
    System.setProperty("carbonio.mailbox.port", "9999");

    FilesConfig filesConfig = new FilesConfig();

    // When
    String mailboxUrl = filesConfig.getMailboxUrl();

    // Then
    Assertions.assertThat(mailboxUrl).isEqualTo("http://1.2.3.4:9999/");
  }

  @Test
  void givenEmptyPropertiesTheGetMailboxUrlShouldReturnTheDefaultFullMailboxUrlString() {
    // Given
    FilesConfig filesConfig = new FilesConfig();

    // When
    String mailboxUrl = filesConfig.getMailboxUrl();

    // Then
    Assertions.assertThat(mailboxUrl).isEqualTo("http://127.78.0.2:20004/");
  }
}
