// SPDX-FileCopyrightText: 2022 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.dal.repositories.interfaces;

import com.zextras.carbonio.files.dal.dao.ebean.FileVersion;
import com.zextras.carbonio.files.dal.dao.ebean.Node;
import com.zextras.carbonio.files.dal.repositories.impl.ebean.utilities.FileVersionSort;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * <p>This is the only class allowed to execute CRUD operations on a {@link FileVersion}
 * element.</p>
 * <p>It's method must be implemented by specific implementations depending on DB and ORM used.</p>
 */
public interface FileVersionRepository {

  /**
   * Allows to retrieve a {@link FileVersion} from the database or from the cache if it was recently
   * requested.
   *
   * @param nodeId is a {@link String} representing the id of the node to retrieve
   * @param version is an {@link Integer} of the version of the node
   *
   * @return an {@link Optional<FileVersion>} containing the {@link FileVersion} requested.
   */
  Optional<FileVersion> getFileVersion(
    String nodeId,
    int version
  );

  /**
   * Checks if the related {@link Node} exists and if not it returns an {@link Optional#empty()}
   * otherwise:
   * <ul>
   *   <li>it creates a new {@link FileVersion} and saves it in the database</li>
   *   <li>it returns a {@link FileVersion} of the {@link FileVersion} just created</li>
   * </ul>
   * This method considers the parameters in input already valid so it does not do any kind of control on them.
   *
   * @param nodeId is a {@link String} representing the id of the {@link Node}
   * @param lastEditorId is a {@link String} of the last user that edit the file
   * @param version is an {@link Integer} representing the version of the file
   * @param mimeType is a {@link String} of the mime type of the file
   * @param size is a {@link Long} representing the size of the file
   * @param digest is a {@link String} of the digest of the file
   * @param autosave is a {@link Boolean} that tells if a file can be auto saved
   *
   * @return a {@link Optional<FileVersion>} that is empty if the related node does not exist.
   */
  Optional<FileVersion> createNewFileVersion(
    String nodeId,
    String lastEditorId,
    int version,
    String mimeType,
    long size,
    String digest,
    boolean autosave
  );

  /**
   * <p>Returns the {@link List} of all {@link FileVersion}s associated to a specific {@link
   * Node}.</p>
   *
   * @param nodeId the {@link String} representing the identifier of the {@link Node} whose {@link
   * FileVersion}s we are interested in.
   *
   * @return the {@link List} containing all {@link FileVersion}s associated with the {@link Node}
   * if it is present, or the empty list if it is not present.
   */
  List<FileVersion> getFileVersions(String nodeId, List<FileVersionSort> sorts);

  List<FileVersion> getFileVersions(
    String nodeId,
    Collection<Integer> versions
  );

  /**
   * <p>Returns the most recent {@link FileVersion} associated with the specified {@link Node}.</p>
   *
   * @param nodeId identifier for the {@link Node}.
   *
   * @return an {@link Optional} containing the most recent {@link FileVersion} found.
   */
  Optional<FileVersion> getLastFileVersion(String nodeId);

  /**
   * <p>Updates a {@link FileVersion} on database.</p>
   *
   * @param fileVersion is the {@link FileVersion} to update
   *
   * @return the update fileVersion
   */
  FileVersion updateFileVersion(FileVersion fileVersion);

  /**
   * <p>Deletes a {@link Node} from the database and from the cache if enabled.</p>
   * <p>if the {@link Node} does not exist, then the method does nothing and returns
   * <code>false</code>.</p>
   *
   * @param fileVersion is the {@link FileVersion} to delete
   *
   * @return <code>true</code> if the {@link Node} exists in the database, and if the deletion is
   * performed correctly, <code>false</code> otherwise.
   */
  boolean deleteFileVersion(FileVersion fileVersion);

  void deleteFileVersions(
    String nodeId,
    Collection<Integer> versions
  );
}
