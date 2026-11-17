// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.dal;

import static org.assertj.core.api.Assertions.assertThat;

import com.zextras.carbonio.files.FilesStackTestResource;
import com.zextras.carbonio.files.dal.dao.ebean.Tombstone;
import com.zextras.carbonio.files.dal.dao.ebean.TombstonePK;
import com.zextras.carbonio.files.dal.repositories.interfaces.TombstoneRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

@QuarkusTest
@QuarkusTestResource(FilesStackTestResource.class)
class TombstoneDalTest {

  private static final String OWNER = "11111111-1111-1111-1111-111111111111";

  @Inject TombstoneRepository tombstones;
  @Inject EntityManager entityManager;

  private final List<String> nodes = new ArrayList<>();

  @AfterEach
  void cleanUp() {
    QuarkusTransaction.requiringNew()
        .run(
            () ->
                entityManager
                    .createQuery("delete from Tombstone t where t.mComposedId.mNodeId in :ids")
                    .setParameter("ids", nodes)
                    .executeUpdate());
  }

  private String newNode() {
    String id = UUID.randomUUID().toString();
    nodes.add(id);
    return id;
  }

  private int attemptsOf(String nodeId, int version) {
    return tombstones.getTombstones().stream()
        .filter(t -> t.getNodeId().equals(nodeId) && t.getVersion() == version)
        .map(Tombstone::getAttempts)
        .findFirst()
        .orElseThrow();
  }

  @Test
  void incrementAttemptsCountsOnlyTheGivenTombstones() {
    String a = newNode();
    String b = newNode();
    tombstones.createNewTombstone(a, OWNER, 1);
    tombstones.createNewTombstone(a, OWNER, 2);
    tombstones.createNewTombstone(b, OWNER, 1);

    tombstones.incrementAttempts(List.of(new TombstonePK(a, 1), new TombstonePK(b, 1)));
    tombstones.incrementAttempts(List.of(new TombstonePK(a, 1)));

    assertThat(attemptsOf(a, 1)).isEqualTo(2);
    assertThat(attemptsOf(a, 2)).isZero();
    assertThat(attemptsOf(b, 1)).isEqualTo(1);
  }

  @Test
  void incrementAttemptsWithNoIdsChangesNothing() {
    String a = newNode();
    tombstones.createNewTombstone(a, OWNER, 1);

    tombstones.incrementAttempts(List.of());

    assertThat(attemptsOf(a, 1)).isZero();
  }
}
