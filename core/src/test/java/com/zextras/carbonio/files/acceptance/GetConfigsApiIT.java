// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.acceptance;

import com.zextras.carbonio.files.TestUtils;
import com.zextras.carbonio.files.acceptance.seam.FilesTestApp;
import com.zextras.carbonio.files.acceptance.seam.impl.GuiceNettyFilesTestAppBuilder;
import com.zextras.carbonio.files.utilities.http.HttpRequest;
import com.zextras.carbonio.files.utilities.http.HttpResponse;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Task 5.1 of the acceptance coverage-expansion plan: {@code ConfigDataFetcher#getConfigs} (bound
 * to the {@code getConfigs} query).
 *
 * <p><b>Why this class does NOT use the usual static-app/{@code @BeforeAll} skeleton:</b> the two
 * scenarios below differ ONLY in the ServiceDiscover mock's BUILD-TIME state (whether {@code
 * max-number-of-versions} is stubbed at all, and to what value). {@code ConfigDataFetcher} re-hits
 * ServiceDiscover on every {@code getConfigs} call, but the only seam knob able to shape that
 * mock's KV response at all is {@code GuiceNettyFilesTestAppBuilder#withMaxNumberOfVersions(int)},
 * which must run BEFORE {@code build()} (see its own javadoc and {@code
 * Simulator#setMaxNumberOfVersions}). A single shared class-level app cannot host both the "happy"
 * and the "ServiceDiscover-down" scenario at once. This follows the existing precedent of {@code
 * HealthApiIT}/{@code MetricsApiIT}: a fresh, disposable {@code FilesTestApp} per test via
 * try-with-resources; no {@code @BeforeAll}/{@code @AfterEach}/{@code @AfterAll} is needed since
 * {@code getConfigs} touches no node/file/share state at all (nothing to reset between tests).
 *
 * <p><b>MISSING SEAM CAPABILITY for the plan's third scenario (reported, NOT built — see the task
 * instructions and plan §2.5):</b> a non-numeric {@code max-number-of-versions} value from
 * ServiceDiscover, which would make {@code ConfigDataFetcher#updateMaxKeepVersionsValue}'s uncaught
 * {@code Integer.parseInt(...)} throw, CANNOT be constructed with the seam as it exists today:
 *
 * <ul>
 *   <li>{@code GuiceNettyFilesTestAppBuilder#withMaxNumberOfVersions(int)} takes an {@code int} —
 *       there is no overload accepting an arbitrary/non-numeric {@code String};
 *   <li>{@code Simulator#serviceDiscoverMock} (the MockServer client that actually answers the KV
 *       GETs) has NO getter — unlike {@code getStoragesMock()}/{@code getPreviewMock()}/{@code
 *       getDocsConnectorMock()}/{@code getMailboxMock()}, there is no {@code
 *       getServiceDiscoverMock()}, so {@code GuiceNettyMocks} has no way to reach it at all;
 *   <li>consequently {@code Mocks} has no method to stub an arbitrary KV response (a raw string
 *       value, a non-200 status, or a deliberately-missing key) for ANY ServiceDiscover key — only
 *       the one hardwired "{@code max-number-of-versions} as a valid integer" path that {@code
 *       withMaxNumberOfVersions(int)} exposes.
 * </ul>
 *
 * <p><b>Recommended central addition</b> (per the task instructions, NOT added here without
 * approval): a general {@code Mocks#serviceDiscoverReturns(String key, String rawValue)}
 * (arbitrary/non-numeric string) and/or {@code Mocks#serviceDiscoverConfigDown(String key)} (force
 * a non-200/error response for one key), ideally backed by exposing {@code
 * Simulator#getServiceDiscoverMock()} the same way the other four dependency mocks already are.
 * With either in place, the expected assertion is already known from reading {@code
 * GraphQLProvider}'s unhandled-exception wrapping (the same mechanism verified empirically
 * elsewhere in this suite, e.g. {@code CloneVersionApiIT}'s {@code "Exception while fetching data
 * (/cloneVersion) : ..."}): a single GraphQL error, {@code "Exception while fetching data
 * (/getConfigs) : For input string: \"<value>\""}, with {@code data.getConfigs} absent — NOT
 * asserted below since it cannot be constructed honestly today.
 *
 * <p>Also documents a related, narrower finding: with the CURRENT seam, {@code
 * max-uploadable-size-in-mb}/{@code max-downloadable-size-in-mb} can NEVER be observed as anything
 * other than {@code null} — there is no builder knob or {@code Mocks} method that stubs either key
 * to a real ServiceDiscover value, so the "happy" and "ServiceDiscover-down" scenarios are
 * indistinguishable for these two keys specifically (both assert {@code null} below, honestly).
 */
class GetConfigsApiIT {

  private static final String GET_CONFIGS_QUERY = "query { getConfigs { name value } }";
  private static final String REQUESTER_ID = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";

  private static Map<String, String> toConfigMap(List<Map<String, Object>> configs) {
    Map<String, String> map = new HashMap<>();
    configs.forEach(config -> map.put((String) config.get("name"), (String) config.get("value")));
    return map;
  }

  private HttpResponse getConfigs(FilesTestApp app) {
    HttpRequest httpRequest =
        HttpRequest.of("POST", "/graphql/", "ZM_AUTH_TOKEN=fake-token", GET_CONFIGS_QUERY);
    return app.send(httpRequest);
  }

  @Test
  void givenAConfiguredMaxNumberOfVersionsGetConfigsShouldReturnItAndTheDerivedKeepCap() {
    // Given — max-number-of-versions stubbed at BUILD TIME to a value distinct from the hardcoded
    // 30 default, so a passing assertion actually proves the value came from ServiceDiscover and
    // not from the fallback default asserted in the sibling test below.
    try (FilesTestApp app =
        GuiceNettyFilesTestAppBuilder.aFilesTestApp()
            .withDatabase()
            .withServiceDiscover()
            .withMaxNumberOfVersions(12)
            .withUserManagement(Map.of("fake-token", REQUESTER_ID))
            .build()) {

      // When
      HttpResponse httpResponse = getConfigs(app);

      // Then
      Assertions.assertThat(httpResponse.getStatus()).isEqualTo(200);
      Assertions.assertThat(TestUtils.jsonResponseToErrors(httpResponse.getBodyPayload())).isEmpty();
      Map<String, String> configs =
          toConfigMap(TestUtils.jsonResponseToList(httpResponse.getBodyPayload(), "getConfigs"));

      Assertions.assertThat(configs)
          .containsEntry("max-number-of-versions", "12")
          // derived = configured - 2 (Constants.ServiceDiscover.Config.DIFF_MAX_VERSION_AND_MAX_KEEP_VERSION)
          .containsEntry("max-number-of-keep-versions", "10");

      // Documents the actual, ONLY achievable behaviour of these two keys with the current seam
      // (see class javadoc): every buildable configuration shows them as null.
      Assertions.assertThat(configs.get("max-uploadable-size-in-mb")).isNull();
      Assertions.assertThat(configs.get("max-downloadable-size-in-mb")).isNull();
    }
  }

  @Test
  void givenServiceDiscoverDoesNotServeTheseKeysGetConfigsShouldFallBackToDefaults() {
    // Given — ServiceDiscover mock started, but max-number-of-versions/size keys deliberately NOT
    // stubbed (withMaxNumberOfVersions is never called here): MockServer answers any unmatched GET
    // with its default 404, so ServiceDiscoverHttpClient#getConfig fails (Try.failure) for every
    // one of these keys — from this client's point of view this is indistinguishable from a real
    // ServiceDiscover outage or a genuinely-missing key.
    try (FilesTestApp app =
        GuiceNettyFilesTestAppBuilder.aFilesTestApp()
            .withDatabase()
            .withServiceDiscover()
            .withUserManagement(Map.of("fake-token", REQUESTER_ID))
            .build()) {

      // When
      HttpResponse httpResponse = getConfigs(app);

      // Then
      Assertions.assertThat(httpResponse.getStatus()).isEqualTo(200);
      Assertions.assertThat(TestUtils.jsonResponseToErrors(httpResponse.getBodyPayload())).isEmpty();
      Map<String, String> configs =
          toConfigMap(TestUtils.jsonResponseToList(httpResponse.getBodyPayload(), "getConfigs"));

      Assertions.assertThat(configs)
          .containsEntry("max-number-of-versions", "30")
          .containsEntry("max-number-of-keep-versions", "28");
      Assertions.assertThat(configs.get("max-uploadable-size-in-mb")).isNull();
      Assertions.assertThat(configs.get("max-downloadable-size-in-mb")).isNull();
    }
  }
}
