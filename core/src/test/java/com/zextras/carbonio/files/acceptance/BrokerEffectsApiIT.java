// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.acceptance;

import com.zextras.carbonio.files.TestUtils;
import com.zextras.carbonio.files.acceptance.seam.FilesTestApp;
import com.zextras.carbonio.files.acceptance.seam.impl.GuiceNettyFilesTestAppBuilder;
import com.zextras.carbonio.files.api.utilities.GraphqlCommandBuilder;
import com.zextras.carbonio.files.api.utilities.entities.SimplePopulatorTextFile;
import com.zextras.carbonio.files.utilities.http.HttpRequest;
import com.zextras.carbonio.files.utilities.http.HttpResponse;
import java.util.List;
import java.util.Map;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Task 6.2 of the acceptance coverage-expansion plan: {@code message_broker.consumers.*}, driven
 * through the APPROVED non-HTTP backdoors {@code backdoor().injectUserStatusChanged(userId,
 * status)} and {@code backdoor().injectMaxVersionNumberChanged(int)} (see plan §2.5/§10). Neither
 * consumer has a real RabbitMQ trigger reachable from this suite; both backdoors mirror the
 * white-box {@code message_broker.it.UserStatusChangedIT}/{@code MaxVersionNumberChangedIT} by
 * constructing the consumer directly and calling {@code doHandle(...)} — no {@code
 * .withMessageBroker()} builder knob is needed since the real broker connection is never touched.
 * Effects are asserted purely via the public HTTP/GraphQL contract ({@code findNodes}, {@code
 * GET /download/{id}/{version}}) and the neutral {@code remainingVersionNumbers} inspector.
 *
 * <p><b>FINDING (confirms/refines the plan's mapping):</b> {@code UserStatusChangedConsumer}'s
 * hide/unhide is NOT a 3-way switch keyed on the literal status string — {@code
 * shouldNodesHideByUserStatus} only special-cases {@code CLOSED} (hide); EVERY other status
 * (including {@code ACTIVE} and {@code MAINTENANCE}) maps to the same "should be visible" value.
 * The consumer is also a TOGGLE, not a set: {@code shouldChangeHiddenFlag} inspects one arbitrary
 * node owned by the user and only flips ALL of the user's nodes if that one node's current hidden
 * flag disagrees with the new status's implied value. So "{@code MAINTENANCE} -> unchanged" is
 * only true starting from a non-hidden baseline (the normal, freshly-seeded case exercised below);
 * sending {@code MAINTENANCE} right after a {@code CLOSED} would unhide nodes exactly like {@code
 * ACTIVE} would, since both are simply "not {@code CLOSED}". Not fixed (Phase-1 forbids {@code
 * src/main} changes) — documented here and worth a ticket.
 */
class BrokerEffectsApiIT {

  static FilesTestApp app;

  private static final String OWNER_ID = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
  private static final String OWNER_COOKIE = "ZM_AUTH_TOKEN=fake-token";

  @BeforeAll
  static void init() {
    app =
        GuiceNettyFilesTestAppBuilder.aFilesTestApp()
            .withDatabase()
            .withServiceDiscover()
            .withStorages()
            .withUserManagement(Map.of("fake-token", OWNER_ID))
            .build();
  }

  @AfterEach
  void cleanUp() {
    app.backdoor().resetDatabase();
    app.mocks().reset();
  }

  @AfterAll
  static void cleanUpAll() {
    app.close();
  }

  @SuppressWarnings("unchecked")
  private List<String> findNodeIdsOwnedByRequester() {
    String bodyPayload =
        GraphqlCommandBuilder.aQueryBuilder("findNodes")
            .withString("folder_id", "LOCAL_ROOT")
            .withBoolean("cascade", true)
            .withInteger("limit", 20)
            .withWantedResultFormat("{ nodes { id }, page_token }")
            .build();
    HttpResponse httpResponse =
        app.send(HttpRequest.of("POST", "/graphql/", OWNER_COOKIE, bodyPayload));
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(200);

    Map<String, Object> page = TestUtils.jsonResponseToMap(httpResponse.getBodyPayload(), "findNodes");
    List<Map<String, Object>> nodes = (List<Map<String, Object>>) page.get("nodes");
    return nodes.stream().map(node -> (String) node.get("id")).toList();
  }

  private HttpResponse download(String nodeId, int version) {
    return app.send(HttpRequest.of("GET", "/download/" + nodeId + "/" + version, OWNER_COOKIE, null));
  }

  // --- UserStatusChanged: CLOSED hides, ACTIVE unhides, MAINTENANCE is a no-op ------------

  @Test
  void givenAVisibleNodeUserStatusChangedClosedThenNodeExcludedFromFindNodes() {
    // Given
    String fileId = "00000000-0000-0000-0000-600000000101";
    app.backdoor().populator().addNode(new SimplePopulatorTextFile(fileId, OWNER_ID, "a.txt"));
    Assertions.assertThat(findNodeIdsOwnedByRequester()).contains(fileId);

    // When
    app.backdoor().injectUserStatusChanged(OWNER_ID, "CLOSED");

    // Then — SearchBuilder's base query unconditionally excludes hidden nodes.
    Assertions.assertThat(findNodeIdsOwnedByRequester()).doesNotContain(fileId);
  }

  @Test
  void givenANodeHiddenByAPriorClosedEventUserStatusChangedActiveThenNodeVisibleAgainInFindNodes() {
    // Given — get to the hidden state the only way the seam allows: via the CLOSED backdoor.
    String fileId = "00000000-0000-0000-0000-600000000102";
    app.backdoor().populator().addNode(new SimplePopulatorTextFile(fileId, OWNER_ID, "a.txt"));
    app.backdoor().injectUserStatusChanged(OWNER_ID, "CLOSED");
    Assertions.assertThat(findNodeIdsOwnedByRequester()).doesNotContain(fileId);

    // When
    app.backdoor().injectUserStatusChanged(OWNER_ID, "ACTIVE");

    // Then
    Assertions.assertThat(findNodeIdsOwnedByRequester()).contains(fileId);
  }

  @Test
  void givenAVisibleNodeUserStatusChangedMaintenanceThenNodeStaysVisible() {
    // Given — freshly-seeded, non-hidden baseline (see class-level FINDING for why this
    // starting state matters: MAINTENANCE is a no-op here only because it agrees with the
    // current "not hidden" state, not because MAINTENANCE is special-cased).
    String fileId = "00000000-0000-0000-0000-600000000103";
    app.backdoor().populator().addNode(new SimplePopulatorTextFile(fileId, OWNER_ID, "a.txt"));
    Assertions.assertThat(findNodeIdsOwnedByRequester()).contains(fileId);

    // When
    app.backdoor().injectUserStatusChanged(OWNER_ID, "MAINTENANCE");

    // Then
    Assertions.assertThat(findNodeIdsOwnedByRequester()).contains(fileId);
  }

  // --- MaxVersionNumberChanged: least-recent versions trimmed, current/keptForever spared -

  @Test
  void givenANodeWithFiveVersionsMaxVersionNumberChangedToThreeTrimsTheTwoOldest() {
    // Given — versions 1..5, none kept forever.
    String fileId = "00000000-0000-0000-0000-600000000104";
    app.backdoor()
        .populator()
        .addNode(new SimplePopulatorTextFile(fileId, OWNER_ID, "a.txt"))
        .addVersion(fileId)
        .addVersion(fileId)
        .addVersion(fileId)
        .addVersion(fileId);
    Assertions.assertThat(app.backdoor().remainingVersionNumbers(fileId))
        .containsExactly(1, 2, 3, 4, 5);

    // When
    app.backdoor().injectMaxVersionNumberChanged(3);

    // Then — HTTP-observable via the neutral inspector: oldest two (1, 2) trimmed.
    Assertions.assertThat(app.backdoor().remainingVersionNumbers(fileId))
        .containsExactly(3, 4, 5);
  }

  @Test
  void givenANodeWithKeptForeverAndCurrentVersionsMaxVersionNumberChangedSparesBoth() {
    // Given — versions 2 and 3 marked keepForever, version 5 is current; only 1 and 4 are
    // eligible for trimming. Mirrors MaxVersionNumberChangedIT's second scenario.
    String fileId = "00000000-0000-0000-0000-600000000105";
    app.backdoor()
        .populator()
        .addNode(new SimplePopulatorTextFile(fileId, OWNER_ID, "a.txt"))
        .addVersion(fileId, true)
        .addVersion(fileId, true)
        .addVersion(fileId)
        .addVersion(fileId);
    Assertions.assertThat(app.backdoor().remainingVersionNumbers(fileId))
        .containsExactly(1, 2, 3, 4, 5);

    // When — requesting max=2 can only actually remove 1 and 4 (2, 3 keptForever; 5 current).
    app.backdoor().injectMaxVersionNumberChanged(2);

    // Then
    Assertions.assertThat(app.backdoor().remainingVersionNumbers(fileId))
        .containsExactly(2, 3, 5);
  }

  @Test
  void givenATrimmedVersionDownloadOfTheOldVersionReturns404WhileASurvivingVersionStillServes() {
    // Given — same trim as the first scenario: versions 1, 2 removed; 3, 4, 5 remain.
    String fileId = "00000000-0000-0000-0000-600000000106";
    app.backdoor()
        .populator()
        .addNode(new SimplePopulatorTextFile(fileId, OWNER_ID, "a.txt"))
        .addVersion(fileId)
        .addVersion(fileId)
        .addVersion(fileId)
        .addVersion(fileId);
    app.backdoor().injectMaxVersionNumberChanged(3);
    Assertions.assertThat(app.backdoor().remainingVersionNumbers(fileId)).containsExactly(3, 4, 5);

    // When / Then — the trimmed version 1 is gone: BlobService#downloadFile can't find its
    // FileVersion row, funnelling into the same generic 404 as a non-existent node/version.
    HttpResponse trimmedDownload = download(fileId, 1);
    Assertions.assertThat(trimmedDownload.getStatus()).isEqualTo(404);
    Assertions.assertThat(trimmedDownload.getBodyPayload()).isEqualTo("404 Not Found");

    // A surviving version is unaffected.
    app.mocks().storagesServesBlob(fileId, 3);
    HttpResponse survivingDownload = download(fileId, 3);
    Assertions.assertThat(survivingDownload.getStatus()).isEqualTo(200);
    app.mocks().verifyStoragesDownloaded(fileId, 3);
  }
}
