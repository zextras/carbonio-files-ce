// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.it;

import static com.zextras.carbonio.files.config.HierarchicalConfigKeys.HierarchicalConfig.SHARES_ENABLED;
import static org.assertj.core.api.Assertions.assertThat;

import com.zextras.carbonio.files.FilesStackTestResource;
import com.zextras.carbonio.quarkus.extensions.confighierarchical.ConfigAdminService;
import com.zextras.carbonio.quarkus.extensions.confighierarchical.ConfigResolver;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

@QuarkusTest
@QuarkusTestResource(FilesStackTestResource.class)
class HierarchicalConfigIT {

  private static final String ACC = "account-1";
  private static final String COS = "cos-1";

  @Inject ConfigResolver resolver;

  @Inject ConfigAdminService adminService;

  @AfterEach
  void cleanup() {
    adminService.deleteForAccount(ACC, SHARES_ENABLED);
    adminService.deleteForCos(COS, SHARES_ENABLED);
  }

  @Test
  void skipAbsent_cosWinsWhenNoAccountRow() {
    adminService.setForCos(COS, SHARES_ENABLED, "false");

    assertThat(resolver.get(Optional.of(ACC), Optional.of(COS), SHARES_ENABLED)).hasValue("false");
  }

  @Test
  void skipAbsent_accountWinsWhenNoCosRow() {
    adminService.setForAccount(ACC, SHARES_ENABLED, "false");

    assertThat(resolver.get(Optional.of(ACC), Optional.of(COS), SHARES_ENABLED)).hasValue("false");
  }

  @Test
  void accountWinsOverCos() {
    adminService.setForAccount(ACC, SHARES_ENABLED, "false");
    adminService.setForCos(COS, SHARES_ENABLED, "true");

    assertThat(resolver.get(Optional.of(ACC), Optional.of(COS), SHARES_ENABLED)).hasValue("false");
  }

  @Test
  void perScopeDbValuesIndependent() {
    adminService.setForAccount(ACC, SHARES_ENABLED, "false");
    adminService.setForCos(COS, SHARES_ENABLED, "true");

    assertThat(adminService.getRawFromAccount(ACC, SHARES_ENABLED)).hasValue("false");
    assertThat(adminService.getRawFromCos(COS, SHARES_ENABLED)).hasValue("true");
  }

  @Test
  void baseDefaultTrueWhenNoRows() {
    assertThat(resolver.get(Optional.of(ACC), Optional.of(COS), SHARES_ENABLED)).hasValue("true");
  }

  @Test
  void undeclaredKeyNoRowsNoDefault_returnsEmpty() {
    assertThat(resolver.get(Optional.of(ACC), Optional.of(COS), "no.such.key")).isEmpty();
  }
}
