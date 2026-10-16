// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.graphql.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.zextras.carbonio.files.dal.dao.UserMyself;
import com.zextras.carbonio.files.dal.dao.UserStatus;
import com.zextras.carbonio.files.dal.dao.UserType;
import com.zextras.carbonio.files.dal.repositories.interfaces.UserRepository;
import io.quarkus.security.AuthenticationFailedException;
import io.quarkus.security.identity.AuthenticationRequestContext;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.vertx.http.runtime.security.HttpSecurityUtils;
import io.smallrye.mutiny.Uni;
import io.vertx.ext.web.RoutingContext;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class FilesIdentityProviderTest {

  private static final String COOKIES = "ZM_AUTH_TOKEN=token";
  private static final String URI = "/graphql/";

  private UserRepository userRepository;
  private RoutingContext routingContext;
  private AuthenticationRequestContext requestContext;
  private FilesAuthenticationRequest request;
  private FilesIdentityProvider provider;

  @BeforeEach
  void setUp() {
    userRepository = mock(UserRepository.class);
    routingContext = mock(RoutingContext.class);
    requestContext = mock(AuthenticationRequestContext.class);
    when(requestContext.runBlocking(any())).thenReturn(Uni.createFrom().nullItem());
    request =
        (FilesAuthenticationRequest)
            HttpSecurityUtils.setRoutingContextAttribute(
                new FilesAuthenticationRequest(COOKIES, URI), routingContext);
    provider = new FilesIdentityProvider(userRepository);
  }

  private Supplier<SecurityIdentity> captureBlockingSupplier() {
    provider.authenticate(request, requestContext);
    @SuppressWarnings("unchecked")
    ArgumentCaptor<Supplier<SecurityIdentity>> captor = ArgumentCaptor.forClass(Supplier.class);
    verify(requestContext).runBlocking(captor.capture());
    return captor.getValue();
  }

  private UserMyself user(UserStatus status, UserType type, Map<String, String> attributes) {
    UserMyself user = mock(UserMyself.class);
    when(user.getStatus()).thenReturn(status);
    when(user.getType()).thenReturn(type);
    when(user.getCarbonioAttributes()).thenReturn(attributes);
    when(user.getEmail()).thenReturn("user@example.com");
    return user;
  }

  @Test
  void givenARequestWhenAuthenticatingThenTheLookupIsDelegatedToRunBlocking() {
    Supplier<SecurityIdentity> supplier = captureBlockingSupplier();

    verifyNoInteractions(userRepository);
    UserMyself user =
        user(UserStatus.ACTIVE, UserType.INTERNAL, Map.of("carbonioFeatureFilesEnabled", "TRUE"));
    when(userRepository.getUserMyselfByCookie(COOKIES)).thenReturn(Optional.of(user));

    SecurityIdentity identity = supplier.get();

    assertThat(identity.isAnonymous()).isFalse();
    assertThat(identity.getPrincipal().getName()).isEqualTo("user@example.com");
    assertThat((String) identity.getAttribute("cookies")).isEqualTo(COOKIES);
    assertThat((Object) identity.getAttribute(FilesGraphQLAuthMechanism.IDENTITY_ATTRIBUTE))
        .isNotNull();
  }

  @Test
  void givenAnUnresolvableCookieWhenResolvingThen401IsStoredAndAuthenticationFails() {
    Supplier<SecurityIdentity> supplier = captureBlockingSupplier();
    when(userRepository.getUserMyselfByCookie(COOKIES)).thenReturn(Optional.empty());

    assertThatThrownBy(supplier::get)
        .isInstanceOf(AuthenticationFailedException.class)
        .hasMessage("Unable to find requested user");
    verify(routingContext).put(FilesGraphQLAuthMechanism.CTX_STATUS, 401);
    verify(routingContext)
        .put(
            FilesGraphQLAuthMechanism.CTX_MESSAGE,
            "Failed to authenticate request /graphql/: Unable to find requested user");
  }

  @Test
  void givenAnInactiveUserWhenResolvingThen403IsStored() {
    Supplier<SecurityIdentity> supplier = captureBlockingSupplier();
    UserMyself user =
        user(
            UserStatus.MAINTENANCE,
            UserType.INTERNAL,
            Map.of("carbonioFeatureFilesEnabled", "TRUE"));
    when(userRepository.getUserMyselfByCookie(COOKIES)).thenReturn(Optional.of(user));

    assertThatThrownBy(supplier::get).isInstanceOf(AuthenticationFailedException.class);
    verify(routingContext).put(FilesGraphQLAuthMechanism.CTX_STATUS, 403);
    verify(routingContext)
        .put(
            FilesGraphQLAuthMechanism.CTX_MESSAGE,
            "Failed to authorize request /graphql/: User is not active");
  }
}
