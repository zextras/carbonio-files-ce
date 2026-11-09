// SPDX-FileCopyrightText: 2024 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.message_broker.consumers;

import com.zextras.carbonio.files.dal.repositories.impl.Transactions;
import com.zextras.carbonio.files.dal.repositories.interfaces.NodeRepository;
import com.zextras.carbonio.message_broker.config.EventConfig;
import com.zextras.carbonio.message_broker.consumer.BaseConsumer;
import com.zextras.carbonio.message_broker.events.generic.BaseEvent;
import com.zextras.carbonio.message_broker.events.services.mailbox.UserStatusChanged;
import com.zextras.carbonio.message_broker.events.services.mailbox.enums.UserStatus;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Hides or shows every node of a user according to the status carried by the event: nodes are
 * hidden only when the user is closed, and the flag is written as an absolute value, so handling
 * the same event twice leaves the same result.
 *
 * <p>The write runs in its own transaction opened inside {@code doHandle} through {@link
 * Transactions}: this consumer runs on the message-broker SDK's delivery thread, and {@code
 * doHandle} is invoked on {@code this}, so a {@code @Transactional} annotation on it would be
 * ignored. {@link BaseConsumer#handleDelivery} acks only after {@code doHandle} returns, hence the
 * commit always precedes the ack.
 */
@ApplicationScoped
public class UserStatusChangedConsumer extends BaseConsumer {

  private static final Logger logger = LoggerFactory.getLogger(UserStatusChangedConsumer.class);

  private final NodeRepository nodeRepository;
  private final Transactions transactions;

  @Inject
  public UserStatusChangedConsumer(NodeRepository nodeRepository, Transactions transactions) {
    this.nodeRepository = nodeRepository;
    this.transactions = transactions;
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
    boolean hidden = userStatusChanged.getUserStatus() == UserStatus.CLOSED;
    int updated =
        transactions.inNewTransaction(() -> nodeRepository.setHiddenForOwner(userId, hidden));
    logger.info("Set hidden flag to {} for {} node(s) of user {}", hidden, updated, userId);
  }
}
