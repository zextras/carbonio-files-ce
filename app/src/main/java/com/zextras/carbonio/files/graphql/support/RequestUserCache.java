// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.graphql.support;

import com.zextras.carbonio.files.dal.dao.UserInfo;
import jakarta.enterprise.context.RequestScoped;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@RequestScoped
public class RequestUserCache {

  private final Map<String, Optional<UserInfo>> byId = new ConcurrentHashMap<>();

  public Map<String, Optional<UserInfo>> entries() {
    return byId;
  }
}
