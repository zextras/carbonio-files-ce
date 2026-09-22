// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.rest.resources;

import static com.zextras.carbonio.files.config.HierarchicalConfigKeys.HierarchicalConfig.SHARES_ENABLED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.zextras.carbonio.files.dal.dao.UserId;
import com.zextras.carbonio.files.dal.dao.UserMyself;
import com.zextras.carbonio.files.dal.dao.UserStatus;
import com.zextras.carbonio.files.dal.dao.UserType;
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

/** Unit tests for {@link ConfigResource}: the caller's resolved (effective) config. */
class ConfigResourceTest {

  private BlobAuthenticator authenticator;
  private ConfigResolver configResolver;
  private ConfigResource resource;

  @BeforeEach
  void setUp() {
    authenticator = mock(BlobAuthenticator.class);
    configResolver = mock(ConfigResolver.class);
    resource = new ConfigResource(authenticator, configResolver);
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

  @Test
  void getMyConfig_resolvesEveryKeyForTheCallerAccountCosDomain() {
    when(authenticator.requireUser("cookie", "tok")).thenReturn(caller());
    when(configResolver.get(
            Optional.of("acc-1"), Optional.of("cos-1"), Optional.of("dom-1"), SHARES_ENABLED))
        .thenReturn(Optional.of("false"));

    RestResponse<Map<String, String>> response = resource.getMyConfig("cookie", "tok", null);

    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(response.getEntity()).containsEntry(SHARES_ENABLED, "false");
  }

  @Test
  void getMyConfig_includesEveryKeyWithNullWhenResolverEmpty() {
    when(authenticator.requireUser("cookie", "tok")).thenReturn(caller());
    when(configResolver.get(
            Optional.of("acc-1"), Optional.of("cos-1"), Optional.of("dom-1"), SHARES_ENABLED))
        .thenReturn(Optional.empty());

    RestResponse<Map<String, String>> response = resource.getMyConfig("cookie", "tok", null);

    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(response.getEntity()).containsKey(SHARES_ENABLED);
    assertThat(response.getEntity().get(SHARES_ENABLED)).isNull();
  }

  @Test
  void getMyConfig_withKeyQueryParam_resolvesOnlyThatDeclaredKey() {
    when(authenticator.requireUser("cookie", "tok")).thenReturn(caller());
    when(configResolver.get(
            Optional.of("acc-1"), Optional.of("cos-1"), Optional.of("dom-1"), SHARES_ENABLED))
        .thenReturn(Optional.of("false"));

    RestResponse<Map<String, String>> response =
        resource.getMyConfig("cookie", "tok", SHARES_ENABLED);

    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(response.getEntity())
        .containsExactly(org.assertj.core.api.Assertions.entry(SHARES_ENABLED, "false"));
  }

  @Test
  void getMyConfig_withUndeclaredKey_returns404_andDoesNotResolve() {
    when(authenticator.requireUser("cookie", "tok")).thenReturn(caller());

    assertThatThrownBy(() -> resource.getMyConfig("cookie", "tok", "does.not.exist"))
        .isInstanceOf(WebApplicationException.class)
        .extracting(e -> ((WebApplicationException) e).getResponse().getStatus())
        .isEqualTo(Response.Status.NOT_FOUND.getStatusCode());

    verifyNoInteractions(configResolver);
  }
}
