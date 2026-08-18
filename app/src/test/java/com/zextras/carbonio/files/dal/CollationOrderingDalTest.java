// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.dal;

import static org.assertj.core.api.Assertions.assertThat;

import com.zextras.carbonio.files.dal.dao.ebean.Node;
import com.zextras.carbonio.files.dal.dao.ebean.NodeType;
import com.zextras.carbonio.files.dal.repositories.impl.ebean.utilities.NodeSort;
import com.zextras.carbonio.files.dal.repositories.interfaces.NodeRepository;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * RED/GREEN proof that {@code NodeRepositoryImpl}'s {@code NAME_ASC}/{@code NAME_DESC} ordering
 * actually applies the database collation {@link
 * com.zextras.carbonio.files.dal.repositories.impl.CollationRepositoryImpl} resolves, restoring the
 * feature the Quarkus rewrite silently dropped when it deleted {@code CollationRepositoryImpl} as
 * (apparently) dead code.
 *
 * <p><b>Why this needs its own {@link CLocalePostgresTestResource}, not the shared {@code
 * FilesStackTestResource}:</b> the shared Testcontainer's database defaults to {@code
 * datcollate=en_US.utf8} (the official {@code postgres:16} image's own default), so {@code
 * CollationRepositoryImpl}'s {@code C}-detection branch never fires there and an explicit {@code
 * COLLATE "en_US.utf8"} would be a no-op — the exact "container's own default collation may already
 * mask the difference" trap. {@link CLocalePostgresTestResource} instead forces {@code
 * datcollate=C}, the one environment this feature exists for, so the fix's effect is observable.
 *
 * <p><b>Why these particular fixture names:</b> under the {@code C} collation Postgres falls back
 * to when nothing else is specified, string ordering is plain byte/codepoint order: {@code 'A'}
 * (0x41) &lt; {@code 'Z'} (0x5A) &lt; {@code 'a'} (0x61) &lt; the multi-byte UTF-8 encoding of
 * {@code 'Ä'} (0xC3 0x84) — giving {@code Apple, Zebra, apple, Ähnlich}. Under {@code en_US.utf8},
 * a locale-aware collation, case and diacritics are folded near their base letter instead — giving
 * {@code Ähnlich, apple, Apple, Zebra} (confirmed empirically against a real {@code postgres:16}
 * container with {@code datcollate=C}: see this task's report). Every ASCII-only fixture (as {@code
 * FindNodesApiIT}'s {@code NAME_ASC}/{@code NAME_DESC} cases are) sorts IDENTICALLY under both
 * collations, which is exactly why the existing suite never caught the regression.
 */
@QuarkusTest
@QuarkusTestResource(value = CLocalePostgresTestResource.class, restrictToAnnotatedClass = true)
class CollationOrderingDalTest {

  @Inject NodeRepository nodeRepository;
  @Inject EntityManager entityManager;

  private static String id() {
    return UUID.randomUUID().toString();
  }

  private String persist(String name, String owner) {
    String nodeId = id();
    long now = System.currentTimeMillis();
    Node node =
        new Node(
            nodeId,
            owner,
            owner,
            "LOCAL_ROOT",
            now,
            now,
            name,
            "",
            NodeType.TEXT,
            "LOCAL_ROOT",
            0L);
    entityManager.persist(node);
    return nodeId;
  }

  @Test
  @TestTransaction
  void nameOrderingHonoursTheResolvedDatabaseCollation() {
    String owner = id();
    // Deliberately unordered insertion, so the assertion cannot pass by insertion-order accident.
    String zebra = persist("Zebra", owner);
    String umlaut = persist("Ähnlich", owner);
    String upperApple = persist("Apple", owner);
    String lowerApple = persist("apple", owner);
    entityManager.flush();

    List<String> namesInOrder =
        nodeRepository
            .getNodes(
                List.of(zebra, umlaut, upperApple, lowerApple), Optional.of(NodeSort.NAME_ASC))
            .map(Node::getFullName)
            .toList();

    // en_US.utf8 (the fallback CollationRepositoryImpl applies once it detects the database's own
    // "C" default): locale-aware order, diacritics/case folded near their base letter.
    assertThat(namesInOrder).containsExactly("Ähnlich", "apple", "Apple", "Zebra");
  }
}
