// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.api;

import com.google.inject.Injector;
import com.zextras.carbonio.files.Simulator;
import com.zextras.carbonio.files.Simulator.SimulatorBuilder;
import com.zextras.carbonio.files.TestUtils;
import com.zextras.carbonio.files.api.utilities.DatabasePopulator;
import com.zextras.carbonio.files.api.utilities.GraphqlCommandBuilder;
import com.zextras.carbonio.files.api.utilities.entities.SimplePopulatorTextFile;
import com.zextras.carbonio.files.dal.repositories.impl.ebean.utilities.FileVersionSort;
import com.zextras.carbonio.files.dal.repositories.interfaces.FileVersionRepository;
import com.zextras.carbonio.files.dal.repositories.interfaces.NodeRepository;
import com.zextras.carbonio.files.utilities.StoragesMockHelper;
import com.zextras.carbonio.files.utilities.http.HttpRequest;
import com.zextras.carbonio.files.utilities.http.HttpResponse;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

class DeleteVersionsApiIT {

  static Simulator simulator;
  static StoragesMockHelper storagesMockHelper;
  static NodeRepository nodeRepository;
  static FileVersionRepository fileVersionRepository;

  private static final String OWNER_ID = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";

  @BeforeAll
  static void init() {
    simulator =
        SimulatorBuilder.aSimulator()
            .init()
            .withDatabase()
            .withServiceDiscover()
            .withStorages()
            .withUserManagement(Map.of("fake-token", OWNER_ID))
            .build()
            .start();

    final Injector injector = simulator.getInjector();
    nodeRepository = injector.getInstance(NodeRepository.class);
    fileVersionRepository = injector.getInstance(FileVersionRepository.class);
    storagesMockHelper = new StoragesMockHelper(simulator.getStoragesMock());
  }

  @AfterEach
  void cleanUp() {
    simulator.resetDatabase();
    simulator.reinitializeMocks();
  }

  @AfterAll
  static void cleanUpAll() {
    simulator.stopAll();
  }

  private HttpResponse executeDeleteVersions(String nodeId, int... versions) {
    String bodyPayload =
        GraphqlCommandBuilder.aMutationBuilder("deleteVersions")
            .withString("node_id", nodeId)
            .withListOfIntegers("versions", versions)
            .withWantedResultFormat("")
            .build();
    HttpRequest httpRequest =
        HttpRequest.of("POST", "/graphql/", "ZM_AUTH_TOKEN=fake-token", null, bodyPayload);
    return TestUtils.sendRequest(httpRequest, simulator.getNettyChannel());
  }

  /**
   * Creates a file with 3 versions using DatabasePopulator.
   * Initial addNode creates version 1; addVersion adds versions 2 and 3.
   * The node's currentVersion ends at 3.
   */
  private void createFileWithThreeVersions(String nodeId) {
    DatabasePopulator.aNodePopulator(simulator.getInjector())
        .addNode(new SimplePopulatorTextFile(nodeId, OWNER_ID, "file.txt"))
        .addVersion(nodeId)   // version 2
        .addVersion(nodeId);  // version 3
  }

  private List<Integer> getRemainingVersionNumbers(String nodeId) {
    return fileVersionRepository
        .getFileVersions(nodeId, List.of(FileVersionSort.VERSION_ASC))
        .stream()
        .map(fv -> fv.getVersion())
        .collect(Collectors.toList());
  }

  // --- Test 1: Happy path — file with 3 versions, delete [1,2], all blobs succeed ---

  @Test
  void givenFileWithThreeVersionsDeleteVersionsOneAndTwoAllBlobsSucceedThenVersionsOneAndTwoDeletedVersionThreeStays() {
    // Given
    String nodeId = "00000000-0000-0000-0000-200000000001";
    createFileWithThreeVersions(nodeId);

    storagesMockHelper.bulkDelete(List.of());

    // When — delete versions 1 and 2 (current=3 must stay)
    HttpResponse httpResponse = executeDeleteVersions(nodeId, 1, 2);

    // Then
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(200);

    List<Integer> deletedVersions =
        (List<Integer>) TestUtils.jsonResponseToValue(httpResponse.getBodyPayload(), "deleteVersions")
            .orElse(List.of());
    Assertions.assertThat(deletedVersions).containsExactlyInAnyOrder(1, 2);

    List<String> errors = TestUtils.jsonResponseToErrors(httpResponse.getBodyPayload());
    Assertions.assertThat(errors).isEmpty();

    // Verify DB: only version 3 remains
    Assertions.assertThat(getRemainingVersionNumbers(nodeId)).containsExactly(3);
  }

  // --- Test 2: Partial failure — file with 3 versions, delete [1,2], version 1 blob fails ---

