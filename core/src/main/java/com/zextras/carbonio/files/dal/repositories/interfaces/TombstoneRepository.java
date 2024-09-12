// SPDX-FileCopyrightText: 2022 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.dal.repositories.interfaces;

import com.zextras.carbonio.files.dal.dao.ebean.FileVersion;
import com.zextras.carbonio.files.dal.dao.ebean.Node;
import com.zextras.carbonio.files.dal.dao.ebean.Tombstone;
import java.util.List;
import java.util.Optional;

/**
 * <p>This is the only class allowed to execute CRUD operations on a Tombstone element.</p>
 * <p>Its methods' implementation depends specifically on DB and ORM used.</p>
 */
public interface TombstoneRepository {

  /**
   * <p>Deletes all Tombstones from the database.</p>
   */
  void deleteTombstones();

  /**
   * <p>Gets all Tombstones from the database.</p>
   *
   * @return the list of all Tombstones in the database.
   */
  List<Tombstone> getTombstones();

  /**
   * <p>Creates a new {@link Tombstone}.</p>
   *
   * @param nodeId the identifier of the {@link Node}
   * @param ownerId the identifier associated with the owner of the {@link Node}
   * @param version the version of the {@link Node} referred by the {@link Tombstone}
   *
   * @return an {@link Optional} containing the {@link Tombstone} just created if the creation
   * operation was performed correctly, the {@link Optional#empty()} otherwise
   */
  Optional<Tombstone> createNewTombstone(
    String nodeId,
    String ownerId,
    Integer version
  );

  /**
   * Creates new {@link Tombstone}s in bulk, one for each given {@link FileVersion}.
   *
   * @param fileVersions is a {@link List<FileVersion>} to create a {@link Tombstone} with.
   * @param ownerId is a {@link String} representing the owner of the {@link FileVersion}.
   */
  void createTombstonesBulk(
    List<FileVersion> fileVersions,
    String ownerId
  );

  void deleteTombstonesFromOwner(String ownerId);
}
