// SPDX-FileCopyrightText: 2024 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.api.utilities.entities;

import com.zextras.carbonio.files.dal.dao.ebean.NodeType;

public class SimplePopulatorFolder extends PopulatorNode {
  public SimplePopulatorFolder(String nodeId, String ownerId) {
    super(
        nodeId,
        ownerId,
        ownerId,
        "LOCAL_ROOT",
        "folder",
        "",
        NodeType.FOLDER,
        "LOCAL_ROOT",
        0L,
        null);
  }

  public SimplePopulatorFolder(String nodeId, String ownerId, String name) {
    super(
        nodeId, ownerId, ownerId, "LOCAL_ROOT", name, "", NodeType.FOLDER, "LOCAL_ROOT", 0L, null);
  }
}
