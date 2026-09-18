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

@QuarkusTest
@QuarkusTestResource(FilesStackTestResource.class)
class ConfigHierarchicalPocIT {

  @Inject ConfigResolver resolver;

  @Inject ConfigAdminService adminService;

  @AfterEach
  void cleanup() {
    adminService.delete(ScopeType.ACCOUNT, "acc1", "poc.key");
    adminService.delete(ScopeType.COS, "cos1", "poc.key");
    adminService.delete(ScopeType.DOMAIN, "dom1", "poc.key");
    adminService.delete(ScopeType.ACCOUNT, "acc1", "poc.default.key");
  }

  @Test
  void hierarchyPrecedence() {
    adminService.put(ScopeType.ACCOUNT, "acc1", "poc.key", "vAcc");
    adminService.put(ScopeType.COS, "cos1", "poc.key", "vCos");
    adminService.put(ScopeType.DOMAIN, "dom1", "poc.key", "vDom");

    assertThat(resolver.get("acc1", "cos1", "dom1", "poc.key")).hasValue("vAcc");
    assertThat(resolver.get(null, "cos1", "dom1", "poc.key")).hasValue("vCos");
    assertThat(resolver.get(null, null, "dom1", "poc.key")).hasValue("vDom");
  }

  @Test
  void nullScopesWithNoRowsFallsBack() {
    assertThat(resolver.get(null, null, null, "poc.key")).isEmpty();
  }

  @Test
  void globalDefaultAndDbOverride() {
    assertThat(resolver.get(null, null, null, "poc.default.key")).hasValue("global-value");

    adminService.put(ScopeType.ACCOUNT, "acc1", "poc.default.key", "override");
    assertThat(resolver.get("acc1", null, null, "poc.default.key")).hasValue("override");
  }
}
