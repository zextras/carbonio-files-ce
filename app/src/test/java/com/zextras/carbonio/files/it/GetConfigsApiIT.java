// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.it;

import com.zextras.carbonio.files.FilesStackTestResource;
import com.zextras.carbonio.files.TestUtils;
import com.zextras.carbonio.files.it.support.AbstractFilesIT;
import io.restassured.response.Response;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Out-of-process {@code @QuarkusIntegrationTest} (on {@link AbstractFilesIT}) for the {@code
 * getConfigs} query.
 *
 * <p><b>Why this asserts the default path:</b> {@code FilesConfig#getMaxNumberOfVersionsRaw()}
 * reads the CURRENT (live) value via {@code ApplicationConfigService} (extension 1.13.0-1 keeps the
 * Consul KV view live). The harness Consul WireMock ({@link FilesStackTestResource}) serves only DB
 * credentials on its root {@code /v1/kv/?recurse} stub — no {@code max-number-of-versions} key — so
 * the live snapshot never carries that key and this query always reports the default (30), with the
 * derived keep-cap being 30 - {@code DIFF_MAX_VERSION_AND_MAX_KEEP_VERSION}(2) = 28.
 *
 * <p>The parsing/derivation behaviour (configured value -&gt; keep = versions - DIFF,
 * at-or-below-the- diff clamp to "0", non-numeric value -&gt; GraphQL execution error) is asserted
 * directly, with no Consul in the loop, by the in-process unit test {@code ConfigDataFetcherTest},
 * which mocks {@code FilesConfig} to vary the raw value.
 */
class GetConfigsApiIT extends AbstractFilesIT {

  private static final String GET_CONFIGS_QUERY = "query { getConfigs { name value } }";
  private static final String REQUESTER_ID = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
  private static final String REQUESTER_COOKIE = "ZM_AUTH_TOKEN=fake-token";

  @BeforeAll
  static void registerUsers() {
    FilesStackTestResource.getUserManagementService().registerToken("fake-token", REQUESTER_ID);
  }

  private static Map<String, String> toConfigMap(List<Map<String, Object>> configs) {
    Map<String, String> map = new HashMap<>();
    configs.forEach(config -> map.put((String) config.get("name"), (String) config.get("value")));
    return map;
  }

  private Response getConfigs() {
    return graphql(GET_CONFIGS_QUERY, REQUESTER_COOKIE);
  }

  @Test
  void getConfigsReturnsDefaultsForVersionKeysAndNullForSizeKeys() {
    Response response = getConfigs();

    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    Assertions.assertThat(TestUtils.jsonResponseToErrors(response.getBody().asString())).isEmpty();
    Map<String, String> configs =
        toConfigMap(TestUtils.jsonResponseToList(response.getBody().asString(), "getConfigs"));

    Assertions.assertThat(configs)
        .containsEntry("max-number-of-versions", "30")
        .containsEntry("max-number-of-keep-versions", "28");
    Assertions.assertThat(configs.get("max-uploadable-size-in-mb")).isNull();
    Assertions.assertThat(configs.get("max-downloadable-size-in-mb")).isNull();
  }
}
