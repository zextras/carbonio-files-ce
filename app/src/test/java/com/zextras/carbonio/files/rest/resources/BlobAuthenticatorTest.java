// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.rest.resources;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.zextras.carbonio.files.dal.dao.UserMyself;
import com.zextras.carbonio.files.dal.dao.UserStatus;
import com.zextras.carbonio.files.dal.dao.UserType;
import com.zextras.carbonio.files.dal.repositories.interfaces.UserRepository;
import com.zextras.carbonio.files.graphql.spi.UserAccessPolicy;
import jakarta.ws.rs.WebApplicationException;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class BlobAuthenticatorTest {

  private static final String TOKEN = "token";
  private static final String COOKIES = "ZM_AUTH_TOKEN=token";

  private UserRepository userRepository;
  private UserAccessPolicy userAccessPolicy;
  private BlobAuthenticator authenticator;
  private UserMyself user;

  @BeforeEach
  void setUp() {
    userRepository = mock(UserRepository.class);
    userAccessPolicy = mock(UserAccessPolicy.class);
    authenticator = new BlobAuthenticator(userRepository, userAccessPolicy);
    user = mock(UserMyself.class);
    when(user.getStatus()).thenReturn(UserStatus.ACTIVE);
    when(user.getType()).thenReturn(UserType.INTERNAL);
    when(user.getCarbonioAttributes()).thenReturn(Map.of("carbonioFeatureFilesEnabled", "TRUE"));
    when(userRepository.getUserMyselfByCookie(COOKIES)).thenReturn(Optional.of(user));
  }

  private static int status(Throwable t) {
    return ((WebApplicationException) t).getResponse().getStatus();
  }

  @Test
  void givenAllowedUserWhenRequireUserThenUserIsReturned() {
    when(userAccessPolicy.isAllowed(user)).thenReturn(true);

    assertThat(authenticator.requireUser(null, TOKEN)).isSameAs(user);
  }

  @Test
  void givenDeniedUserWhenRequireUserThen403() {
    when(userAccessPolicy.isAllowed(user)).thenReturn(false);

    assertThatThrownBy(() -> authenticator.requireUser(null, TOKEN))
        .isInstanceOf(WebApplicationException.class)
        .satisfies(t -> assertThat(status(t)).isEqualTo(403));
  }

  @Test
  void givenDeniedUserWhenRequireAuthenticatedUserThenUserIsReturnedWithoutPolicyCheck() {
    when(userAccessPolicy.isAllowed(any())).thenReturn(false);

    assertThat(authenticator.requireAuthenticatedUser(null, TOKEN)).isSameAs(user);
    verify(userAccessPolicy, never()).isAllowed(any());
  }

  @Test
  void givenMissingTokenWhenRequireUserThen401() {
    assertThatThrownBy(() -> authenticator.requireUser(null, null))
        .satisfies(t -> assertThat(status(t)).isEqualTo(401));
    verify(userAccessPolicy, never()).isAllowed(any());
  }

  @Test
  void givenFeatureDisabledWhenRequireUserThen403BeforeThePolicy() {
    when(user.getCarbonioAttributes()).thenReturn(Map.of("carbonioFeatureFilesEnabled", "FALSE"));

    assertThatThrownBy(() -> authenticator.requireUser(null, TOKEN))
        .satisfies(t -> assertThat(status(t)).isEqualTo(403));
    verify(userAccessPolicy, never()).isAllowed(any());
  }
}