  @Test
  void givenFileWithThreeVersionsDeleteVersionsOneAndTwoVersionOneBlobFailsThenOnlyVersionTwoDeletedVersionOneAndThreeStay() {
    // Given
    String nodeId = "00000000-0000-0000-0000-200000000002";
    createFileWithThreeVersions(nodeId);

    // version 1 blob deletion fails (node=nodeId, version=1)
    storagesMockHelper.bulkDeleteWithVersions(List.of(Map.entry(nodeId, 1)));

    // When — delete versions 1 and 2
    HttpResponse httpResponse = executeDeleteVersions(nodeId, 1, 2);

    // Then
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(200);

    List<Integer> deletedVersions =
        (List<Integer>) TestUtils.jsonResponseToValue(httpResponse.getBodyPayload(), "deleteVersions")
            .orElse(List.of());
    // Only version 2 was successfully deleted in PowerStore
    Assertions.assertThat(deletedVersions).containsExactly(2);

    // Errors must be returned for version 1 (blob delete failed)
    List<String> errors = TestUtils.jsonResponseToErrors(httpResponse.getBodyPayload());
    Assertions.assertThat(errors).isNotEmpty();

    // DB: version 1 (blob failed) and version 3 (current) stay; version 2 is deleted
    Assertions.assertThat(getRemainingVersionNumbers(nodeId)).containsExactly(1, 3);
  }

  // --- Test 3: Total failure — file with 3 versions, delete [1,2], all blobs fail ---

  @Test
  void givenFileWithThreeVersionsDeleteVersionsOneAndTwoAllBlobsFailThenNothingDeletedAndErrorsReturned() {
    // Given
    String nodeId = "00000000-0000-0000-0000-200000000003";
    createFileWithThreeVersions(nodeId);

    storagesMockHelper.bulkDeleteWithVersions(List.of(Map.entry(nodeId, 1), Map.entry(nodeId, 2)));

    // When
    HttpResponse httpResponse = executeDeleteVersions(nodeId, 1, 2);

    // Then
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(200);

    List<Integer> deletedVersions =
        (List<Integer>) TestUtils.jsonResponseToValue(httpResponse.getBodyPayload(), "deleteVersions")
            .orElse(List.of());
    Assertions.assertThat(deletedVersions).isEmpty();

    List<String> errors = TestUtils.jsonResponseToErrors(httpResponse.getBodyPayload());
    Assertions.assertThat(errors).hasSize(2);

    // All 3 versions stay in DB
    Assertions.assertThat(getRemainingVersionNumbers(nodeId)).containsExactly(1, 2, 3);
  }

  // --- Test 4: Current version cannot be deleted ---

  @Test
  void givenFileWithThreeVersionsDeleteCurrentVersionThenCurrentVersionSkippedAndErrorReturned() {
    // Given
    String nodeId = "00000000-0000-0000-0000-200000000004";
    createFileWithThreeVersions(nodeId); // current = 3

    storagesMockHelper.bulkDelete(List.of());

    // When — try to delete current version (3)
    HttpResponse httpResponse = executeDeleteVersions(nodeId, 3);

    // Then
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(200);

    List<Integer> deletedVersions =
        (List<Integer>) TestUtils.jsonResponseToValue(httpResponse.getBodyPayload(), "deleteVersions")
            .orElse(List.of());
    Assertions.assertThat(deletedVersions).isEmpty();

    List<String> errors = TestUtils.jsonResponseToErrors(httpResponse.getBodyPayload());
    Assertions.assertThat(errors).hasSize(1);

    // All 3 versions stay in DB
    Assertions.assertThat(getRemainingVersionNumbers(nodeId)).containsExactly(1, 2, 3);
  }

  // --- Test 5: keepForever version cannot be deleted ---

  @Test
  void givenFileWithKeepForeverVersionDeleteItThenKeepForeverVersionSkippedAndOnlyEligibleVersionDeleted() {
    // Given — version 2 is keepForever, version 3 is current
    String nodeId = "00000000-0000-0000-0000-200000000005";
    DatabasePopulator.aNodePopulator(simulator.getInjector())
        .addNode(new SimplePopulatorTextFile(nodeId, OWNER_ID, "file.txt"))
        .addVersion(nodeId, true)   // version 2, keepForever=true
        .addVersion(nodeId);        // version 3 (current)

    storagesMockHelper.bulkDelete(List.of());

    // When — try to delete v1 (eligible) and v2 (keepForever, ineligible)
    HttpResponse httpResponse = executeDeleteVersions(nodeId, 1, 2);

    // Then
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(200);

    List<Integer> deletedVersions =
        (List<Integer>) TestUtils.jsonResponseToValue(httpResponse.getBodyPayload(), "deleteVersions")
            .orElse(List.of());
    // Only v1 deleted; v2 is keepForever (skipped)
    Assertions.assertThat(deletedVersions).containsExactly(1);

    List<String> errors = TestUtils.jsonResponseToErrors(httpResponse.getBodyPayload());
    Assertions.assertThat(errors).hasSize(1); // error for v2

    // v2 (keepForever) and v3 (current) stay
    Assertions.assertThat(getRemainingVersionNumbers(nodeId)).containsExactly(2, 3);
  }
}
