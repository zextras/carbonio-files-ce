// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.message_broker.ut;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.zextras.carbonio.files.dal.dao.UserInfo;
import com.zextras.carbonio.files.dal.dao.UserStatus;
import com.zextras.carbonio.files.dal.repositories.interfaces.NodeRepository;
import com.zextras.carbonio.files.dal.repositories.interfaces.UserRepository;
import com.zextras.carbonio.files.message_broker.consumers.UserStatusChangedConsumer;
import com.zextras.carbonio.message_broker.events.services.mailbox.UserStatusChanged;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Plain JUnit unit test for {@link UserStatusChangedConsumer}: the hidden flag is derived from the
 * status user-management reports (the event status only when it is unreachable) and applied with an
 * absolute update, so a redelivery or an out-of-order pair of events cannot invert it.
 */
class UserStatusChangedConsumerTest {

  private NodeRepository nodeRepository;
  private UserRepository userRepository;
  private UserStatusChangedConsumer consumer;

  private static UserInfo userWithStatus(UserStatus status) {
    UserInfo userInfo = mock(UserInfo.class);
    when(userInfo.getStatus()).thenReturn(status);
    return userInfo;
  }

  @BeforeEach
  void setUp() {
    nodeRepository = mock(NodeRepository.class);
    userRepository = mock(UserRepository.class);
    consumer = new UserStatusChangedConsumer(nodeRepository, userRepository);
  }

  @Test
  void givenAClosedUserInUserManagementThenNodesAreHidden() {
    UserInfo user = userWithStatus(UserStatus.CLOSED);
    when(userRepository.getUserById(null, "owner-1")).thenReturn(Optional.of(user));

    consumer.doHandle(new UserStatusChanged("owner-1", "CLOSED"));

    verify(nodeRepository).setHiddenForOwner("owner-1", true);
  }

  @Test
  void givenAnActiveUserInUserManagementThenNodesAreShown() {
    UserInfo user = userWithStatus(UserStatus.ACTIVE);
    when(userRepository.getUserById(null, "owner-2")).thenReturn(Optional.of(user));

    consumer.doHandle(new UserStatusChanged("owner-2", "ACTIVE"));

    verify(nodeRepository).setHiddenForOwner("owner-2", false);
  }

  @Test
  void givenAStaleEventStatusThenTheStatusOfUserManagementWins() {
    UserInfo user = userWithStatus(UserStatus.ACTIVE);
    when(userRepository.getUserById(null, "owner-3")).thenReturn(Optional.of(user));

    consumer.doHandle(new UserStatusChanged("owner-3", "CLOSED"));

    verify(nodeRepository).setHiddenForOwner("owner-3", false);
  }

  @Test
  void givenAMaintenanceUserThenNodesAreShown() {
    UserInfo user = userWithStatus(UserStatus.MAINTENANCE);
    when(userRepository.getUserById(null, "owner-4")).thenReturn(Optional.of(user));

    consumer.doHandle(new UserStatusChanged("owner-4", "MAINTENANCE"));

    verify(nodeRepository).setHiddenForOwner("owner-4", false);
  }

  @Test
  void givenUserManagementUnreachableThenTheEventStatusIsUsed() {
    when(userRepository.getUserById(null, "owner-5")).thenReturn(Optional.empty());
    when(userRepository.getUserById(null, "owner-6")).thenReturn(Optional.empty());

    consumer.doHandle(new UserStatusChanged("owner-5", "CLOSED"));
    consumer.doHandle(new UserStatusChanged("owner-6", "ACTIVE"));

    verify(nodeRepository).setHiddenForOwner("owner-5", true);
    verify(nodeRepository).setHiddenForOwner("owner-6", false);
  }
}
