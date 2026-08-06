// SPDX-FileCopyrightText: 2022 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.graphql.datafetchers;

import com.google.inject.Inject;
import com.zextras.carbonio.files.Constants;
import com.zextras.carbonio.files.Constants.GraphQL;
import com.zextras.carbonio.files.Constants.ServiceDiscover;
import com.zextras.carbonio.files.Constants.ServiceDiscover.Config;
import com.zextras.carbonio.files.clients.ServiceDiscoverHttpClient;
import com.zextras.carbonio.files.config.FilesConfig;
import com.zextras.carbonio.files.graphql.GraphQLProvider;
import graphql.execution.DataFetcherResult;
import graphql.schema.DataFetcher;
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
 * HashMap} containing the data fetched from service discover.
 *
 * <p>These {@link DataFetcher}s will be used in the {@link GraphQLProvider} where they are bound
 * with the related queries, mutations and composed attributes.
 */
public class ConfigDataFetcher {

  private final Map<String, String> configMap;
  private final FilesConfig filesConfig;
  private String maxKeepVersionsValue;

  /**
   * This constructor initializes the map of config keys which values will be requested at service
   * discover. The value is initialized at the default value of every specific configuration
   */
  @Inject
  public ConfigDataFetcher(FilesConfig filesConfig) {
    this.filesConfig = filesConfig;
    configMap = new HashMap<>();
    configMap.put(Config.MAX_VERSIONS, String.valueOf(Config.DEFAULT_MAX_VERSIONS));
    configMap.put(Config.MAX_DOWNLOADABLE_SIZE_IN_MB, null);
    configMap.put(Config.MAX_UPLOADABLE_SIZE_IN_MB, null);
    maxKeepVersionsValue = String.valueOf(Config.DEFAULT_MAX_KEEP_VERSIONS);
  }

  /**
   * This {@link DataFetcher} must be used for the {@link Constants.GraphQL.Queries#GET_CONFIGS}
   * query.
   *
   * <p>The request does not need any parameters in input.
   *
   * <h2>Behaviour:</h2>
   *
   * <p>It fetches the configs from service discover, if one of the configs is not found it will
   * return the default value for that config.
   *
   * @return an asynchronous {@link DataFetcher} containing a {@link List} with a {@link Map} in
   *     every entry.
   */
  public DataFetcher<CompletableFuture<List<DataFetcherResult<Map<String, String>>>>> getConfigs() {
    return environment ->
        CompletableFuture.supplyAsync(
            () -> {
              List<DataFetcherResult<Map<String, String>>> result = new ArrayList<>();
              configMap.forEach(
                  (key, value) -> {
                    String currValue =
                        ServiceDiscoverHttpClient.atURL(
                                filesConfig.getServiceDiscoverEndpoint(),
                                ServiceDiscover.SERVICE_NAME)
                            .getConfig(key)
                            .getOrElse(value);

                    if (Config.MAX_VERSIONS.equals(key)) {
                      updateMaxKeepVersionsValue(currValue);
                    }

                    result.add(convertConfigToGraphQLMap(key, currValue));
                  });
              result.add(convertConfigToGraphQLMap(Config.MAX_KEEP_VERSIONS, maxKeepVersionsValue));
              return result;
            });
  }

  private void updateMaxKeepVersionsValue(String maxVersionsValue) {
    int maxVersions = Integer.parseInt(maxVersionsValue);
    maxKeepVersionsValue =
        maxVersions <= Config.DIFF_MAX_VERSION_AND_MAX_KEEP_VERSION
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
