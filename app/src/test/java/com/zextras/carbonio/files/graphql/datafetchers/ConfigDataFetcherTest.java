// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.graphql.datafetchers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.zextras.carbonio.files.Constants.ServiceDiscover.Config;
import com.zextras.carbonio.files.config.FilesConfig;
import graphql.execution.DataFetcherResult;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Plain JUnit (NOT {@code @QuarkusTest}) unit test for {@link ConfigDataFetcher#getConfigs}, mocking
 * {@link FilesConfig} so the raw {@code max-number-of-versions} value can be varied per test.
 *
 * <p>This is where the PARSING/DERIVATION coverage lives: it varies the raw {@code
 * max-number-of-versions} value directly, with no Consul in the loop. The out-of-process {@code
 * GetConfigsApiIT} only exercises the harness default (30 -&gt; 28) because its Consul WireMock stub
 * serves no {@code max-number-of-versions} key, so the configured (versions -&gt; keep = versions -
 * DIFF), the at-or-below-the-diff clamp-to-"0", and the non-numeric -&gt; GraphQL execution error
 * cases are asserted directly against the fetcher here.
 */
class ConfigDataFetcherTest {

  private FilesConfig filesConfig;
  private ConfigDataFetcher configDataFetcher;

  @BeforeEach
  void setUp() {
    filesConfig = mock(FilesConfig.class);
    configDataFetcher = new ConfigDataFetcher(filesConfig);
  }

  private Map<String, String> fetchConfigs() throws Exception {
    List<DataFetcherResult<Map<String, String>>> results =
        configDataFetcher.getConfigs().get(null).join();
    Map<String, String> configs = new HashMap<>();
    results.forEach(result -> configs.put(result.getData().get("name"), result.getData().get("value")));
    return configs;
  }

  @Test
  void getConfigsDerivesKeepCapAsMaxVersionsMinusTheDiff() throws Exception {
    when(filesConfig.getMaxNumberOfVersionsRaw()).thenReturn("12");

    Map<String, String> configs = fetchConfigs();

    assertThat(configs)
        .containsEntry(Config.MAX_VERSIONS, "12")
        .containsEntry(Config.MAX_KEEP_VERSIONS, "10");
  }

  @Test
  void getConfigsClampsKeepCapToZeroWhenMaxVersionsAtOrBelowTheDiff() throws Exception {
    when(filesConfig.getMaxNumberOfVersionsRaw()).thenReturn("2");

    Map<String, String> configs = fetchConfigs();

    assertThat(configs)
        .containsEntry(Config.MAX_VERSIONS, "2")
        .containsEntry(Config.MAX_KEEP_VERSIONS, "0");
  }

  @Test
  void getConfigsSurfacesExecutionErrorForNonNumericMaxVersions() throws Exception {
    when(filesConfig.getMaxNumberOfVersionsRaw()).thenReturn("not-a-number");

    CompletableFuture<List<DataFetcherResult<Map<String, String>>>> future =
        configDataFetcher.getConfigs().get(null);

    assertThat(future).isCompletedExceptionally();
    assertThatThrownBy(future::join)
        .isInstanceOf(CompletionException.class)
        .hasCauseInstanceOf(NumberFormatException.class)
        .hasMessageContaining("not-a-number");
  }
}
