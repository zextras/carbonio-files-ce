// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.it;

import static com.zextras.carbonio.files.config.HierarchicalConfigKeys.HierarchicalConfig.MAX_VERSIONS;
import static com.zextras.carbonio.files.config.HierarchicalConfigKeys.HierarchicalConfig.SAMPLE_EMPTY;
import static com.zextras.carbonio.files.config.HierarchicalConfigKeys.HierarchicalConfig.SHARES_ENABLED;
import static org.assertj.core.api.Assertions.assertThat;

import com.zextras.carbonio.files.FilesStackTestResource;
import com.zextras.carbonio.quarkus.extensions.confighierarchical.ConfigAdminService;
import com.zextras.carbonio.quarkus.extensions.confighierarchical.ConfigResolver;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

@QuarkusTest
@QuarkusTestResource(FilesStackTestResource.class)
class ConfigHierarchicalPocIT {

  private static final String ACC = "poc-acc1";
  private static final String COS = "poc-cos1";
  private static final String DOM = "poc-dom1";

  @Inject ConfigResolver resolver;

  @Inject ConfigAdminService adminService;

  @AfterEach
  void cleanup() {
    adminService.deleteForAccount(ACC, MAX_VERSIONS);
    adminService.deleteForCos(COS, MAX_VERSIONS);
    adminService.deleteForDomain(DOM, MAX_VERSIONS);
    adminService.deleteForAccount(ACC, SAMPLE_EMPTY);
    adminService.deleteForCos(COS, SAMPLE_EMPTY);
    adminService.deleteForDomain(DOM, SAMPLE_EMPTY);
    adminService.deleteForAccount(ACC, SHARES_ENABLED);
    adminService.deleteForCos(COS, SHARES_ENABLED);
    adminService.deleteForDomain(DOM, SHARES_ENABLED);
  }

  @Test
  void skipAbsent_cosWinsWhenNoAccountRow() {
    adminService.setForDomain(DOM, MAX_VERSIONS, "vDom");
    adminService.setForCos(COS, MAX_VERSIONS, "vCos");

    assertThat(resolver.get(ACC, COS, DOM, MAX_VERSIONS)).hasValue("vCos");
  }

  @Test
  void skipAbsent_accountWinsWhenNoCosRow() {
    adminService.setForAccount(ACC, MAX_VERSIONS, "vAcc");
    adminService.setForDomain(DOM, MAX_VERSIONS, "vDom");

    assertThat(resolver.get(ACC, COS, DOM, MAX_VERSIONS)).hasValue("vAcc");
  }

  @Test
  void skipAbsent_domainWinsWhenOnlyDomainRow() {
    adminService.setForDomain(DOM, MAX_VERSIONS, "vDom");

    assertThat(resolver.get(ACC, COS, DOM, MAX_VERSIONS)).hasValue("vDom");
  }

  @Test
  void namespacedDefault_noRowsReturnBaseDefault() {
    assertThat(resolver.get(ACC, COS, DOM, MAX_VERSIONS)).hasValue("100");
  }

  @Test
  void namespacedDefault_scopeRowOverridesBaseDefault() {
    adminService.setForAccount(ACC, MAX_VERSIONS, "99");

    assertThat(resolver.get(ACC, COS, DOM, MAX_VERSIONS)).hasValue("99");
  }

  @Test
  void emptyDefault_noRowsReturnsEmpty() {
    assertThat(resolver.get(ACC, COS, DOM, SAMPLE_EMPTY)).isEmpty();
  }

  @Test
  void rawGetter_returnsOnlyDomainRowWithNoHierarchy() {
    adminService.setForAccount(ACC, MAX_VERSIONS, "vAcc");
    adminService.setForDomain(DOM, MAX_VERSIONS, "vDom");

    assertThat(adminService.getRawFromDomain(DOM, MAX_VERSIONS)).hasValue("vDom");
    assertThat(adminService.getRawFromAccount(ACC, MAX_VERSIONS)).hasValue("vAcc");
    assertThat(adminService.getRawFromCos(COS, MAX_VERSIONS)).isEmpty();
  }

  // Values are strings "true"/"false" — the extension is string-valued; boolean interpretation is
  // the caller's.

  @Test
  void sharesEnabled_hierarchyResolves() {
    adminService.setForDomain(DOM, SHARES_ENABLED, "false");
    adminService.setForCos(COS, SHARES_ENABLED, "true");

    // cos beats domain when account row is absent
    assertThat(resolver.get(ACC, COS, DOM, SHARES_ENABLED)).hasValue("true");

    adminService.setForAccount(ACC, SHARES_ENABLED, "false");

    // account is most-specific, wins over cos
    assertThat(resolver.get(ACC, COS, DOM, SHARES_ENABLED)).hasValue("false");
  }

  @Test
  void sharesEnabled_perScopeDbValuesIndependent() {
    adminService.setForAccount(ACC, SHARES_ENABLED, "false");
    adminService.setForCos(COS, SHARES_ENABLED, "true");
    adminService.setForDomain(DOM, SHARES_ENABLED, "false");

    assertThat(adminService.getRawFromAccount(ACC, SHARES_ENABLED)).hasValue("false");
    assertThat(adminService.getRawFromCos(COS, SHARES_ENABLED)).hasValue("true");
    assertThat(adminService.getRawFromDomain(DOM, SHARES_ENABLED)).hasValue("false");
  }

  @Test
  void sharesEnabled_baseDefaultTrueWhenNoRows() {
    // No DB rows set — falls back to hierarchical-config.shares-enabled=true in
    // application.properties
    assertThat(resolver.get(ACC, COS, DOM, SHARES_ENABLED)).hasValue("true");
  }
}
