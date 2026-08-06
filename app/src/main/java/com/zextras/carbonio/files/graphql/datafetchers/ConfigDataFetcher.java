// SPDX-FileCopyrightText: 2022 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.graphql.datafetchers;

import com.zextras.carbonio.files.Constants;
import com.zextras.carbonio.files.Constants.GraphQL;
import com.zextras.carbonio.files.Constants.ServiceDiscover.Config;
import com.zextras.carbonio.files.config.FilesConfig;
import com.zextras.carbonio.files.graphql.SyncCompletableFuture;
import graphql.execution.DataFetcherResult;
import graphql.schema.DataFetcher;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Contains all the implementations of {@link DataFetcher}s for all the queries and mutations
 * defined in the GraphQL schema that are related to the {@link Constants.GraphQL.Config} type.
 *
 * <p>Each {@link DataFetcher} implementation is asynchronous and returns a {@link List} of {@link
 * HashMap} containing the data fetched from configuration.
 *
 * <p>These {@link DataFetcher}s will be used by the GraphQL provider (wired in a later phase) where
 * they are bound with the related queries, mutations and composed attributes.
 */
@ApplicationScoped
public class ConfigDataFetcher {

  private final FilesConfig filesConfig;

  @Inject
  public ConfigDataFetcher(FilesConfig filesConfig) {
    this.filesConfig = filesConfig;
  }

  /**
   * This {@link DataFetcher} must be used for the {@link Constants.GraphQL.Queries#GET_CONFIGS}
   * query.
   *
   * <p>The request does not need any parameters in input.
   *
   * <h2>Behaviour:</h2>
   *
   * <p>It fetches the configs, if one of the configs is not found it will return the default value
   * for that config.
   *
   * @return an asynchronous {@link DataFetcher} containing a {@link List} with a {@link Map} in
   *     every entry.
   */
  public DataFetcher<CompletableFuture<List<DataFetcherResult<Map<String, String>>>>> getConfigs() {
    return environment ->
        SyncCompletableFuture.supplyAsync(
            () -> {
              List<DataFetcherResult<Map<String, String>>> result = new ArrayList<>();

              // Legacy parity: parsed inline with no try/catch, so a malformed override surfaces as
              // an
              // uncaught GraphQL execution error here (unlike the safe
              // FilesConfig#getMaxNumberOfVersions
              // used by the version-cap enforcement paths).
              String maxVersionsRaw = filesConfig.getMaxNumberOfVersionsRaw();
              int maxVersions = Integer.parseInt(maxVersionsRaw);
              result.add(convertConfigToGraphQLMap(Config.MAX_VERSIONS, maxVersionsRaw));

              result.add(
                  convertConfigToGraphQLMap(
                      Config.MAX_DOWNLOADABLE_SIZE_IN_MB,
                      filesConfig
                          .getMaxDownloadableFileSizeInMb()
                          .map(String::valueOf)
                          .orElse(null)));

              result.add(
                  convertConfigToGraphQLMap(
                      Config.MAX_UPLOADABLE_SIZE_IN_MB,
                      filesConfig
                          .getMaxUploadableFileSizeInMb()
                          .map(String::valueOf)
                          .orElse(null)));

              result.add(
                  convertConfigToGraphQLMap(
                      Config.MAX_KEEP_VERSIONS, computeMaxKeepVersions(maxVersions)));

              return result;
            });
  }

  private String computeMaxKeepVersions(int maxVersions) {
    return maxVersions <= Config.DIFF_MAX_VERSION_AND_MAX_KEEP_VERSION
        ? "0"
        : String.valueOf(maxVersions - Config.DIFF_MAX_VERSION_AND_MAX_KEEP_VERSION);
  }

  private DataFetcherResult<Map<String, String>> convertConfigToGraphQLMap(
      String key, String value) {
    Map<String, String> resultMap = new HashMap<>();
    resultMap.put(GraphQL.Config.NAME, key);
    resultMap.put(GraphQL.Config.VALUE, value);
    return DataFetcherResult.<Map<String, String>>newResult().data(resultMap).build();
  }
}
