// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.dal;

import static org.assertj.core.api.Assertions.assertThat;

import com.zextras.carbonio.files.FilesStackTestResource;
import com.zextras.carbonio.files.dal.dao.ebean.FileVersion;
import com.zextras.carbonio.files.dal.dao.ebean.Node;
import com.zextras.carbonio.files.dal.dao.ebean.NodeType;
import com.zextras.carbonio.files.dal.repositories.impl.ebean.utilities.FileVersionSort;
import com.zextras.carbonio.files.dal.repositories.interfaces.FileVersionRepository;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * P2c: validates the clean Panache/JPA {@code FileVersionRepositoryImpl}, which replaces {@code
 * FileVersionRepositoryEbean}.
 */
@QuarkusTest
@QuarkusTestResource(FilesStackTestResource.class)
class FileVersionRepositoryIT {

  @Inject FileVersionRepository fileVersionRepository;

  @Inject EntityManager entityManager;

  private Node persistParentNode(String nodeId, int currentVersion) {
    Node node =
        new Node(
            nodeId,
            "creator-id",
            "owner-id",
            "",
            1L,
            1L,
            "file-version-parent-" + nodeId,
            "description",
            NodeType.TEXT,
            "",
            0L);
    node.setCurrentVersion(currentVersion);
    entityManager.persist(node);
    return node;
  }

  @Test
  @TestTransaction
  void createNewFileVersionShouldPersistAndBeRetrievable() {
    String nodeId = "55555555-5555-5555-5555-555555555551";
    persistParentNode(nodeId, 1);

    Optional<FileVersion> created =
        fileVersionRepository.createNewFileVersion(
            nodeId, "editor-1", 1, "text/plain", 10L, "digest1", false);

    assertThat(created).isPresent();
    assertThat(created.get().getNodeId()).isEqualTo(nodeId);
    assertThat(created.get().getVersion()).isEqualTo(1);
    assertThat(created.get().getMimeType()).isEqualTo("text/plain");
    assertThat(created.get().getDigest()).isEqualTo("digest1");
    assertThat(created.get().isAutosave()).isFalse();

    Optional<FileVersion> found = fileVersionRepository.getFileVersion(nodeId, 1);
    assertThat(found).isPresent();
    assertThat(found.get().getLastEditorId()).isEqualTo("editor-1");
  }

  @Test
  @TestTransaction
  void createNewFileVersionShouldReturnEmptyWhenNodeIsMissing() {
    Optional<FileVersion> created =
        fileVersionRepository.createNewFileVersion(
            "55555555-5555-5555-5555-555555555552",
            "editor-1",
            1,
            "text/plain",
            10L,
            "digest1",
            false);

    assertThat(created).isEmpty();
  }

  @Test
  @TestTransaction
  void getFileVersionShouldReturnEmptyWhenMissing() {
    assertThat(fileVersionRepository.getFileVersion("55555555-5555-5555-5555-555555555553", 1))
        .isEmpty();
  }

  @Test
  @TestTransaction
  void getFileVersionsShouldRespectSortDirection() {
    String nodeId = "55555555-5555-5555-5555-555555555554";
    persistParentNode(nodeId, 3);
    entityManager.persist(new FileVersion(nodeId, "editor-1", 1000L, 1, "text/plain", 10L, "d1", false));
    entityManager.persist(new FileVersion(nodeId, "editor-1", 2000L, 2, "text/plain", 20L, "d2", false));
    entityManager.persist(new FileVersion(nodeId, "editor-1", 3000L, 3, "text/plain", 30L, "d3", false));

    List<Integer> ascending =
        fileVersionRepository
            .getFileVersions(nodeId, List.of(FileVersionSort.VERSION_ASC))
            .stream()
            .map(FileVersion::getVersion)
            .toList();
    List<Integer> descending =
        fileVersionRepository
            .getFileVersions(nodeId, List.of(FileVersionSort.VERSION_DESC))
            .stream()
            .map(FileVersion::getVersion)
            .toList();

    assertThat(ascending).containsExactly(1, 2, 3);
    assertThat(descending).containsExactly(3, 2, 1);
  }

  @Test
  @TestTransaction
  void getFileVersionsByVersionsCollectionShouldReturnOnlyRequestedVersions() {
    String nodeId = "55555555-5555-5555-5555-555555555555";
    persistParentNode(nodeId, 3);
    entityManager.persist(new FileVersion(nodeId, "editor-1", 1000L, 1, "text/plain", 10L, "d1", false));
    entityManager.persist(new FileVersion(nodeId, "editor-1", 2000L, 2, "text/plain", 20L, "d2", false));
    entityManager.persist(new FileVersion(nodeId, "editor-1", 3000L, 3, "text/plain", 30L, "d3", false));

    List<Integer> found =
        fileVersionRepository.getFileVersions(nodeId, List.of(1, 3)).stream()
            .map(FileVersion::getVersion)
            .toList();

    assertThat(found).containsExactlyInAnyOrder(1, 3);
  }

