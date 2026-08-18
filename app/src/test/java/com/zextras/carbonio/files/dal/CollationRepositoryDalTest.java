// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.dal;

import static org.assertj.core.api.Assertions.assertThat;

import com.zextras.carbonio.files.FilesStackTestResource;
import com.zextras.carbonio.files.dal.repositories.interfaces.CollationRepository;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Restores the deleted (Phase 7b) {@code CollationRepositoryIT}'s coverage of {@code
 * CollationRepositoryImpl}: the caching contract and the quoted-identifier shape of a resolved
 * collation, run against the real Testcontainers PostgreSQL instance so the native {@code
 * pg_database}/{@code pg_collation} queries are actually exercised.
 *
 * <p>Ported to the {@code *DalTest} in-process convention (see {@code NodeQueryDalTest}/{@code
 * NotificationDalTest}'s javadoc for the surefire-vs-failsafe rationale). Uses the SHARED {@link
 * FilesStackTestResource} deliberately (unlike {@link CollationOrderingDalTest}): this class only
 * asserts API-contract properties that hold regardless of which branch {@code
 * getValidCollateForQuery()} takes (stability across calls; IF a collation is resolved, it is a
 * quoted identifier) — it is not trying to force the {@code C}-detection branch, so it does not
 * need a dedicated non-default-collation container. Whether the shared container's own default
 * happens to be {@code C} or {@code en_US.utf8} is exactly the "environment dependent" behaviour
 * the original {@code CollationRepositoryIT} javadoc called out.
 */
@QuarkusTest
@QuarkusTestResource(FilesStackTestResource.class)
class CollationRepositoryDalTest {

  @Inject CollationRepository collationRepository;

  @Test
  void shouldReturnAConsistentResultAcrossCalls() {
    Optional<String> first = collationRepository.getValidCollateForQuery();
    Optional<String> second = collationRepository.getValidCollateForQuery();

    // The result depends on the PostgreSQL server's default collation, which is environment
    // dependent, but it must never throw and must be cached/stable across calls.
    assertThat(first).isNotNull();
    assertThat(second).isEqualTo(first);
  }

  @Test
  void ifPresentTheCollationShouldBeAQuotedIdentifier() {
    Optional<String> collate = collationRepository.getValidCollateForQuery();

    collate.ifPresent(
        value -> {
          assertThat(value).startsWith("\"").endsWith("\"");
          assertThat(value).isEqualTo("\"en_US.utf8\"");
        });
  }
}
