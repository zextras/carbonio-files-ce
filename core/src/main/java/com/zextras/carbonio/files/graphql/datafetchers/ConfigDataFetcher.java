package com.zextras.carbonio.files.graphql.datafetchers;

import com.google.inject.Inject;
import com.zextras.carbonio.files.Files;
import com.zextras.carbonio.files.Files.ServiceDiscover;
import com.zextras.carbonio.files.Files.ServiceDiscover.Config;
import com.zextras.carbonio.files.Files.GraphQL;
import com.zextras.carbonio.files.clients.ServiceDiscoverHttpClient;
import com.zextras.carbonio.files.graphql.GraphQLProvider;
import graphql.execution.DataFetcherResult;
import graphql.schema.DataFetcher;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * <p>Contains all the implementations of {@link DataFetcher}s for all the queries and mutations
 * defined in the GraphQL schema that are related to the {@link Files.GraphQL.Config} type.</p>
 * <p>Each {@link DataFetcher} implementation is asynchronous and returns a {@link List} of
 * {@link HashMap} containing the data fetched from service discover.</p>
 * <p>These {@link DataFetcher}s will be used in the {@link GraphQLProvider} where they are bound
 * with the related queries, mutations and composed attributes.</p>
 */
public class ConfigDataFetcher {

  private final Map<String, String> configMap;
  private       String              maxKeepVersionsValue;

  /**
   * <p> This constructor initializes the map of config keys which values will be
   * requested at service discover. The value is initialized at the default value of every specific
   * configuration</p>
   */
  @Inject
  public ConfigDataFetcher() {
    configMap = new HashMap<>();
    configMap.put(Config.MAX_VERSIONS, String.valueOf(Config.DEFAULT_MAX_VERSIONS));
    maxKeepVersionsValue = String.valueOf(Config.DEFAULT_MAX_KEEP_VERSIONS);
  }

  /**
   * <p>This {@link DataFetcher} must be used for the {@link Files.GraphQL.Queries#GET_CONFIGS}
   * query.</p>
   * <p>The request does not need any parameters in input.</p>
   * <h2>Behaviour:</h2>
   * <p>It fetches the configs from service discover, if one of the configs is not found it will
   * return the default value for that config. </p>
   *
   * @return an asynchronous {@link DataFetcher} containing a {@link List} with a {@link  Map} in
   * every entry.
   */
  public DataFetcher<CompletableFuture<List<DataFetcherResult<Map<String, String>>>>> getConfigs() {
    return environment -> CompletableFuture.supplyAsync(() -> {
      List<DataFetcherResult<Map<String, String>>> result = new ArrayList<>();
      configMap.forEach((key, value) -> {
        String currValue = ServiceDiscoverHttpClient
          .defaultURL(ServiceDiscover.SERVICE_NAME)
          .getConfig(key)
          .getOrElse(value);
        result.add(convertConfigToGraphQLMap(key, currValue));
      });
      result.add(convertConfigToGraphQLMap(Config.MAX_KEEP_VERSIONS, maxKeepVersionsValue));
      return result;
    });
  }

  private DataFetcherResult<Map<String, String>> convertConfigToGraphQLMap(
    String key,
    String value
  ) {
    Map<String, String> resultMap = new HashMap<>();
    resultMap.put(
      GraphQL.Config.NAME,
      key
    );
    resultMap.put(
      GraphQL.Config.VALUE,
      value
    );
    // TODO: Federico, FIX ME PLEASE
    if (Config.MAX_VERSIONS.equals(key)) {
      maxKeepVersionsValue = Integer.parseInt(value) <= Config.DIFF_MAX_VERSION_AND_MAX_KEEP_VERSION
        ? "0"
        : String.valueOf(Integer.parseInt(value) - Config.DIFF_MAX_VERSION_AND_MAX_KEEP_VERSION);
    }
    return DataFetcherResult
      .<Map<String, String>>newResult()
      .data(resultMap)
      .build();
  }
}
