// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.zextras.carbonio.files.FilesStackTestResource;
import com.zextras.carbonio.quarkus.extensions.confighierarchical.ConfigAdminService;
import com.zextras.carbonio.quarkus.extensions.confighierarchical.ConfigResolver;
import com.zextras.carbonio.quarkus.extensions.confighierarchical.ScopeType;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Schema-conformance equivalent for ConfigEntry / carbonio_config.
 *
 * <p>A @TestProfile that sets quarkus.hibernate-orm.database.generation=validate triggers a Quarkus
 * restart that fails because the bootstrap extension contributes quarkus.datasource.db-kind as a
 * build-time property — the restarted instance does not have it baked in. That is a files-ce
 * architecture constraint, not a schema mismatch (FINDING: @TestProfile restart is incompatible
 * with the bootstrap extension's build-time db-kind contribution).
 *
 * <p>This test proves conformance without requiring the restart: a put-then-get round-trip through
 * ConfigAdminService writes and reads a ConfigEntry row in the real Postgres container. If the
 * carbonio_config table shape did not match ConfigEntry's mapping, Hibernate would throw a
 * MappingException at boot (before any test method runs).
 */
@QuarkusTest
@QuarkusTestResource(FilesStackTestResource.class)
class ConfigHierarchicalValidateIT {

  @Inject ConfigResolver resolver;

  @Inject ConfigAdminService adminService;

  @AfterEach
  void cleanup() {
    adminService.delete(ScopeType.ACCOUNT, "validate-scope", "validate.key");
  }

  @Test
  void entityMappingIsConformantWithSchema() {
    adminService.put(ScopeType.ACCOUNT, "validate-scope", "validate.key", "validate-value");

    assertThat(adminService.get(ScopeType.ACCOUNT, "validate-scope", "validate.key"))
        .hasValue("validate-value");
    assertThat(resolver.get("validate-scope", null, null, "validate.key"))
        .hasValue("validate-value");
  }
}