  @Test
  @TestTransaction
  void getLastFileVersionShouldReturnTheHighestVersion() {
    String nodeId = "55555555-5555-5555-5555-555555555556";
    persistParentNode(nodeId, 2);
    entityManager.persist(new FileVersion(nodeId, "editor-1", 1000L, 1, "text/plain", 10L, "d1", false));
    entityManager.persist(new FileVersion(nodeId, "editor-1", 2000L, 2, "text/plain", 20L, "d2", false));

    Optional<FileVersion> last = fileVersionRepository.getLastFileVersion(nodeId);

    assertThat(last).isPresent();
    assertThat(last.get().getVersion()).isEqualTo(2);
  }

  @Test
  @TestTransaction
  void updateFileVersionShouldPersistChanges() {
    String nodeId = "55555555-5555-5555-5555-555555555557";
    persistParentNode(nodeId, 1);
    FileVersion fileVersion =
        fileVersionRepository
            .createNewFileVersion(nodeId, "editor-1", 1, "text/plain", 10L, "digest1", false)
            .orElseThrow();

    fileVersion.keepForever(true);
    FileVersion updated = fileVersionRepository.updateFileVersion(fileVersion);

    assertThat(updated.isKeptForever()).isTrue();
    assertThat(fileVersionRepository.getFileVersion(nodeId, 1).orElseThrow().isKeptForever())
        .isTrue();
  }

  @Test
  @TestTransaction
  void deleteFileVersionShouldRemoveItAndReturnTrue() {
    String nodeId = "55555555-5555-5555-5555-555555555558";
    persistParentNode(nodeId, 1);
    FileVersion fileVersion =
        fileVersionRepository
            .createNewFileVersion(nodeId, "editor-1", 1, "text/plain", 10L, "digest1", false)
            .orElseThrow();

    boolean deleted = fileVersionRepository.deleteFileVersion(fileVersion);

    assertThat(deleted).isTrue();
    assertThat(fileVersionRepository.getFileVersion(nodeId, 1)).isEmpty();
  }

  @Test
  @TestTransaction
  void deleteFileVersionShouldReturnFalseWhenMissing() {
    String nodeId = "55555555-5555-5555-5555-555555555559";
    persistParentNode(nodeId, 1);
    FileVersion notPersisted =
        new FileVersion(nodeId, "editor-1", 1000L, 99, "text/plain", 10L, "d1", false);

    assertThat(fileVersionRepository.deleteFileVersion(notPersisted)).isFalse();
  }

  @Test
  @TestTransaction
  void deleteFileVersionsBulkShouldRemoveOnlyGivenVersions() {
    String nodeId = "55555555-5555-5555-5555-555555555560";
    persistParentNode(nodeId, 3);
    entityManager.persist(new FileVersion(nodeId, "editor-1", 1000L, 1, "text/plain", 10L, "d1", false));
    entityManager.persist(new FileVersion(nodeId, "editor-1", 2000L, 2, "text/plain", 20L, "d2", false));
    entityManager.persist(new FileVersion(nodeId, "editor-1", 3000L, 3, "text/plain", 30L, "d3", false));

    fileVersionRepository.deleteFileVersions(nodeId, List.of(1, 2));

    assertThat(fileVersionRepository.getFileVersion(nodeId, 1)).isEmpty();
    assertThat(fileVersionRepository.getFileVersion(nodeId, 2)).isEmpty();
    assertThat(fileVersionRepository.getFileVersion(nodeId, 3)).isPresent();
  }

  @Test
  @TestTransaction
  void getFileVersionsRelatedToNodesHavingVersionsGreaterThanShouldGroupPerNodeAscending() {
    String nodeAboveThreshold = "55555555-5555-5555-5555-555555555561";
    String nodeBelowThreshold = "55555555-5555-5555-5555-555555555562";
    persistParentNode(nodeAboveThreshold, 5);
    persistParentNode(nodeBelowThreshold, 2);

    for (int version = 1; version <= 5; version++) {
      entityManager.persist(
          new FileVersion(
              nodeAboveThreshold,
              "editor-1",
              1000L * version,
              version,
              "text/plain",
              10L,
              "digest" + version,
              false));
    }
    entityManager.persist(
        new FileVersion(nodeBelowThreshold, "editor-1", 1000L, 1, "text/plain", 10L, "d1", false));

    Map<String, List<FileVersion>> candidates =
        fileVersionRepository.getFileVersionsRelatedToNodesHavingVersionsGreaterThan(3);

    assertThat(candidates).containsOnlyKeys(nodeAboveThreshold);
    assertThat(candidates.get(nodeAboveThreshold).stream().map(FileVersion::getVersion).toList())
        .containsExactly(1, 2, 3, 4, 5);
  }
}
