// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.graphql.auth;

import com.zextras.carbonio.files.dal.dao.UserMyself;
import io.quarkus.security.UnauthorizedException;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.enterprise.context.RequestScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;

@RequestScoped
public class AuthenticatedUserProducer {

  @Inject SecurityIdentity identity;

  @Produces
  @RequestScoped
  @AuthenticatedUser
  public UserMyself authenticatedUser() {
    UserMyself user = identity.getAttribute(FilesGraphQLAuthMechanism.IDENTITY_ATTRIBUTE);
    if (user == null) {
      throw new UnauthorizedException();
    }
    return user;
  }

  public String cookies() {
    return identity.getAttribute("cookies");
  }
}
