// SPDX-FileCopyrightText: 2024 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.messageBroker.consumers;
import com.zextras.carbonio.files.dal.dao.ebean.Node;
import com.zextras.carbonio.files.dal.repositories.interfaces.NodeRepository;
import com.zextras.carbonio.message_broker.consumer.BaseConsumer;
import com.zextras.carbonio.message_broker.events.services.mailbox.UserStatusChanged;
import com.zextras.carbonio.message_broker.events.services.mailbox.enums.UserStatus;

import java.util.List;
import java.util.Optional;

public class UserStatusChangedConsumer extends BaseConsumer<UserStatusChanged> {

  private final NodeRepository nodeRepository;

  public UserStatusChangedConsumer(NodeRepository nodeRepository) {
    this.nodeRepository = nodeRepository;
  }

  @Override
  public Class<UserStatusChanged> getEventClass() {
    return UserStatusChanged.class;
  }

  @Override
  protected void doHandle(UserStatusChanged userStatusChanged) {
    if(shouldChangeHiddenFlag(userStatusChanged)){
      List<Node> nodesToProcess = nodeRepository.findNodesByOwner(userStatusChanged.getUserId());
      nodeRepository.invertHiddenFlagNodes(nodesToProcess);
    }
  }

  /**
   * An operation is useless if all nodes already have the same flag that the operation would set. Since
   * setting this flag is transactional for all nodes owned by a user, checking a single node is sufficient.
   * Once obtaining the first node's hidden flag value we can check if is already correctly set or otherwise.
   * This is useful because if we catch an userstatuschanged, but it is from a non-closed status (like active)
   * to another non-closed status (like maintenance) we do not want to perform a useless update operation for
   * every node owned by user.
   */
  private boolean shouldChangeHiddenFlag(UserStatusChanged userStatusChanged){
    Optional<Node> firstNodeToCheckOpt = nodeRepository.findFirstByOwner(userStatusChanged.getUserId());
    return firstNodeToCheckOpt.isPresent() &&
        !firstNodeToCheckOpt.get().isHidden().equals(shouldHideByStatus(userStatusChanged.getUserStatus()));
  }

  /**
   * Small utility method that returns if nodes should be hidden or not given the status of a user.
   * Here we assume that nodes should be hidden only if user status is closed.
   */
  private Boolean shouldHideByStatus(UserStatus userStatus) {
    return userStatus.equals(UserStatus.CLOSED);
  }
}
