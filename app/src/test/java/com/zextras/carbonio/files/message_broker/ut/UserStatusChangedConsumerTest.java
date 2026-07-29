// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.message_broker.ut;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.zextras.carbonio.files.dal.dao.ebean.Node;
import com.zextras.carbonio.files.dal.dao.ebean.NodeType;
import com.zextras.carbonio.files.dal.repositories.interfaces.NodeRepository;
import com.zextras.carbonio.files.message_broker.consumers.UserStatusChangedConsumer;
import com.zextras.carbonio.message_broker.events.services.mailbox.UserStatusChanged;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Plain JUnit (NOT {@code @QuarkusTest}) unit test for {@link UserStatusChangedConsumer}'s
 * hide/unhide logic, replacing the {@code UserStatusChanged} half of {@code
 * com.zextras.carbonio.files.acceptance.BrokerEffectsApiIT} (which drove the consumer by
 * constructing it directly and calling {@code doHandle(...)} — a backdoor now deleted since there
 * is no HTTP/broker trigger reachable out-of-process). Mirrors the sibling {@code
 * KeyValueChangedHandlerTest}, which covers {@link
 * com.zextras.carbonio.files.message_broker.consumers.KeyValueChangedConsumer} the same way.
 *
 * <p><b>Coverage change (accepted):</b> the HTTP-observable consequence (a hidden node excluded
 * from {@code findNodes}) is no longer asserted end-to-end; only the consumer's decision logic
 * (whether/how it calls {@link NodeRepository#invertHiddenFlagNodes}) is asserted here.
 *
 * <p><b>FINDING (carried over verbatim from the deleted IT, not fixed — no {@code src/main}
 * changes):</b> {@code shouldNodesHideByUserStatus} only special-cases {@code CLOSED} (hide) —
 * EVERY other status (including {@code ACTIVE} and {@code MAINTENANCE}) maps to the same "should be
 * visible" value. The consumer is also a TOGGLE, not a set: {@code shouldChangeHiddenFlag} inspects
 * one arbitrary node owned by the user and only flips ALL of the user's nodes if that one node's
 * current hidden flag disagrees with the new status's implied value.
 */
class UserStatusChangedConsumerTest {

  private static Node node(boolean hidden) {
    Node node =
        new Node(
            "node-1", "owner-1", "owner-1", "LOCAL_ROOT", 1L, 1L, "n", "d", NodeType.TEXT,
            "LOCAL_ROOT", 1L);
    node.setHidden(hidden);
    return node;
  }

  @Test
  void givenAVisibleNodeAndClosedStatusThenNodesAreHidden() {
    NodeRepository nodeRepository = mock(NodeRepository.class);
    Node visibleNode = node(false);
    when(nodeRepository.findFirstByOwner("owner-1")).thenReturn(Optional.of(visibleNode));
    when(nodeRepository.findNodesByOwner("owner-1")).thenReturn(List.of(visibleNode));
    UserStatusChangedConsumer consumer = new UserStatusChangedConsumer(nodeRepository);

    consumer.doHandle(new UserStatusChanged("owner-1", "CLOSED"));

    verify(nodeRepository).invertHiddenFlagNodes(List.of(visibleNode));
  }

  @Test
  void givenANodeHiddenByAPriorCloseAndActiveStatusThenNodesAreUnhidden() {
    NodeRepository nodeRepository = mock(NodeRepository.class);
    Node hiddenNode = node(true);
    when(nodeRepository.findFirstByOwner("owner-2")).thenReturn(Optional.of(hiddenNode));
    when(nodeRepository.findNodesByOwner("owner-2")).thenReturn(List.of(hiddenNode));
    UserStatusChangedConsumer consumer = new UserStatusChangedConsumer(nodeRepository);

    consumer.doHandle(new UserStatusChanged("owner-2", "ACTIVE"));

    verify(nodeRepository).invertHiddenFlagNodes(List.of(hiddenNode));
  }

  @Test
  void givenAVisibleNodeAndMaintenanceStatusThenNoOperationIsPerformed() {
    // MAINTENANCE agrees with the current "not hidden" baseline (both are "not CLOSED"), so
    // shouldChangeHiddenFlag is false and the toggle never fires.
    NodeRepository nodeRepository = mock(NodeRepository.class);
    Node visibleNode = node(false);
    when(nodeRepository.findFirstByOwner("owner-3")).thenReturn(Optional.of(visibleNode));
    UserStatusChangedConsumer consumer = new UserStatusChangedConsumer(nodeRepository);

    consumer.doHandle(new UserStatusChanged("owner-3", "MAINTENANCE"));

    verify(nodeRepository, never()).invertHiddenFlagNodes(org.mockito.ArgumentMatchers.any());
    verify(nodeRepository, never()).findNodesByOwner(eq("owner-3"));
  }

  @Test
  void givenAUserWithNoNodesThenNoOperationIsPerformed() {
    NodeRepository nodeRepository = mock(NodeRepository.class);
    when(nodeRepository.findFirstByOwner("owner-4")).thenReturn(Optional.empty());
    UserStatusChangedConsumer consumer = new UserStatusChangedConsumer(nodeRepository);

    consumer.doHandle(new UserStatusChanged("owner-4", "CLOSED"));

    verify(nodeRepository, never()).invertHiddenFlagNodes(org.mockito.ArgumentMatchers.any());
  }
}
