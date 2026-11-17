// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.dal.repositories.impl;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;

/** Reads the {@link DatabaseClock}; joins the caller's transaction when there is one. */
@ApplicationScoped
public class DatabaseClockReader {

  private final EntityManager entityManager;

  @Inject
  public DatabaseClockReader(EntityManager entityManager) {
    this.entityManager = entityManager;
  }

  public long nowMillis() {
    return entityManager
        .createQuery("select " + DatabaseClock.NOW_MILLIS, Long.class)
        .getSingleResult();
  }
}
