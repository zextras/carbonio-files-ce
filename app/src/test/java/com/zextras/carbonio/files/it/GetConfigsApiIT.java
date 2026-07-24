// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.it;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;

import com.github.tomakehurst.wiremock.http.Fault;
import com.zextras.carbonio.files.FilesStackTestResource;
import com.zextras.carbonio.files.TestUtils;
import com.zextras.carbonio.files.it.support.AbstractFilesIT;
import io.restassured.response.Response;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * {@code com.zextras.carbonio.files.acceptance.GetConfigsApiIT} rewritten as an out-of-process
 * {@code @QuarkusIntegrationTest} on {@link AbstractFilesIT}. All 5 methods and their assertions
 * are preserved verbatim.
 *
 * <p><b>Why this class does NOT rebuild a fresh app per test (unlike the seam original):</b>
 * {@code @QuarkusIntegrationTest} launches ONE shared out-of-process app for the WHOLE suite, so
 * per-test app rebuilding (the seam's {@code withMaxNumberOfVersions(int)} boot-time knob) is not
 * available here. Instead this class relies on the documented fact that {@code
 * FilesConfig#getMaxNumberOfVersionsRaw()} is a LIVE, per-call Consul KV read (unlike the other
 * config accessors, which are boot-time snapshots) — so each scenario re-stubs the SAME Consul
 * WireMock ({@link FilesStackTestResource#getWireMock()}) at TEST TIME, right before the {@code
 * getConfigs} call, and {@link #resetConsulStubsAfterEach()} restores the baseline stubs afterward
 * so the next test (in this class or any other) is not affected.
 */
class GetConfigsApiIT extends AbstractFilesIT {

  private static final String GET_CONFIGS_QUERY = "query { getConfigs { name value } }";
  private static final String REQUESTER_ID = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
  private static final String REQUESTER_COOKIE = "ZM_AUTH_TOKEN=fake-token";
  private static final String MAX_VERSIONS_KV_PATH = "/v1/kv/carbonio-files/max-number-of-versions";

  @BeforeAll
  static void registerUsers() {
    FilesStackTestResource.getUserManagementService().registerToken("fake-token", REQUESTER_ID);
  }

  /** Restores the Consul WireMock to its baseline stubs after every test in this class. */
  @AfterEach
  void resetConsulStubsAfterEach() {
    FilesStackTestResource.resetConsulStubs();
  }

  private static Map<String, String> toConfigMap(List<Map<String, Object>> configs) {
    Map<String, String> map = new HashMap<>();
    configs.forEach(config -> map.put((String) config.get("name"), (String) config.get("value")));
    return map;
  }

  private Response getConfigs() {
    return graphql(GET_CONFIGS_QUERY, REQUESTER_COOKIE);
  }

  /** Stubs the LIVE {@code carbonio-files/max-number-of-versions} Consul KV entry to {@code rawValue}. */
  private static void stubMaxNumberOfVersions(String rawValue) {
    String base64Value = Base64.getEncoder().encodeToString(rawValue.getBytes(StandardCharsets.UTF_8));
    String body =
        "[{\"LockIndex\":0,\"Key\":\"carbonio-files/max-number-of-versions\",\"Flags\":0,\"Value\":\""
            + base64Value
            + "\",\"CreateIndex\":1,\"ModifyIndex\":1}]";
    FilesStackTestResource.getWireMock()
        .stubFor(
            get(urlPathEqualTo(MAX_VERSIONS_KV_PATH))
                .atPriority(1)
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody(body)));
  }

  /** Simulates a Consul connection-level outage for the live KV read (distinct from "key not stubbed" = 404). */
  private static void stubMaxNumberOfVersionsConnectionDrop() {
    FilesStackTestResource.getWireMock()
        .stubFor(
            get(urlPathEqualTo(MAX_VERSIONS_KV_PATH))
                .atPriority(1)
                .willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));
  }

  @Test
  void givenAConfiguredMaxNumberOfVersionsGetConfigsShouldReturnItAndTheDerivedKeepCap() {
    // Given — max-number-of-versions stubbed, LIVE, to a value distinct from the hardcoded 30
    // default, so a passing assertion actually proves the value came from ServiceDiscover and not
    // from the fallback default asserted in the sibling test below.
    stubMaxNumberOfVersions("12");

    // When
    Response response = getConfigs();

    // Then
    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    Assertions.assertThat(TestUtils.jsonResponseToErrors(response.getBody().asString())).isEmpty();
    Map<String, String> configs = toConfigMap(TestUtils.jsonResponseToList(response.getBody().asString(), "getConfigs"));

    Assertions.assertThat(configs)
        .containsEntry("max-number-of-versions", "12")
        // derived = configured - 2 (Constants.ServiceDiscover.Config.DIFF_MAX_VERSION_AND_MAX_KEEP_VERSION)
        .containsEntry("max-number-of-keep-versions", "10");

    // Documents the actual, ONLY achievable behaviour of these two keys with the current harness
    // (see class javadoc): every buildable configuration shows them as null.
    Assertions.assertThat(configs.get("max-uploadable-size-in-mb")).isNull();
    Assertions.assertThat(configs.get("max-downloadable-size-in-mb")).isNull();
  }

  @Test
  void givenServiceDiscoverDoesNotServeTheseKeysGetConfigsShouldFallBackToDefaults() {
    // Given — max-number-of-versions/size keys deliberately NOT stubbed: the Consul WireMock's
    // baseline catch-all answers any unmatched KV path with its default 404, so
    // ServiceDiscoverHttpClient#getConfig fails (empty Optional) for every one of these keys —
    // indistinguishable, from this client's point of view, from a real ServiceDiscover outage or a
    // genuinely-missing key.

    // When
    Response response = getConfigs();

    // Then
    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    Assertions.assertThat(TestUtils.jsonResponseToErrors(response.getBody().asString())).isEmpty();
    Map<String, String> configs = toConfigMap(TestUtils.jsonResponseToList(response.getBody().asString(), "getConfigs"));

    Assertions.assertThat(configs)
        .containsEntry("max-number-of-versions", "30")
        .containsEntry("max-number-of-keep-versions", "28");
    Assertions.assertThat(configs.get("max-uploadable-size-in-mb")).isNull();
    Assertions.assertThat(configs.get("max-downloadable-size-in-mb")).isNull();
  }

  /**
   * Closes {@code ConfigDataFetcher#updateMaxKeepVersionsValue}'s missing branch: both scenarios
   * above only ever exercise {@code maxVersions > DIFF_MAX_VERSION_AND_MAX_KEEP_VERSION} (12-2=10
   * and 30-2=28). At-or-below the diff (2), the derived cap must clamp to {@code "0"} instead of
   * going negative.
   */
  @Test
  void givenMaxNumberOfVersionsAtTheDiffThenDerivedKeepCapClampsToZero() {
    stubMaxNumberOfVersions("2");

    Response response = getConfigs();

    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    Assertions.assertThat(TestUtils.jsonResponseToErrors(response.getBody().asString())).isEmpty();
    Map<String, String> configs = toConfigMap(TestUtils.jsonResponseToList(response.getBody().asString(), "getConfigs"));

    Assertions.assertThat(configs)
        .containsEntry("max-number-of-versions", "2")
        .containsEntry("max-number-of-keep-versions", "0");
  }

  /**
   * Closes {@code ConfigDataFetcher.getConfigs}'s uncaught-{@code NumberFormatException} finding:
   * a non-numeric ServiceDiscover value for {@code max-number-of-versions} is never caught, so the
   * whole {@code getConfigs} data fetcher fails and surfaces as a single generic GraphQL execution
   * error, NOT a 500 or a per-field error.
   *
   * <p>A warm-up {@code getConfigs} call runs before the bad value is stubbed, preserving the
   * original seam test's ordering precaution (its target JVM's {@code NodeDataFetcher} did an
   * eager, unguarded {@code Integer.parseInt} on this exact key at CONSTRUCTION time under the old
   * Guice stack); harmless here regardless since the Quarkus {@code NodeDataFetcher} constructor
   * does no such eager parsing.
   */
  @Test
  void givenNonNumericMaxNumberOfVersionsGetConfigsShouldSurfaceAnExecutionError() {
    // Warm-up: exercised while the key is still unstubbed (Consul mock answers 404 -> the safe default).
    Assertions.assertThat(getConfigs().getStatusCode()).isEqualTo(200);

    stubMaxNumberOfVersions("not-a-number");

    Response response = getConfigs();

    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    List<String> errors = TestUtils.jsonResponseToErrors(response.getBody().asString());
    Assertions.assertThat(errors)
        .hasSize(1)
        .containsExactly("Exception while fetching data (/getConfigs) : For input string: \"not-a-number\"");
    Assertions.assertThat(TestUtils.jsonResponseToValue(response.getBody().asString(), "getConfigs")).isEmpty();
  }

  /**
   * Closes {@code ServiceDiscoverHttpClient#getConfig}'s {@code catch (Exception)} branch: a
   * connection-level outage (distinct from the "key never stubbed" scenario above, which only
   * ever exercises the "non-200 status" branch) is caught and falls back to the same default,
   * exactly like any other failure.
   */
  @Test
  void givenServiceDiscoverConnectionDropsGetConfigsShouldStillFallBackToDefaults() {
    stubMaxNumberOfVersionsConnectionDrop();

    Response response = getConfigs();

    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    Assertions.assertThat(TestUtils.jsonResponseToErrors(response.getBody().asString())).isEmpty();
    Map<String, String> configs = toConfigMap(TestUtils.jsonResponseToList(response.getBody().asString(), "getConfigs"));

    Assertions.assertThat(configs)
        .containsEntry("max-number-of-versions", "30")
        .containsEntry("max-number-of-keep-versions", "28");
  }
}
