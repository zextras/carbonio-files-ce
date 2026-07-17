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
 * P2c: validates the clean Panache/JPA {@code CollationRepositoryImpl}, which replaces {@code
 * CollationRepositoryEbean}. Runs against the real Testcontainers PostgreSQL instance so the
 * native {@code pg_database}/{@code pg_collation} queries are actually exercised.
 */
@QuarkusTest
@QuarkusTestResource(FilesStackTestResource.class)
class CollationRepositoryIT {

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
