// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.graphql.spi;

import com.zextras.carbonio.files.dal.dao.ebean.notifications.BaseNotification;
import com.zextras.carbonio.files.graphql.model.Notification;
import java.util.Optional;

/**
 * Extension seam for editions built on top of the CE jar (e.g. carbonio-files Advanced) to map
 * their own notification entities to the code-first {@code Notification} union. CE's {@code
 * NotificationApi} fans in every CDI bean implementing this interface and delegates to them for the
 * notification types it does not handle itself, replacing the retired schema-first {@code
 * GraphQLWiringContributor} seam.
 */
public interface NotificationModelContributor {

  /**
   * @return the union model for the given notification, or {@link Optional#empty()} if this
   *     contributor does not handle its {@code notificationType}.
   */
  Optional<Notification> toModel(BaseNotification notification);
}
