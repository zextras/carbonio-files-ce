// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.rest.resources;

import static com.zextras.carbonio.files.config.HierarchicalConfigKeys.HierarchicalConfig.SHARES_ENABLED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.zextras.carbonio.quarkus.extensions.confighierarchical.ConfigAdminService;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import java.util.Map;
import java.util.Optional;
import org.jboss.resteasy.reactive.RestResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link AdminConfigResource}: admin per-scope raw read (no hierarchy resolution) +
 * per-scope write. All endpoints are global-admin gated.
 */
class AdminConfigResourceTest {

  private AdminAuthenticator adminAuthenticator;
  private ConfigAdminService configAdminService;
  private AdminConfigResource resource;

  @BeforeEach
  void setUp() {
    adminAuthenticator = mock(AdminAuthenticator.class);
    configAdminService = mock(ConfigAdminService.class);
    resource = new AdminConfigResource(adminAuthenticator, configAdminService);
  }

  // ---- GET /admin/config/{scope}/{scopeId} — raw, no resolution ----

  @Test
  void getScopeConfig_cos_returnsOnlyTheOverridesSetAtThisScope() {
    when(configAdminService.getRawFromCos("cos-9", SHARES_ENABLED))
        .thenReturn(Optional.of("false"));

    RestResponse<Map<String, String>> response =
        resource.getScopeConfig("admin-tok", "cos", "cos-9");

    verify(adminAuthenticator).requireGlobalAdmin("admin-tok");
    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(response.getEntity()).containsEntry(SHARES_ENABLED, "false");
  }

  @Test
  void getScopeConfig_account_dispatchesToAccountRawGetter() {
    when(configAdminService.getRawFromAccount("acc-9", SHARES_ENABLED))
        .thenReturn(Optional.of("true"));

    RestResponse<Map<String, String>> response =
        resource.getScopeConfig("admin-tok", "account", "acc-9");

    assertThat(response.getEntity()).containsEntry(SHARES_ENABLED, "true");
  }

  @Test
  void getScopeConfig_omitsKeysWithNoOverrideAtThisScope() {
    // The domain does not override shares-enabled → the key is absent from the response (not null),
    // so the panel can tell "not overridden here" from "overridden to empty".
    when(configAdminService.getRawFromDomain("dom-9", SHARES_ENABLED)).thenReturn(Optional.empty());

    RestResponse<Map<String, String>> response =
        resource.getScopeConfig("admin-tok", "domain", "dom-9");

    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(response.getEntity()).isEmpty();
  }

  @Test
  void getScopeConfig_unknownScope_returns400() {
    assertThatThrownBy(() -> resource.getScopeConfig("admin-tok", "bogus", "x"))
        .isInstanceOf(WebApplicationException.class)
        .extracting(e -> ((WebApplicationException) e).getResponse().getStatus())
        .isEqualTo(Response.Status.BAD_REQUEST.getStatusCode());

    verifyNoInteractions(configAdminService);
  }

  @Test
  void getScopeConfig_nonAdmin_propagatesTheAuthenticatorRejection() {
    when(adminAuthenticator.requireGlobalAdmin("user-tok"))
        .thenThrow(
            new WebApplicationException(Response.status(Response.Status.UNAUTHORIZED).build()));

    assertThatThrownBy(() -> resource.getScopeConfig("user-tok", "cos", "cos-9"))
        .isInstanceOf(WebApplicationException.class)
        .extracting(e -> ((WebApplicationException) e).getResponse().getStatus())
        .isEqualTo(Response.Status.UNAUTHORIZED.getStatusCode());

    verifyNoInteractions(configAdminService);
  }

  // ---- PUT /admin/config/{scope}/{scopeId} — write ----

  @Test
  void setScopeConfig_cos_delegatesAndReturns204() {
    RestResponse<Void> response =
        resource.setScopeConfig(
            "admin-tok",
            "cos",
            "cos-9",
            new AdminConfigResource.SetConfigRequest(SHARES_ENABLED, "false"));

    verify(adminAuthenticator).requireGlobalAdmin("admin-tok");
    verify(configAdminService).setForCos("cos-9", SHARES_ENABLED, "false");
    assertThat(response.getStatus()).isEqualTo(204);
  }

  @Test
  void setScopeConfig_account_delegates() {
    resource.setScopeConfig(
        "admin-tok",
        "account",
        "acc-9",
        new AdminConfigResource.SetConfigRequest(SHARES_ENABLED, "true"));
    verify(configAdminService).setForAccount("acc-9", SHARES_ENABLED, "true");
  }

  @Test
  void setScopeConfig_domain_delegates() {
    resource.setScopeConfig(
        "admin-tok",
        "domain",
        "dom-9",
        new AdminConfigResource.SetConfigRequest(SHARES_ENABLED, "false"));
    verify(configAdminService).setForDomain("dom-9", SHARES_ENABLED, "false");
  }

  @Test
  void setScopeConfig_unknownScope_returns400_andDoesNotWrite() {
    assertThatThrownBy(
            () ->
                resource.setScopeConfig(
                    "admin-tok",
                    "bogus",
                    "x",
                    new AdminConfigResource.SetConfigRequest(SHARES_ENABLED, "false")))
        .isInstanceOf(WebApplicationException.class)
        .extracting(e -> ((WebApplicationException) e).getResponse().getStatus())
        .isEqualTo(Response.Status.BAD_REQUEST.getStatusCode());

    verify(configAdminService, never()).setForCos(anyString(), anyString(), any());
  }

  @Test
  void setScopeConfig_missingKey_returns400() {
    assertThatThrownBy(
            () ->
                resource.setScopeConfig(
                    "admin-tok",
                    "cos",
                    "cos-9",
                    new AdminConfigResource.SetConfigRequest("  ", "false")))
        .isInstanceOf(WebApplicationException.class)
        .extracting(e -> ((WebApplicationException) e).getResponse().getStatus())
        .isEqualTo(Response.Status.BAD_REQUEST.getStatusCode());

    verifyNoInteractions(configAdminService);
  }

  @Test
  void setScopeConfig_missingValue_returns400() {
    assertThatThrownBy(
            () ->
                resource.setScopeConfig(
                    "admin-tok",
                    "cos",
                    "cos-9",
                    new AdminConfigResource.SetConfigRequest(SHARES_ENABLED, null)))
        .isInstanceOf(WebApplicationException.class)
        .extracting(e -> ((WebApplicationException) e).getResponse().getStatus())
        .isEqualTo(Response.Status.BAD_REQUEST.getStatusCode());

    verifyNoInteractions(configAdminService);
  }

  @Test
  void setScopeConfig_nonAdmin_propagatesTheAuthenticatorRejection() {
    when(adminAuthenticator.requireGlobalAdmin("user-tok"))
        .thenThrow(
            new WebApplicationException(Response.status(Response.Status.UNAUTHORIZED).build()));

    assertThatThrownBy(
            () ->
                resource.setScopeConfig(
                    "user-tok",
                    "cos",
                    "cos-9",
                    new AdminConfigResource.SetConfigRequest(SHARES_ENABLED, "false")))
        .isInstanceOf(WebApplicationException.class)
        .extracting(e -> ((WebApplicationException) e).getResponse().getStatus())
        .isEqualTo(Response.Status.UNAUTHORIZED.getStatusCode());

    verifyNoInteractions(configAdminService);
  }
}
