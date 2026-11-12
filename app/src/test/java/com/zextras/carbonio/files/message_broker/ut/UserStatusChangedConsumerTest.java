// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.message_broker.ut;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Envelope;
import com.zextras.carbonio.files.dal.repositories.impl.Transactions;
import com.zextras.carbonio.files.dal.repositories.interfaces.NodeRepository;
import com.zextras.carbonio.files.message_broker.consumers.UserStatusChangedConsumer;
import com.zextras.carbonio.message_broker.events.services.mailbox.UserStatusChanged;
import com.zextras.carbonio.message_broker.events.services.mailbox.enums.UserStatus;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class UserStatusChangedConsumerTest {

  private NodeRepository nodeRepository;
  private Transactions transactions;
  private List<String> events;
  private UserStatusChangedConsumer consumer;

  @BeforeEach
  void setUp() {
    nodeRepository = mock(NodeRepository.class);
    transactions = mock(Transactions.class);
    events = new ArrayList<>();
    when(transactions.inNewTransaction(any()))
        .thenAnswer(
            invocation -> {
              events.add("begin");
              Object result = invocation.<Callable<?>>getArgument(0).call();
              events.add("commit");
              return result;
            });
    consumer = new UserStatusChangedConsumer(nodeRepository, transactions);
  }

  @Test
  void givenClosedEventThenNodesAreHidden() {
    consumer.doHandle(new UserStatusChanged("owner-1", UserStatus.CLOSED));

    verify(nodeRepository).setHiddenForOwner("owner-1", true);
  }

  @ParameterizedTest
  @EnumSource(
      value = UserStatus.class,
      mode = EnumSource.Mode.EXCLUDE,
      names = {"CLOSED"})
  void givenAnyNonClosedEventThenNodesAreShown(UserStatus status) {
    consumer.doHandle(new UserStatusChanged("owner-2", status));

    verify(nodeRepository).setHiddenForOwner("owner-2", false);
  }

  @Test
  void givenTheSameEventTwiceThenTheSameAbsoluteValueIsSetTwice() {
    UserStatusChanged event = new UserStatusChanged("owner-3", UserStatus.CLOSED);

    consumer.doHandle(event);
    consumer.doHandle(event);

    verify(nodeRepository, times(2)).setHiddenForOwner("owner-3", true);
    verify(nodeRepository, never()).setHiddenForOwner("owner-3", false);
  }

  @Test
  void givenAnEventThenTheWriteHappensInsideTheTransaction() {
    doAnswer(
            invocation -> {
              assertThat(events).containsExactly("begin");
              return 0;
            })
        .when(nodeRepository)
        .setHiddenForOwner(anyString(), anyBoolean());

    consumer.doHandle(new UserStatusChanged("owner-4", UserStatus.CLOSED));

    assertThat(events).containsExactly("begin", "commit");
  }

  @Test
  void givenADeliveryThenTheTransactionCommitsBeforeTheAck() throws Exception {
    Channel channel = mock(Channel.class);
    consumer.setChannel(channel);
    doAnswer(
            invocation -> {
              events.add("ack");
              return null;
            })
        .when(channel)
        .basicAck(anyLong(), anyBoolean());

    consumer.handleDelivery(
        "tag",
        new Envelope(1L, false, "exchange", "key"),
        null,
        "{\"userId\":\"owner-8\",\"userStatus\":\"CLOSED\"}".getBytes(UTF_8));

    verify(nodeRepository).setHiddenForOwner("owner-8", true);
    assertThat(events).containsExactly("begin", "commit", "ack");
  }
}
