// SPDX-FileCopyrightText: 2024 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.config;

import com.zextras.carbonio.files.Constants.ServiceDiscover;
import java.io.IOException;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

class FilesConfigTest {

  @BeforeEach
  void setUp() {
    System.clearProperty(ServiceDiscover.HOST_PROPERTY);
    System.clearProperty(ServiceDiscover.PORT_PROPERTY);
  }

  // This test will pass when the config.ini is removed or when the FilesConfig
  // uses also the System.getEnv() to retrieve the configuration values.
  @Disabled("Disabled until a refactor on the FilesConfig is done")
  @Test
  void givenAMailboxUrlAndAPortSetInPropertiesTheGetMailboxUrlShouldReturnTheFullMailboxUrlString()
      throws IOException {
    // Given
    System.setProperty("carbonio.mailbox.url", "1.2.3.4");
    System.setProperty("carbonio.mailbox.port", "9999");

    FilesConfig filesConfig = getLoadedConfig();

    // When
    String mailboxUrl = filesConfig.getMailboxHost();

    // Then
    Assertions.assertThat(mailboxUrl).isEqualTo("http://1.2.3.4:9999/");
  }

  @Test
  void givenEmptyPropertiesTheGetMailboxUrlShouldReturnTheDefaultFullMailboxUrlString()
      throws IOException {
    // Given
    FilesConfig filesConfig = getLoadedConfig();

    // When
    String mailboxUrl =
        "http://" + filesConfig.getMailboxHost() + ":" + filesConfig.getMailboxPort() + "/";

    // Then
    Assertions.assertThat(mailboxUrl).isEqualTo("http://127.78.0.2:20004/");
  }

  @Test
  void givenEmptyPropertiesTheServiceDiscoverEndpointShouldReturnTheDefault() throws IOException {
    FilesConfig filesConfig = getLoadedConfig();

    Assertions.assertThat(filesConfig.getServiceDiscoverEndpoint())
        .isEqualTo("http://localhost:8500");
  }

  @Test
  void givenServiceDiscoverPropertiesTheServiceDiscoverEndpointShouldReturnProvidedValues()
      throws IOException {
    System.setProperty(ServiceDiscover.HOST_PROPERTY, "service-discover-endpoint");
    System.setProperty(ServiceDiscover.PORT_PROPERTY, "19000");
    final FilesConfig filesConfig = getLoadedConfig();

    Assertions.assertThat(filesConfig.getServiceDiscoverEndpoint())
        .isEqualTo("http://service-discover-endpoint:19000");
  }

  private static FilesConfig getLoadedConfig() throws IOException {
    // It would be best to avoid side effect caused by loadConfig, however
    // files depends on ServiceDiscover at startup of config, unlike Tasks,
    // so for now I kept the loadConfig() method
    FilesConfig filesConfig = new FilesConfig();
    filesConfig.loadConfig();
    return filesConfig;
  }
}
