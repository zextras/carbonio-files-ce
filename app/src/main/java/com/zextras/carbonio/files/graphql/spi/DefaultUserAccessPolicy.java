// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.graphql.spi;

import com.zextras.carbonio.files.dal.dao.UserMyself;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class DefaultUserAccessPolicy implements UserAccessPolicy {

  @Override
  public boolean isAllowed(UserMyself user) {
    return true;
  }
}
