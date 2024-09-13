// SPDX-FileCopyrightText: 2024 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.utilities;

import com.zextras.carbonio.files.dal.repositories.interfaces.NodeRepository;

public class RenameNodeUtils {

  /**
   * This method is used to search an alternative name if the filename is already present in the
   * destination folder.
   *
   * @param filename            a {@link String} representing the filename of the node I have to
   *                            upload.
   * @param destinationFolderId is a {@link String } representing the id of the destination folder.
   * @param nodeOwner is a {@link String } representing the id of the owner of the node.
   * @return a {@link String} of the alternative name if the filename is already taken or the chosen
   * filename.
   */
  public static String searchAlternativeName(
    NodeRepository nodeRepository,
    String filename,
    String destinationFolderId,
    String nodeOwner
  ) {

    int level = 1;
    String finalFilename = filename;

    while (nodeRepository
      .getNodeByName(finalFilename, destinationFolderId, nodeOwner)
      .isPresent()
    ) {
      int dotPosition = filename.lastIndexOf('.');

      finalFilename = (dotPosition != -1)
        ? filename.substring(0, dotPosition) + " (" + level + ")" + filename.substring(dotPosition)
        : filename + " (" + level + ")";

      ++level;
    }

    return finalFilename;
  }
}
