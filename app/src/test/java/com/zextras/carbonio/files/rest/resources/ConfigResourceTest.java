// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.rest.resources;

import static com.zextras.carbonio.files.config.HierarchicalConfigKeys.HierarchicalConfig.SHARES_ENABLED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.zextras.carbonio.files.dal.dao.UserId;
import com.zextras.carbonio.files.dal.dao.UserMyself;
import com.zextras.carbonio.files.dal.dao.UserStatus;
import com.zextras.carbonio.files.dal.dao.UserType;
import com.zextras.carbonio.quarkus.extensions.confighierarchical.ConfigAdminService;
import com.zextras.carbonio.quarkus.extensions.confighierarchical.ConfigResolver;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.jboss.resteasy.reactive.RestResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link ConfigResource} (caller config read + admin per-scope config write). */
class ConfigResourceTest {

  private BlobAuthenticator authenticator;
  private AdminAuthenticator adminAuthenticator;
  private ConfigResolver configResolver;
  private ConfigAdminService configAdminService;
  private ConfigResource resource;

  @BeforeEach
  void setUp() {
    authenticator = mock(BlobAuthenticator.class);
    adminAuthenticator = mock(AdminAuthenticator.class);
    configResolver = mock(ConfigResolver.class);
    configAdminService = mock(ConfigAdminService.class);
    resource =
        new ConfigResource(authenticator, adminAuthenticator, configResolver, configAdminService);
  }

  private UserMyself caller() {
    UserMyself user =
        new UserMyself(
            new UserId("acc-1"),
            "user@example.com",
            "User",
            "example.com",
            UserStatus.ACTIVE,
            Locale.ENGLISH,
            UserType.INTERNAL,
            List.of());
    user.setCosId("cos-1");
    user.setDomainId("dom-1");
    return user;
  }

  // ---- GET /config (caller's resolved config) ----

  @Test
  void getMyConfig_resolvesEveryKeyForTheCallerAccountCosDomain() {
    when(authenticator.requireUser("cookie", "tok")).thenReturn(caller());
    when(configResolver.get(
            Optional.of("acc-1"), Optional.of("cos-1"), Optional.of("dom-1"), SHARES_ENABLED))
        .thenReturn(Optional.of("false"));

    RestResponse<Map<String, String>> response = resource.getMyConfig("cookie", "tok");

    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(response.getEntity()).containsEntry(SHARES_ENABLED, "false");
  }

  @Test
  void getMyConfig_includesKeyWithNullWhenResolverEmpty() {
    when(authenticator.requireUser("cookie", "tok")).thenReturn(caller());
    when(configResolver.get(
            Optional.of("acc-1"), Optional.of("cos-1"), Optional.of("dom-1"), SHARES_ENABLED))
        .thenReturn(Optional.empty());

    RestResponse<Map<String, String>> response = resource.getMyConfig("cookie", "tok");

    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(response.getEntity()).containsKey(SHARES_ENABLED);
    assertThat(response.getEntity().get(SHARES_ENABLED)).isNull();
  }

  // ---- PUT /config/admin/{scope}/{scopeId} (admin write) ----

  @Test
  void setConfig_cos_delegatesToConfigAdminService() {
    RestResponse<Void> response =
        resource.setConfig(
            "admin-tok",
            "cos",
            "cos-9",
            new ConfigResource.SetConfigRequest(SHARES_ENABLED, "false"));

    verify(adminAuthenticator).requireGlobalAdmin("admin-tok");
    verify(configAdminService).setForCos("cos-9", SHARES_ENABLED, "false");
    assertThat(response.getStatus()).isEqualTo(204);
  }

  @Test
  void setConfig_account_delegatesToConfigAdminService() {
    resource.setConfig(
        "admin-tok",
        "account",
        "acc-9",
        new ConfigResource.SetConfigRequest(SHARES_ENABLED, "true"));
    verify(configAdminService).setForAccount("acc-9", SHARES_ENABLED, "true");
  }

  @Test
  void setConfig_domain_delegatesToConfigAdminService() {
    resource.setConfig(
        "admin-tok",
        "domain",
        "dom-9",
        new ConfigResource.SetConfigRequest(SHARES_ENABLED, "false"));
    verify(configAdminService).setForDomain("dom-9", SHARES_ENABLED, "false");
  }

  @Test
  void setConfig_unknownScope_returns400_andDoesNotWrite() {
    assertThatThrownBy(
            () ->
                resource.setConfig(
                    "admin-tok",
                    "bogus",
                    "x",
                    new ConfigResource.SetConfigRequest(SHARES_ENABLED, "false")))
        .isInstanceOf(WebApplicationException.class)
        .extracting(e -> ((WebApplicationException) e).getResponse().getStatus())
        .isEqualTo(Response.Status.BAD_REQUEST.getStatusCode());

    verifyNoInteractions(configAdminService);
  }

  @Test
  void setConfig_missingKey_returns400() {
    assertThatThrownBy(
            () ->
                resource.setConfig(
                    "admin-tok",
                    "cos",
                    "cos-9",
                    new ConfigResource.SetConfigRequest("  ", "false")))
        .isInstanceOf(WebApplicationException.class)
        .extracting(e -> ((WebApplicationException) e).getResponse().getStatus())
        .isEqualTo(Response.Status.BAD_REQUEST.getStatusCode());

    verify(configAdminService, never())
        .setForCos(
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.any());
  }

  @Test
  void setConfig_nonAdmin_propagatesTheAuthenticatorRejection() {
    when(adminAuthenticator.requireGlobalAdmin("user-tok"))
        .thenThrow(
            new WebApplicationException(Response.status(Response.Status.UNAUTHORIZED).build()));

    assertThatThrownBy(
            () ->
                resource.setConfig(
                    "user-tok",
                    "cos",
                    "cos-9",
                    new ConfigResource.SetConfigRequest(SHARES_ENABLED, "false")))
        .isInstanceOf(WebApplicationException.class)
        .extracting(e -> ((WebApplicationException) e).getResponse().getStatus())
        .isEqualTo(Response.Status.UNAUTHORIZED.getStatusCode());

    verifyNoInteractions(configAdminService);
  }
}
