// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.dal.dao.ebean.notifications.utils;

import com.zextras.carbonio.files.dal.dao.ebean.notifications.BaseNotification;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Fans in every {@link NotificationTypeDescriptor} bean (CE built-ins plus any Advanced additions)
 * and exposes them for the two dispatch sites that used to be driven by the closed {@code
 * NotificationType} enum: the persisted code -&gt; descriptor lookup and the per-subtype JPA query
 * set in {@code NotificationRepositoryImpl}.
 */
@ApplicationScoped
public class NotificationTypeRegistry {

  private final Map<String, NotificationTypeDescriptor> byCode;
  private final List<Class<? extends BaseNotification>> notificationClasses;

  @Inject
  public NotificationTypeRegistry(@Any Instance<NotificationTypeDescriptor> descriptors) {
    Map<String, NotificationTypeDescriptor> codeMap = new LinkedHashMap<>();
    List<Class<? extends BaseNotification>> classes = new ArrayList<>();
    for (NotificationTypeDescriptor descriptor : descriptors) {
      codeMap.put(descriptor.code(), descriptor);
      if (!classes.contains(descriptor.notificationClass())) {
        classes.add(descriptor.notificationClass());
      }
    }
    this.byCode = Map.copyOf(codeMap);
    this.notificationClasses = List.copyOf(classes);
  }

  /**
   * @return the concrete notification entity subtypes to query, one per registered descriptor.
   */
  public Collection<Class<? extends BaseNotification>> notificationClasses() {
    return notificationClasses;
  }

  public Optional<NotificationTypeDescriptor> findByCode(String code) {
    return Optional.ofNullable(byCode.get(code));
  }
}
