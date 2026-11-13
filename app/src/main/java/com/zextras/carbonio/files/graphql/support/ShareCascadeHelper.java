// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.graphql.support;

import com.zextras.carbonio.files.dal.dao.ebean.ACL;
import com.zextras.carbonio.files.dal.repositories.interfaces.ShareRepository;
import com.zextras.carbonio.files.utilities.NodeRules;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Optional;

@ApplicationScoped
public class ShareCascadeHelper {

  private final ShareRepository shareRepository;

  @Inject
  public ShareCascadeHelper(ShareRepository shareRepository) {
    this.shareRepository = shareRepository;
  }

  public void cascadeUpsertShare(
      String nodeId, String userId, ACL permission, Optional<Long> expiredAt) {
    if (NodeRules.isRootId(nodeId)) {
      return;
    }
    shareRepository.cascadeUpsertInheritedShares(nodeId, userId, permission, expiredAt);
  }

  public void cascadeDeleteShare(String nodeId, String userId) {
    shareRepository.cascadeDeleteInheritedShares(nodeId, userId);
  }
}
