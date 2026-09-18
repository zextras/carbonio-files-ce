// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.zextras.carbonio.files.FilesStackTestResource;
import com.zextras.carbonio.quarkus.extensions.confighierarchical.ConfigAdminService;
import com.zextras.carbonio.quarkus.extensions.confighierarchical.ConfigResolver;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Schema-conformance equivalent for the 3 per-scope config tables.
 *
 * <p>A @TestProfile that sets quarkus.hibernate-orm.database.generation=validate triggers a Quarkus
 * restart that fails because the bootstrap extension contributes quarkus.datasource.db-kind as a
 * build-time property — the restarted instance does not have it baked in. That is a files-ce
 * architecture constraint, not a schema mismatch (FINDING: @TestProfile restart is incompatible
 * with the bootstrap extension's build-time db-kind contribution).
 *
 * <p>This test proves conformance without requiring the restart: a set-then-getRaw round-trip
 * through ConfigAdminService writes and reads a row in the real Postgres container. If the 3 table
 * shapes did not match their entity mappings, Hibernate would throw a MappingException at boot
 * (before any test method runs).
 */
@QuarkusTest
@QuarkusTestResource(FilesStackTestResource.class)
class ConfigHierarchicalValidateIT {

  private static final String SCOPE_ID = "validate-scope";
  private static final String KEY = "validate.key";

  @Inject ConfigResolver resolver;

  @Inject ConfigAdminService adminService;

  @AfterEach
  void cleanup() {
    adminService.deleteForAccount(SCOPE_ID, KEY);
  }

  @Test
  void entityMappingIsConformantWithSchema() {
    adminService.setForAccount(SCOPE_ID, KEY, "validate-value");

    assertThat(adminService.getRawFromAccount(SCOPE_ID, KEY)).hasValue("validate-value");
    assertThat(resolver.get(SCOPE_ID, null, null, KEY)).hasValue("validate-value");
  }
}
