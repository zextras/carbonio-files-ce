// SPDX-FileCopyrightText: 2022 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.dal.repositories.interfaces;

import com.zextras.carbonio.files.dal.dao.ebean.Link;
import com.zextras.carbonio.files.dal.repositories.impl.ebean.utilities.LinkSort;
import java.util.Collection;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * <p>This is the only class allowed to execute CRUD operations on a {@link Link} element.</p>
 * <p>In particular it can create a new {@link Link}, update it, access to and delete an existing
 * one.</p>
 */
public interface LinkRepository {

  Link createLink(
    String linkId,
    String nodeId,
    String publicId,
    Optional<Long> optExpiresAt,
    Optional<String> optDescription
  );

  Optional<Link> getLinkById(String linkId);

  Optional<Link> getLinkByPublicId(String publicId);

  Stream<Link> getLinksByNodeId(
    String nodeId,
    LinkSort sort
  );

  Link updateLink(Link link);

  void deleteLink(String linkId);

  void deleteLinksBulk(Collection<String> linkIds);
}
