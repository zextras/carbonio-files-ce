// SPDX-FileCopyrightText: 2024 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.message_broker.consumers;
import com.google.inject.Inject;
import com.zextras.carbonio.files.dal.repositories.interfaces.NodeRepository;
import com.zextras.carbonio.message_broker.config.EventConfig;
import com.zextras.carbonio.message_broker.consumer.BaseConsumer;
import com.zextras.carbonio.message_broker.events.generic.BaseEvent;
import com.zextras.carbonio.message_broker.events.services.mailbox.UserDeleted;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class UserDeletedConsumer extends BaseConsumer {

  private static final Logger logger = LoggerFactory.getLogger(UserDeletedConsumer.class);

  private final NodeRepository nodeRepository;

  @Inject
  public UserDeletedConsumer(NodeRepository nodeRepository) {
    this.nodeRepository = nodeRepository;
  }

  @Override
  protected EventConfig getEventConfig() {
    return EventConfig.USER_DELETED;
  }

  @Override
  public void doHandle(BaseEvent baseMessageBrokerEvent) {
    UserDeleted userDeleted = (UserDeleted) baseMessageBrokerEvent;
    logger.info("Received UserDeleted({})", userDeleted.getUserId());

    //TODO delete all user's nodes

  }
}
