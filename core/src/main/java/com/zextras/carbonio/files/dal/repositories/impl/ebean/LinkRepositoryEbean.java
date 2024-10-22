// SPDX-FileCopyrightText: 2022 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.dal.repositories.impl.ebean;

import com.google.inject.Inject;
import com.zextras.carbonio.files.Files.Db;
import com.zextras.carbonio.files.dal.EbeanDatabaseManager;
import com.zextras.carbonio.files.dal.dao.ebean.Link;
import com.zextras.carbonio.files.dal.dao.ebean.Node;
import com.zextras.carbonio.files.dal.repositories.impl.ebean.utilities.LinkSort;
import com.zextras.carbonio.files.dal.repositories.interfaces.LinkRepository;
import io.ebean.Query;
import io.ebean.Transaction;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

public class LinkRepositoryEbean implements LinkRepository {

  private final EbeanDatabaseManager ebeanDatabaseManager;

  @Inject
  public LinkRepositoryEbean(EbeanDatabaseManager ebeanDatabaseManager) {
    this.ebeanDatabaseManager = ebeanDatabaseManager;
  }

  public Link createLink(
    String linkId,
    String nodeId,
    String publicId,
    Optional<Long> optExpiresAt,
    Optional<String> optDescription
  ) {

    Link link = new Link(
      linkId,
      nodeId,
      publicId,
      System.currentTimeMillis()
    );

    optExpiresAt.ifPresent(link::setExpiresAt);
    optDescription.ifPresent(link::setDescription);

    ebeanDatabaseManager.getEbeanDatabase().save(link);

    return getLinkById(linkId).get();
  }

  public Optional<Link> getLinkById(String linkId) {
    return ebeanDatabaseManager
      .getEbeanDatabase()
      .find(Link.class)
      .where()
      .eq(Db.Link.ID, linkId)
      .findOneOrEmpty();
  }

  public Optional<Link> getLinkByNotExpiredPublicId(String publicId) {
    return ebeanDatabaseManager
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

  public Stream<Link> getLinksByNodeId(
    String nodeId,
    LinkSort sort
  ) {
    Query<Link> query = ebeanDatabaseManager
      .getEbeanDatabase()
      .find(Link.class)
      .where()
      .eq(Db.Link.NODE_ID, nodeId)
      .query();

    return sort
      .getOrderEbeanQuery(query)
      .findList()
      .stream();
  }

  public Link updateLink(Link link) {
    ebeanDatabaseManager.getEbeanDatabase().update(link);
    return getLinkById(link.getLinkId()).get();
  }

  public void deleteLink(String linkId) {
    ebeanDatabaseManager
      .getEbeanDatabase()
      .find(Link.class)
      .where()
      .eq(Db.Link.ID, linkId)
      .delete();
  }

  public void deleteLinksBulk(Collection<String> linkIds) {
    try (Transaction transaction = ebeanDatabaseManager.getEbeanDatabase().beginTransaction()) {
      transaction.setBatchMode(true);
      transaction.setBatchSize(50);

      linkIds.forEach(this::deleteLink);

      transaction.commit();
    }
  }

  public boolean isLinkValidForNode(String publicLinkId, Node node) {
    publicLinkId = publicLinkId == null ? "" : publicLinkId;
    Optional<Link> linkOptional = ebeanDatabaseManager
        .getEbeanDatabase()
        .find(Link.class)
        .where()
        .eq(Db.Link.PUBLIC_ID, publicLinkId)
        .eq(Db.Link.NODE_ID, node.getId())
        .or()
        .isNull(Db.Link.EXPIRES_AT)
        .gt(Db.Link.EXPIRES_AT, System.currentTimeMillis())
        .endOr()
        .findOneOrEmpty();
    return linkOptional.isPresent();
  }
}
