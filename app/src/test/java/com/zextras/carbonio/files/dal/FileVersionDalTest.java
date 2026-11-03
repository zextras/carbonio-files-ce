// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.dal;

import static org.assertj.core.api.Assertions.assertThat;

import com.zextras.carbonio.files.FilesStackTestResource;
import com.zextras.carbonio.files.dal.dao.ebean.FileVersion;
import com.zextras.carbonio.files.dal.dao.ebean.Node;
import com.zextras.carbonio.files.dal.dao.ebean.NodeType;
import com.zextras.carbonio.files.dal.repositories.interfaces.FileVersionRepository;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

@QuarkusTest
@QuarkusTestResource(FilesStackTestResource.class)
class FileVersionDalTest {

  @Inject FileVersionRepository fileVersionRepository;
  @Inject EntityManager entityManager;

  private String fileWithVersions(String... digests) {
    String id = UUID.randomUUID().toString();
    long now = System.currentTimeMillis();
    entityManager.persist(
        new Node(
            id,
            "owner",
            "owner",
            "LOCAL_ROOT",
            now,
            now,
            "f",
            "",
            NodeType.TEXT,
            "LOCAL_ROOT",
            1L));
    for (int i = 0; i < digests.length; i++) {
      entityManager.persist(
          new FileVersion(id, "owner", now, i + 1, "text/plain", 1L, digests[i], false));
    }
    entityManager.flush();
    return id;
  }

  @Test
  @TestTransaction
  void everyVersionOfEachNodeIsReadByNode() {
    String first = fileWithVersions("a", "b");
    String second = fileWithVersions("c");
    String none = fileWithVersions();

    Map<String, List<FileVersion>> versions =
        fileVersionRepository.getFileVersionsOf(List.of(first, second, none));

    assertThat(versions).containsOnlyKeys(first, second);
    assertThat(versions.get(first))
        .extracting(FileVersion::getVersion, FileVersion::getDigest)
        .containsExactlyInAnyOrder(
            org.assertj.core.groups.Tuple.tuple(1, "a"),
            org.assertj.core.groups.Tuple.tuple(2, "b"));
    assertThat(versions.get(second)).extracting(FileVersion::getDigest).containsExactly("c");
  }

  @Test
  @TestTransaction
  void moreNodesThanOneInListHoldsAreRead() {
    String file = fileWithVersions("a");
    List<String> ids = new ArrayList<>();
    for (int i = 0; i < 70_000; i++) {
      ids.add(UUID.randomUUID().toString());
    }
    ids.add(file);

    assertThat(fileVersionRepository.getFileVersionsOf(ids)).containsOnlyKeys(file);
  }
}
