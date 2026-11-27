// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.graphql.spi;

import com.zextras.carbonio.files.dal.dao.UserMyself;

/**
 * Extension seam deciding whether an already authenticated user may use the service. It is
 * consulted by both the GraphQL identity provider and the REST requester resolution. The default
 * implementation allows every user.
 */
public interface UserAccessPolicy {

  boolean isAllowed(UserMyself user);
}
