// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.it;

import com.zextras.carbonio.files.FilesStackTestResource;
import com.zextras.carbonio.files.it.support.AbstractFilesIT;
import com.zextras.carbonio.files.it.support.MockStoragesService;
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import io.quarkus.test.common.TestResourceScope;
import io.quarkus.test.common.WithTestResource;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** The purge of reservations never confirmed, run every second with no expiry. */
@WithTestResource(
    value = ReservationPurgeApiIT.FastPurge.class,
    scope = TestResourceScope.RESTRICTED_TO_CLASS)
class ReservationPurgeApiIT extends AbstractFilesIT {

  private static final String REQUESTER_ID = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
  private static final String REQUESTER_COOKIE = "ZM_AUTH_TOKEN=fake-token";
  private static final byte[] CONTENT = "content".getBytes(StandardCharsets.UTF_8);

  public static class FastPurge implements QuarkusTestResourceLifecycleManager {
    @Override
    public Map<String, String> start() {
      return Map.of(
          "quarkus.scheduler.enabled", "true",
          "carbonio.files.reservation-purge.interval", "1s",
          "carbonio.files.reservation-purge.delay", "1s",
          "carbonio.files.reservation-purge.expiry", "0s");
    }

    // Started after FilesStackTestResource, which disables the scheduler for every other IT.
    @Override
    public int order() {
      return Integer.MAX_VALUE;
    }

    @Override
    public void stop() {}
  }

  @BeforeAll
  static void registerUsers() {
    FilesStackTestResource.getUserManagementService().registerToken("fake-token", REQUESTER_ID);
  }

  /** A failed save whose blob reached the storage: version 2 stays reserved, its blob orphaned. */
  private static String fileWithAnOrphanBlobAtVersion2() {
    String nodeId = seedFile("purged.txt", LOCAL_ROOT, CONTENT, REQUESTER_COOKIE);
    FilesStackTestResource.getStoragesService().seed(nodeId, 2, CONTENT);
    Assertions.assertThat(
            uploadVersion(nodeId, CONTENT, "purged.txt", false, REQUESTER_COOKIE).getStatusCode())
        .isEqualTo(500);
    return nodeId;
  }

  private static List<String> reservations(String nodeId) throws SQLException {
    try (Connection connection = jdbcConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "SELECT version, state, attempts FROM version_reservation WHERE node_id = ?")) {
      statement.setString(1, nodeId);
      try (ResultSet resultSet = statement.executeQuery()) {
        List<String> rows = new ArrayList<>();
        while (resultSet.next()) {
          rows.add(resultSet.getInt(1) + "/" + resultSet.getString(2) + "/" + resultSet.getInt(3));
        }
        return rows;
      }
    }
  }

  @Test
  void givenAnExpiredReservationThenItsBlobIsDeletedAndTheReservationRemoved() throws Exception {
    String nodeId = fileWithAnOrphanBlobAtVersion2();

    eventually("reservation purged", () -> reservations(nodeId).isEmpty());

    Assertions.assertThat(FilesStackTestResource.getStoragesService().has(nodeId, 2)).isFalse();
    Assertions.assertThat(FilesStackTestResource.getStoragesService().has(nodeId, 1)).isTrue();
    Assertions.assertThat(versionRows(nodeId)).containsExactly(1);
  }

  @Test
  void givenTheStorageKeepsRefusingTheDeleteThenTheReservationIsDroppedAfterThreeAnswers()
      throws Exception {
    String nodeId = fileWithAnOrphanBlobAtVersion2();
    FilesStackTestResource.getStoragesService().failBulkDeleteFor(nodeId);

    eventually("first refusal counted", () -> reservations(nodeId).equals(List.of("2/PURGING/1")));
    eventually("reservation dropped", () -> reservations(nodeId).isEmpty());

    Assertions.assertThat(FilesStackTestResource.getStoragesService().has(nodeId, 2)).isTrue();
  }

  @Test
  void givenAStorageOutageThenTheReservationWaitsWithoutCountingAttempts() throws Exception {
    MockStoragesService storages = FilesStackTestResource.getStoragesService();
    String nodeId = fileWithAnOrphanBlobAtVersion2();
    storages.setBulkDeleteAlwaysThrows(true);

    eventually("reservation claimed", () -> reservations(nodeId).equals(List.of("2/PURGING/0")));
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
    while (System.nanoTime() < deadline) {
      Assertions.assertThat(reservations(nodeId)).containsExactly("2/PURGING/0");
    }

    storages.setBulkDeleteAlwaysThrows(false);
    eventually("reservation purged after the outage", () -> reservations(nodeId).isEmpty());
    Assertions.assertThat(storages.has(nodeId, 2)).isFalse();
  }
}
