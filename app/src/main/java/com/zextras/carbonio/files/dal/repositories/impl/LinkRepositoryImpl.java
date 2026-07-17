// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.dal.repositories.impl;

import com.zextras.carbonio.files.dal.dao.ebean.Link;
import com.zextras.carbonio.files.dal.dao.ebean.Node;
import com.zextras.carbonio.files.dal.repositories.impl.ebean.utilities.LinkSort;
import com.zextras.carbonio.files.dal.repositories.impl.ebean.utilities.SortOrder;
import com.zextras.carbonio.files.dal.repositories.interfaces.LinkRepository;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Panache implementation of {@link LinkRepository}. Replaces the old Ebean-based {@code
 * LinkRepositoryEbean}.
 */
@ApplicationScoped
public class LinkRepositoryImpl implements LinkRepository, PanacheRepositoryBase<Link, String> {

  @Override
  @Transactional
  public Link createLink(
      String linkId,
      String nodeId,
      String publicId,
      Optional<Long> optExpiresAt,
      Optional<String> optDescription,
      Optional<String> optAccessCode) {

    Link link = new Link(linkId, nodeId, publicId, System.currentTimeMillis(), null, null);

    optExpiresAt.ifPresent(link::setExpiresAt);
    optDescription.ifPresent(link::setDescription);
    optAccessCode.ifPresent(link::setAccessCode);

    persist(link);
    return link;
  }

  @Override
  public Optional<Link> getLinkById(String linkId) {
    return findByIdOptional(linkId);
  }

  @Override
  public Optional<Link> getLinkByNotExpiredPublicId(String publicId) {
    return find(
            "publicId = ?1 and (expiresAt is null or expiresAt > ?2)",
            publicId,
            System.currentTimeMillis())
        .firstResultOptional();
  }

  @Override
  public Stream<Link> getLinksByNodeId(String nodeId, LinkSort sort) {
    String direction = sort.getOrder() == SortOrder.DESCENDING ? "desc" : "asc";
    return find("nodeId = ?1 order by createdAt " + direction, nodeId).stream();
  }

  @Override
  @Transactional
  public Link updateLink(Link link) {
    getEntityManager().merge(link);
    return getLinkById(link.getLinkId()).orElseThrow();
  }

  @Override
  @Transactional
  public void deleteLink(String linkId) {
    deleteById(linkId);
  }

  @Override
  @Transactional
  public void deleteLinksBulk(Collection<String> linkIds) {
    // Fetch-then-remove (rather than a bulk "delete ... where id in" JPQL statement) so entities
    // already managed in the current persistence context are correctly evicted; JPA bulk
    // statements bypass the first-level cache and would otherwise leave stale managed instances
    // behind for the rest of the transaction.
    list("id in ?1", linkIds).forEach(this::delete);
  }

  @Override
  public boolean isLinkValidForNode(String publicLinkId, Node node) {
    String safePublicLinkId = publicLinkId == null ? "" : publicLinkId;

    List<String> nodeIds = new ArrayList<>();
    nodeIds.add(node.getId());
    nodeIds.addAll(node.getAncestorsList());

    return find(
            "publicId = ?1 and nodeId in ?2 and (expiresAt is null or expiresAt > ?3)",
            safePublicLinkId,
            nodeIds,
            System.currentTimeMillis())
        .firstResultOptional()
        .isPresent();
  }

  @Override
  public Integer getLinkCountByNode(Node node) {
    return (int) count("nodeId", node.getId());
  }
}
