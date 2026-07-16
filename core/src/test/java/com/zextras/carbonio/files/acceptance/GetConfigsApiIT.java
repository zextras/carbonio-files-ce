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
 * <p><b>Gap-closing pass addition:</b> the plan's third scenario (a non-numeric {@code
 * max-number-of-versions} value from ServiceDiscover, which makes {@code
 * ConfigDataFetcher#updateMaxKeepVersionsValue}'s uncaught {@code Integer.parseInt(...)} throw)
 * previously could not be constructed — {@code Simulator} exposed no {@code
 * getServiceDiscoverMock()} and {@code Mocks} had no way to stub an arbitrary KV value. Both gaps
 * are now closed: {@code Simulator#getServiceDiscoverMock()} mirrors the other four dependency
 * mock getters, and {@code Mocks#serviceDiscoverReturns(String, String)}/{@code
 * #serviceDiscoverConfigDown(String)} stub an arbitrary raw KV value / a connection-level outage
 * for any key. See {@link #givenNonNumericMaxNumberOfVersionsGetConfigsShouldSurfaceAnExecutionError()}
 * and {@link #givenServiceDiscoverConnectionDropsGetConfigsShouldStillFallBackToDefaults()} below.
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

  /**
   * Closes {@code ConfigDataFetcher#updateMaxKeepVersionsValue}'s missing branch: both scenarios
   * above only ever exercise {@code maxVersions > DIFF_MAX_VERSION_AND_MAX_KEEP_VERSION} (12-2=10
   * and 30-2=28). At-or-below the diff (2), the derived cap must clamp to {@code "0"} instead of
   * going negative.
   */
  @Test
  void givenMaxNumberOfVersionsAtTheDiffThenDerivedKeepCapClampsToZero() {
    try (FilesTestApp app =
        GuiceNettyFilesTestAppBuilder.aFilesTestApp()
            .withDatabase()
            .withServiceDiscover()
            .withMaxNumberOfVersions(2)
            .withUserManagement(Map.of("fake-token", REQUESTER_ID))
            .build()) {

      HttpResponse httpResponse = getConfigs(app);

      Assertions.assertThat(httpResponse.getStatus()).isEqualTo(200);
      Assertions.assertThat(TestUtils.jsonResponseToErrors(httpResponse.getBodyPayload())).isEmpty();
      Map<String, String> configs =
          toConfigMap(TestUtils.jsonResponseToList(httpResponse.getBodyPayload(), "getConfigs"));

      Assertions.assertThat(configs)
          .containsEntry("max-number-of-versions", "2")
          .containsEntry("max-number-of-keep-versions", "0");
    }
  }

  /**
   * Closes {@code ConfigDataFetcher.getConfigs}'s uncaught-{@code NumberFormatException} finding
   * (§9): a non-numeric ServiceDiscover value for {@code max-number-of-versions} is never caught,
   * so the whole {@code getConfigs} data fetcher fails and surfaces as a single generic GraphQL
   * execution error (the same wrapping mechanism verified elsewhere in this suite, e.g. {@code
   * CloneVersionApiIT}'s {@code "Exception while fetching data (/cloneVersion) : ..."}), NOT a 500
   * or a per-field error.
   *
   * <p><b>Ordering finding:</b> the bad value must be stubbed AFTER a warm-up {@code getConfigs}
   * call, not before. {@code NodeDataFetcher}'s constructor ALSO does an eager, unguarded {@code
   * Integer.parseInt} on this exact same key (see plan §2.4/Task 0.3) — but only once, the first
   * time Guice lazily instantiates it for this {@code FilesTestApp}. Stubbing the bad value before
   * the very first request makes NodeDataFetcher's OWN construction crash instead (a {@code
   * ProvisionException} out of {@code injector.getInstance(...)}, not a graceful GraphQL error) —
   * empirically confirmed, and a more severe sibling finding than the one this test targets. The
   * warm-up forces NodeDataFetcher to construct first (with a valid/default value), isolating the
   * one behaviour this test documents: {@code ConfigDataFetcher} re-reads this key LIVE on every
   * call and has no such guard.
   */
  @Test
  void givenNonNumericMaxNumberOfVersionsGetConfigsShouldSurfaceAnExecutionError() {
    try (FilesTestApp app =
        GuiceNettyFilesTestAppBuilder.aFilesTestApp()
            .withDatabase()
            .withServiceDiscover()
            .withUserManagement(Map.of("fake-token", REQUESTER_ID))
            .build()) {
      // Warm-up: forces NodeDataFetcher's construction now, while the key is still unstubbed
      // (ServiceDiscover mock answers 404 -> the safe default).
      Assertions.assertThat(getConfigs(app).getStatus()).isEqualTo(200);

      app.mocks().serviceDiscoverReturns("max-number-of-versions", "not-a-number");

      HttpResponse httpResponse = getConfigs(app);

      Assertions.assertThat(httpResponse.getStatus()).isEqualTo(200);
      List<String> errors = TestUtils.jsonResponseToErrors(httpResponse.getBodyPayload());
      Assertions.assertThat(errors)
          .hasSize(1)
          .containsExactly(
              "Exception while fetching data (/getConfigs) : For input string: \"not-a-number\"");
      Assertions.assertThat(TestUtils.jsonResponseToValue(httpResponse.getBodyPayload(), "getConfigs"))
          .isEmpty();
    }
  }

  /**
   * Closes {@code ServiceDiscoverHttpClient#getConfig}'s {@code catch (IOException)} branch: a
   * connection-level outage (distinct from the "key never stubbed" scenario above, which only
   * ever exercises the "non-200 status" branch since MockServer answers unmatched paths with a
   * plain 404) is caught and wrapped into a {@code Try.failure}, which {@code ConfigDataFetcher}
   * treats identically to any other failure — falling back to the same default.
   */
  @Test
  void givenServiceDiscoverConnectionDropsGetConfigsShouldStillFallBackToDefaults() {
    try (FilesTestApp app =
        GuiceNettyFilesTestAppBuilder.aFilesTestApp()
            .withDatabase()
            .withServiceDiscover()
            .withUserManagement(Map.of("fake-token", REQUESTER_ID))
            .build()) {
      app.mocks().serviceDiscoverConfigDown("max-number-of-versions");

      HttpResponse httpResponse = getConfigs(app);

      Assertions.assertThat(httpResponse.getStatus()).isEqualTo(200);
      Assertions.assertThat(TestUtils.jsonResponseToErrors(httpResponse.getBodyPayload())).isEmpty();
      Map<String, String> configs =
          toConfigMap(TestUtils.jsonResponseToList(httpResponse.getBodyPayload(), "getConfigs"));

      Assertions.assertThat(configs)
          .containsEntry("max-number-of-versions", "30")
          .containsEntry("max-number-of-keep-versions", "28");
    }
  }
}
