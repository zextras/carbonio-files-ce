// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.graphql.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.zextras.carbonio.files.Constants.ServiceDiscover.Config;
import com.zextras.carbonio.files.config.FilesConfig;
import com.zextras.carbonio.files.graphql.model.ConfigModel;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ConfigApiTest {

  private FilesConfig filesConfig;
  private ConfigApi configApi;

  @BeforeEach
  void setUp() {
    filesConfig = mock(FilesConfig.class);
    configApi = new ConfigApi();
    configApi.filesConfig = filesConfig;
  }

  private Map<String, String> callGetConfigs() {
    List<ConfigModel> models = configApi.getConfigs();
    return models.stream()
        .collect(
            Collectors.toMap(
                ConfigModel::getName, m -> m.getValue() != null ? m.getValue() : "__null__"));
  }

  @Test
  void getConfigsReturnsFourEntries() {
    when(filesConfig.getMaxNumberOfVersionsRaw()).thenReturn("30");
    when(filesConfig.getMaxDownloadableFileSizeInMb()).thenReturn(Optional.of(512));
    when(filesConfig.getMaxUploadableFileSizeInMb()).thenReturn(Optional.of(1024));

    List<ConfigModel> result = configApi.getConfigs();

    assertThat(result).hasSize(4);
  }

  @Test
  void getConfigsDerivesMaxKeepVersionsAsMaxVersionsMinusDiff() {
    when(filesConfig.getMaxNumberOfVersionsRaw()).thenReturn("30");
    when(filesConfig.getMaxDownloadableFileSizeInMb()).thenReturn(Optional.empty());
    when(filesConfig.getMaxUploadableFileSizeInMb()).thenReturn(Optional.empty());

    Map<String, String> configs = callGetConfigs();

    assertThat(configs)
        .containsEntry(Config.MAX_VERSIONS, "30")
        .containsEntry(Config.MAX_KEEP_VERSIONS, "28");
  }

  @Test
  void getConfigsClampsMaxKeepVersionsToZeroWhenAtOrBelowDiff() {
    when(filesConfig.getMaxNumberOfVersionsRaw()).thenReturn("2");
    when(filesConfig.getMaxDownloadableFileSizeInMb()).thenReturn(Optional.empty());
    when(filesConfig.getMaxUploadableFileSizeInMb()).thenReturn(Optional.empty());

    Map<String, String> configs = callGetConfigs();

    assertThat(configs)
        .containsEntry(Config.MAX_VERSIONS, "2")
        .containsEntry(Config.MAX_KEEP_VERSIONS, "0");
  }

  @Test
  void getConfigsReturnsNullSizeValuesWhenOptionalEmpty() {
    when(filesConfig.getMaxNumberOfVersionsRaw()).thenReturn("10");
    when(filesConfig.getMaxDownloadableFileSizeInMb()).thenReturn(Optional.empty());
    when(filesConfig.getMaxUploadableFileSizeInMb()).thenReturn(Optional.empty());

    List<ConfigModel> result = configApi.getConfigs();

    Map<String, String> nullableValues =
        result.stream()
            .filter(
                m ->
                    m.getName().equals(Config.MAX_DOWNLOADABLE_SIZE_IN_MB)
                        || m.getName().equals(Config.MAX_UPLOADABLE_SIZE_IN_MB))
            .collect(
                Collectors.toMap(
                    ConfigModel::getName, m -> m.getValue() == null ? "null" : m.getValue()));

    assertThat(nullableValues)
        .containsEntry(Config.MAX_DOWNLOADABLE_SIZE_IN_MB, "null")
        .containsEntry(Config.MAX_UPLOADABLE_SIZE_IN_MB, "null");
  }

  @Test
  void getConfigsReturnsCorrectSizeValues() {
    when(filesConfig.getMaxNumberOfVersionsRaw()).thenReturn("10");
    when(filesConfig.getMaxDownloadableFileSizeInMb()).thenReturn(Optional.of(256));
    when(filesConfig.getMaxUploadableFileSizeInMb()).thenReturn(Optional.of(512));

    Map<String, String> configs = callGetConfigs();

    assertThat(configs)
        .containsEntry(Config.MAX_DOWNLOADABLE_SIZE_IN_MB, "256")
        .containsEntry(Config.MAX_UPLOADABLE_SIZE_IN_MB, "512");
  }
}
