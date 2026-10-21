// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.rest.services;

import com.zextras.carbonio.files.dal.dao.ebean.Node;
import com.zextras.carbonio.files.dal.repositories.interfaces.FileVersionRepository;
import com.zextras.carbonio.files.dal.repositories.interfaces.NodeRepository;
import com.zextras.carbonio.files.dal.repositories.interfaces.OverwriteLeaseRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Duration;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One overwrite of a node at a time (the Docs autosave rewrites the current version in place and
 * the storages do not protect a key written twice at once). The lease is taken and released in
 * short transactions and expires after {@link #LEASE}, the Collabora save timeout: an overwrite
 * that outlives it keeps writing, but another one may start.
 */
@ApplicationScoped
public class OverwriteLeases {

  private static final Logger logger = LoggerFactory.getLogger(OverwriteLeases.class);

  static final Duration LEASE = Duration.ofSeconds(30);

  /** The version an overwrite rewrites and its date (database clock, at registration). */
  public record Lease(String nodeId, int version, String token, long date) {}

  private final NodeRepository nodeRepository;
  private final FileVersionRepository fileVersionRepository;
  private final OverwriteLeaseRepository leaseRepository;

  @Inject
  public OverwriteLeases(
      NodeRepository nodeRepository,
      FileVersionRepository fileVersionRepository,
      OverwriteLeaseRepository leaseRepository) {
    this.nodeRepository = nodeRepository;
    this.fileVersionRepository = fileVersionRepository;
    this.leaseRepository = leaseRepository;
  }

  /**
   * Registers an overwrite of the current version of the node.
   *
   * @return empty when another overwrite of the node holds a lease not expired yet
   * @throws NoSuchElementException when the node does not exist
   */
  public Optional<Lease> take(String nodeId) {
    return QuarkusTransaction.requiringNew()
        .call(
            () -> {
              long date =
                  nodeRepository
                      .reserveTimestamp(nodeId)
                      .orElseThrow(() -> new NoSuchElementException("Node not found: " + nodeId))
                      .reservedAt();
              int version =
                  nodeRepository
                      .getNode(nodeId)
                      .map(Node::getCurrentVersion)
                      .orElseThrow(() -> new NoSuchElementException("Node not found: " + nodeId));
              // A version below may carry a later date (written by an instance clock).
              Optional<Long> latest = fileVersionRepository.latestDateBelow(nodeId, version + 1);
              if (latest.isPresent() && latest.get() >= date) {
                date =
                    nodeRepository
                        .reserveTimestamp(nodeId, latest.get() + 1)
                        .orElseThrow(() -> new NoSuchElementException("Node not found: " + nodeId))
                        .reservedAt();
              }
              String token = UUID.randomUUID().toString();
              return leaseRepository.take(nodeId, version, token, date, LEASE)
                  ? Optional.of(new Lease(nodeId, version, token, date))
                  : Optional.<Lease>empty();
            });
  }

  /**
   * Ends the overwrite inside the transaction that rewrites the version.
   *
   * @return false when the lease expired and a newer overwrite took it: the database is left to it
   */
  public boolean confirm(Lease lease) {
    return leaseRepository.release(lease.nodeId(), lease.token());
  }

  /** Releases the lease of an overwrite that failed; a release that fails expires on its own. */
  public void release(Lease lease) {
    try {
      leaseRepository.release(lease.nodeId(), lease.token());
    } catch (RuntimeException e) {
      logger.warn(
          "Unable to release the overwrite lease of node {}: {}", lease.nodeId(), e.getMessage());
    }
  }
}
