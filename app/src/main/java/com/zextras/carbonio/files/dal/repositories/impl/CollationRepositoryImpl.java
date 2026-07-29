// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.dal.repositories.impl;

import com.zextras.carbonio.files.Constants;
import com.zextras.carbonio.files.dal.repositories.interfaces.CollationRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Panache/JPA implementation of {@link CollationRepository}.
 *
 * <p>Determines the collation to use for name-ordering queries: if the database's default
 * collation is the (locale-less) {@code C}/{@code C.UTF-8} collation, it falls back to {@link
 * Constants.ServiceDiscover.Config#FALLBACK_COLLATE} when that collation is installed on the
 * server; otherwise it returns {@link Optional#empty()} (meaning: use the column's default
 * collation as-is). The result never changes at runtime, so it is computed once and cached.
 */
@ApplicationScoped
public class CollationRepositoryImpl implements CollationRepository {

  private static final Logger logger = LoggerFactory.getLogger(CollationRepositoryImpl.class);

  private final EntityManager entityManager;

  private volatile Optional<String> cachedCollate;

  @Inject
  public CollationRepositoryImpl(EntityManager entityManager) {
    this.entityManager = entityManager;
  }

  @Override
  public Optional<String> getValidCollateForQuery() {
    if (cachedCollate != null) {
      logger.info("Using cached collation: {}", cachedCollate.orElse("System default"));
      return cachedCollate;
    }

    Optional<String> defaultCollate = getDefaultCollate();
    logger.info("Default collation: {}", defaultCollate.orElse("Not found"));

    if (defaultCollate.filter(c -> c.equals("C") || c.equals("C.UTF-8")).isPresent()
        && isCollationValid(Constants.ServiceDiscover.Config.FALLBACK_COLLATE)) {
      cachedCollate = Optional.of("\"" + Constants.ServiceDiscover.Config.FALLBACK_COLLATE + "\"");
      logger.info(
          "C collation detected, setting collation to {}",
          Constants.ServiceDiscover.Config.FALLBACK_COLLATE);
      return cachedCollate;
    }

    cachedCollate = Optional.empty();
    logger.info("Setting collation to System default");
    return cachedCollate;
  }

  private Optional<String> getDefaultCollate() {
    Object result =
        entityManager
            .createNativeQuery("SELECT datcollate FROM pg_database WHERE datname = :datname")
            .setParameter("datname", Constants.Config.Database.DEFAULT_NAME)
            .getResultStream()
            .findFirst()
            .orElse(null);

    if (result == null) {
      logger.error(
          "Failed to get the default collation for the database {}",
          Constants.Config.Database.DEFAULT_NAME);
      return Optional.empty();
    }
    return Optional.ofNullable((String) result);
  }

  private boolean isCollationValid(String collation) {
    Number count =
        (Number)
            entityManager
                .createNativeQuery("SELECT COUNT(*) FROM pg_collation WHERE collname = :collation")
                .setParameter("collation", collation)
                .getSingleResult();
    return count.longValue() > 0;
  }
}
