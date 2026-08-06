// SPDX-FileCopyrightText: 2022 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.dal.repositories.impl.ebean;

import com.google.inject.Inject;
import com.zextras.carbonio.files.Constants.Db;
import com.zextras.carbonio.files.dal.DatabaseManager;
import com.zextras.carbonio.files.dal.dao.ebean.Link;
import com.zextras.carbonio.files.dal.dao.ebean.Node;
import com.zextras.carbonio.files.dal.repositories.impl.ebean.utilities.LinkSort;
import com.zextras.carbonio.files.dal.repositories.interfaces.CollationRepository;
import com.zextras.carbonio.files.dal.repositories.interfaces.LinkRepository;
import io.ebean.Query;
import io.ebean.Transaction;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

public class LinkRepositoryEbean implements LinkRepository {

  private final DatabaseManager databaseManagerFlyway;
  private final CollationRepository collationRepository;

  @Inject
  public LinkRepositoryEbean(
      DatabaseManager databaseManagerFlyway, CollationRepository collationRepository) {
    this.databaseManagerFlyway = databaseManagerFlyway;
    this.collationRepository = collationRepository;
  }

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

    databaseManagerFlyway.getEbeanDatabase().save(link);

    return link;
  }

  public Optional<Link> getLinkById(String linkId) {
    return databaseManagerFlyway
        .getEbeanDatabase()
        .find(Link.class)
        .where()
        .eq(Db.Link.ID, linkId)
        .findOneOrEmpty();
  }

  public Optional<Link> getLinkByNotExpiredPublicId(String publicId) {
    return databaseManagerFlyway
        .getEbeanDatabase()
        .find(Link.class)
        .where()
        .eq(Db.Link.PUBLIC_ID, publicId)
        .or()
        .isNull(Db.Link.EXPIRES_AT)
        .gt(Db.Link.EXPIRES_AT, System.currentTimeMillis())
        .endOr()
        .findOneOrEmpty();
  }

  public Stream<Link> getLinksByNodeId(String nodeId, LinkSort sort) {
    Query<Link> query =
        databaseManagerFlyway
            .getEbeanDatabase()
            .find(Link.class)
            .where()
            .eq(Db.Link.NODE_ID, nodeId)
            .query();

    return sort
        .getOrderEbeanQuery(query, collationRepository.getValidCollateForQuery())
        .findList()
        .stream();
  }

  public Link updateLink(Link link) {
    databaseManagerFlyway.getEbeanDatabase().update(link);
    return getLinkById(link.getLinkId()).get();
  }

  public void deleteLink(String linkId) {
    databaseManagerFlyway
        .getEbeanDatabase()
        .find(Link.class)
        .where()
        .eq(Db.Link.ID, linkId)
        .delete();
  }

  public void deleteLinksBulk(Collection<String> linkIds) {
    try (Transaction transaction = databaseManagerFlyway.getEbeanDatabase().beginTransaction()) {
      transaction.setBatchMode(true);
      transaction.setBatchSize(50);

      linkIds.forEach(this::deleteLink);

      transaction.commit();
    }
  }

  public boolean isLinkValidForNode(String publicLinkId, Node node) {
    publicLinkId = publicLinkId == null ? "" : publicLinkId;

    List<String> nodeIds = new ArrayList<>();
    nodeIds.add(node.getId());
    nodeIds.addAll(node.getAncestorsList());

    Optional<Link> linkOptional =
        databaseManagerFlyway
            .getEbeanDatabase()
            .find(Link.class)
            .where()
            .eq(Db.Link.PUBLIC_ID, publicLinkId)
            .in(Db.Link.NODE_ID, nodeIds)
            .or()
            .isNull(Db.Link.EXPIRES_AT)
            .gt(Db.Link.EXPIRES_AT, System.currentTimeMillis())
            .endOr()
            .findOneOrEmpty();
    return linkOptional.isPresent();
  }

  public Integer getLinkCountByNode(Node node) {
    return databaseManagerFlyway
        .getEbeanDatabase()
        .find(Link.class)
        .where()
        .eq(Db.Link.NODE_ID, node.getId())
        .findCount();
  }
}
