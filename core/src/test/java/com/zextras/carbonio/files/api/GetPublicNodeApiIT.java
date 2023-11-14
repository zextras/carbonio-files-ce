// SPDX-FileCopyrightText: 2023 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.api;

import com.zextras.carbonio.files.Simulator;
import com.zextras.carbonio.files.Simulator.SimulatorBuilder;
import com.zextras.carbonio.files.TestUtils;
import com.zextras.carbonio.files.utilities.http.HttpRequest;
import com.zextras.carbonio.files.utilities.http.HttpResponse;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

public class GetPublicNodeApiIT {

  static Simulator simulator;

  @BeforeAll
  static void init() {
    simulator =
        SimulatorBuilder.aSimulator().init().withDatabase().withServiceDiscover().build().start();
  }

  @AfterAll
  static void cleanUpAll() {
    simulator.stopAll();
  }

  @Test
  void givenAPublicLinkIdAndAnExistingNodeTheGetPublicNodeShouldReturnTheNode() {
    // Given
    final String bodyPayload =
        "query { "
            + "getPublicNode(node_link_id: \\\"abcd1234abcd1234abcd1234abcd1234\\\") { "
            + "id "
            + "created_at "
            + "updated_at "
            + "name "
            + "type "
            + "} "
            + "}";
    final HttpRequest httpRequest = HttpRequest.of("POST", "/public/graphql/", null, bodyPayload);

    // When
    HttpResponse httpResponse = TestUtils.sendRequest(httpRequest, simulator.getNettyChannel());

    // Then
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(200);
    Assertions.assertThat(
            TestUtils.jsonResponseToMap(httpResponse.getBodyPayload(), "getPublicNode"))
        .isEmpty();
  }
}
