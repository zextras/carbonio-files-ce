// SPDX-FileCopyrightText: 2024 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.message_broker.consumers;

import com.zextras.carbonio.files.dal.dao.UserInfo;
import com.zextras.carbonio.files.dal.repositories.interfaces.NodeRepository;
import com.zextras.carbonio.files.dal.repositories.interfaces.UserRepository;
import com.zextras.carbonio.message_broker.config.EventConfig;
import com.zextras.carbonio.message_broker.consumer.BaseConsumer;
import com.zextras.carbonio.message_broker.events.generic.BaseEvent;
import com.zextras.carbonio.message_broker.events.services.mailbox.UserStatusChanged;
import com.zextras.carbonio.message_broker.events.services.mailbox.enums.UserStatus;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@ApplicationScoped
public class UserStatusChangedConsumer extends BaseConsumer {

  private static final Logger logger = LoggerFactory.getLogger(UserStatusChangedConsumer.class);

  private final NodeRepository nodeRepository;
  private final UserRepository userRepository;

  @Inject
  public UserStatusChangedConsumer(NodeRepository nodeRepository, UserRepository userRepository) {
    this.nodeRepository = nodeRepository;
    this.userRepository = userRepository;
  }

  @Override
  protected EventConfig getEventConfig() {
    return EventConfig.USER_CHANGED_STATUS;
  }

  @Override
  public void doHandle(BaseEvent baseMessageBrokerEvent) {
    UserStatusChanged userStatusChanged = (UserStatusChanged) baseMessageBrokerEvent;
    String userId = userStatusChanged.getUserId();
    logger.info("Received UserStatusChanged({}, {})", userId, userStatusChanged.getUserStatus());

    boolean hide =
        userRepository
            .getUserById(null, userId)
            .map(UserInfo::getStatus)
            .map(status -> status == com.zextras.carbonio.files.dal.dao.UserStatus.CLOSED)
            .orElseGet(() -> UserStatus.CLOSED.equals(userStatusChanged.getUserStatus()));

    int updated = nodeRepository.setHiddenForOwner(userId, hide);
    logger.info("Hidden flag of user {} set to {} on {} nodes", userId, hide, updated);
  }
}
