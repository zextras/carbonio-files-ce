// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.rest.resources;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.zextras.carbonio.files.dal.dao.UserId;
import com.zextras.carbonio.files.dal.dao.UserMyself;
import com.zextras.carbonio.files.dal.dao.UserStatus;
import com.zextras.carbonio.files.dal.dao.UserType;
import com.zextras.carbonio.files.dal.repositories.interfaces.UserRepository;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link AdminAuthenticator}: the admin-only gate for the config write endpoints. It
 * resolves the caller from the {@code ZM_ADMIN_AUTH_TOKEN} via user-management {@code /myself} and
 * requires {@code isGlobalAdmin == true}, else 401 — mirroring powerstore's global-admin gate.
 */
class AdminAuthenticatorTest {

  private UserRepository userRepository;
  private AdminAuthenticator adminAuthenticator;

  @BeforeEach
  void setUp() {
    userRepository = mock(UserRepository.class);
    adminAuthenticator = new AdminAuthenticator(userRepository);
  }

  private UserMyself user(boolean globalAdmin) {
    UserMyself user =
        new UserMyself(
            new UserId("admin-1"),
            "admin@example.com",
            "Admin",
            "example.com",
            UserStatus.ACTIVE,
            Locale.ENGLISH,
            UserType.INTERNAL,
            List.of());
    user.setCosId("cos-1");
    user.setDomainId("dom-1");
    user.setGlobalAdmin(globalAdmin);
    return user;
  }

  @Test
  void globalAdminTokenReturnsTheUser() {
    UserMyself admin = user(true);
    when(userRepository.getUserMyselfByToken("admin-token")).thenReturn(Optional.of(admin));

    assertThat(adminAuthenticator.requireGlobalAdmin("admin-token")).isSameAs(admin);
  }

  @Test
  void nonGlobalAdminIsRejectedWith401() {
    when(userRepository.getUserMyselfByToken("user-token")).thenReturn(Optional.of(user(false)));

    assertThatThrownBy(() -> adminAuthenticator.requireGlobalAdmin("user-token"))
        .isInstanceOf(WebApplicationException.class)
        .extracting(e -> ((WebApplicationException) e).getResponse().getStatus())
        .isEqualTo(Response.Status.UNAUTHORIZED.getStatusCode());
  }

  @Test
  void unresolvableTokenIsRejectedWith401() {
    when(userRepository.getUserMyselfByToken("bad-token")).thenReturn(Optional.empty());

    assertThatThrownBy(() -> adminAuthenticator.requireGlobalAdmin("bad-token"))
        .isInstanceOf(WebApplicationException.class)
        .extracting(e -> ((WebApplicationException) e).getResponse().getStatus())
        .isEqualTo(Response.Status.UNAUTHORIZED.getStatusCode());
  }

  @Test
  void missingTokenIsRejectedWith401_withoutCallingUserManagement() {
    assertThatThrownBy(() -> adminAuthenticator.requireGlobalAdmin("  "))
        .isInstanceOf(WebApplicationException.class)
        .extracting(e -> ((WebApplicationException) e).getResponse().getStatus())
        .isEqualTo(Response.Status.UNAUTHORIZED.getStatusCode());

    verify(userRepository, never()).getUserMyselfByToken(org.mockito.ArgumentMatchers.anyString());
  }

  @Test
  void nullTokenIsRejectedWith401() {
    assertThatThrownBy(() -> adminAuthenticator.requireGlobalAdmin(null))
        .isInstanceOf(WebApplicationException.class);
  }
}
