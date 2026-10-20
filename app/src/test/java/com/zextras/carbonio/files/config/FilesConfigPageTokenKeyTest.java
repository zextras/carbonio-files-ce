// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.zextras.carbonio.files.Constants.ServiceDiscover;
import com.zextras.carbonio.files.clients.ServiceDiscoverHttpClient;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class FilesConfigPageTokenKeyTest {

  private final ServiceDiscoverHttpClient consul = mock(ServiceDiscoverHttpClient.class);
  private final FilesConfig config = new FilesConfig(null, null, consul);

  @Test
  void givenAFailedReadTheDefaultKeyShouldBeUsedWithoutBeingKept() {
    when(consul.getConfig(ServiceDiscover.Config.PAGE_TOKEN_SECRET_KEY))
        .thenReturn(Optional.empty(), Optional.of("consul-key"));

    assertThat(config.getPageTokenSecretKey())
        .isEqualTo(ServiceDiscover.Config.DEFAULT_PAGE_TOKEN_SECRET_KEY);
    assertThat(config.getPageTokenSecretKey()).isEqualTo("consul-key");
    assertThat(config.getPageTokenSecretKey()).isEqualTo("consul-key");

    verify(consul, times(2)).getConfig(ServiceDiscover.Config.PAGE_TOKEN_SECRET_KEY);
  }
}
