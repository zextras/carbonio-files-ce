// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.dal;

import static org.assertj.core.api.Assertions.assertThat;

import com.zextras.carbonio.files.FilesStackTestResource;
import com.zextras.carbonio.files.dal.dao.ebean.FileVersion;
import com.zextras.carbonio.files.dal.dao.ebean.Tombstone;
import com.zextras.carbonio.files.dal.repositories.interfaces.TombstoneRepository;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * P2c: validates the clean Panache/JPA {@code TombstoneRepositoryImpl}, which replaces {@code
 * TombstoneRepositoryEbean}.
 *
 * <p>The {@code tombstone} table has no foreign key to {@code node} (see V1__init.sql), so no
 * parent {@code Node} row is required here.
 */
@QuarkusTest
@QuarkusTestResource(FilesStackTestResource.class)
class TombstoneRepositoryIT {

  @Inject TombstoneRepository tombstoneRepository;

  @Test
  @TestTransaction
  void createNewTombstoneShouldPersistAndBeListed() {
    String nodeId = "44444444-4444-4444-4444-444444444441";

    Optional<Tombstone> created =
        tombstoneRepository.createNewTombstone(nodeId, "owner-1", 1);

    assertThat(created).isPresent();
    assertThat(created.get().getNodeId()).isEqualTo(nodeId);
    assertThat(created.get().getOwnerId()).isEqualTo("owner-1");
    assertThat(created.get().getVersion()).isEqualTo(1);
    assertThat(created.get().getAttempts()).isEqualTo(0);

    assertThat(tombstoneRepository.getTombstones())
        .anySatisfy(
            tombstone -> {
              assertThat(tombstone.getNodeId()).isEqualTo(nodeId);
              assertThat(tombstone.getVersion()).isEqualTo(1);
            });
  }

  @Test
  @TestTransaction
  void createNewTombstoneShouldReturnEmptyWhenTheSameNodeAndVersionAlreadyExists() {
    String nodeId = "44444444-4444-4444-4444-444444444442";

    assertThat(tombstoneRepository.createNewTombstone(nodeId, "owner-1", 1)).isPresent();
    assertThat(tombstoneRepository.createNewTombstone(nodeId, "owner-2", 1)).isEmpty();

    long matching =
        tombstoneRepository.getTombstones().stream()
            .filter(t -> t.getNodeId().equals(nodeId) && t.getVersion().equals(1))
            .count();
    assertThat(matching).isEqualTo(1);
  }

  @Test
  @TestTransaction
  void createTombstonesBulkShouldCreateOneTombstonePerFileVersion() {
    String nodeId = "44444444-4444-4444-4444-444444444443";
    List<FileVersion> fileVersions =
        List.of(
            new FileVersion(nodeId, "editor-1", 1000L, 1, "text/plain", 10L, "digest1", false),
            new FileVersion(nodeId, "editor-1", 2000L, 2, "text/plain", 20L, "digest2", false));

    tombstoneRepository.createTombstonesBulk(fileVersions, "owner-bulk");

    List<Tombstone> matching =
        tombstoneRepository.getTombstones().stream()
            .filter(t -> t.getNodeId().equals(nodeId))
            .toList();
    assertThat(matching).hasSize(2);
    assertThat(matching).allSatisfy(t -> assertThat(t.getOwnerId()).isEqualTo("owner-bulk"));
    assertThat(matching.stream().map(Tombstone::getVersion).toList())
        .containsExactlyInAnyOrder(1, 2);
  }

  @Test
  @TestTransaction
  void deleteTombstonesByNodeAndVersionShouldRemoveOnlyThatEntry() {
    String nodeId = "44444444-4444-4444-4444-444444444444";
    tombstoneRepository.createNewTombstone(nodeId, "owner-1", 1);
    tombstoneRepository.createNewTombstone(nodeId, "owner-1", 2);

    tombstoneRepository.deleteTombstonesByNodeAndVersion(nodeId, 1);

    List<Tombstone> remaining =
        tombstoneRepository.getTombstones().stream()
            .filter(t -> t.getNodeId().equals(nodeId))
            .toList();
    assertThat(remaining).hasSize(1);
    assertThat(remaining.get(0).getVersion()).isEqualTo(2);
  }

  @Test
  @TestTransaction
  void updateTombstoneShouldPersistAttemptsCounter() {
    String nodeId = "44444444-4444-4444-4444-444444444445";
    Tombstone tombstone =
        tombstoneRepository.createNewTombstone(nodeId, "owner-1", 1).orElseThrow();

    tombstone.setAttempts(3);
    tombstoneRepository.updateTombstone(tombstone);

    Tombstone reloaded =
        tombstoneRepository.getTombstones().stream()
            .filter(t -> t.getNodeId().equals(nodeId) && t.getVersion().equals(1))
            .findFirst()
            .orElseThrow();
    assertThat(reloaded.getAttempts()).isEqualTo(3);
  }
}
