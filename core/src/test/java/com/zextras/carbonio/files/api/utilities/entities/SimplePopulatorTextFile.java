// SPDX-FileCopyrightText: 2024 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.api.utilities.entities;

import com.zextras.carbonio.files.dal.dao.ebean.NodeType;

public class SimplePopulatorTextFile extends PopulatorNode {
  public SimplePopulatorTextFile(String nodeId, String ownerId) {
    super(
        nodeId,
        ownerId,
        ownerId,
        "LOCAL_ROOT",
        "fake.txt",
        "",
        NodeType.TEXT,
        "LOCAL_ROOT",
        1L,
        "text/plain");
  }

  public SimplePopulatorTextFile(String nodeId, String ownerId, String name) {
    super(
        nodeId,
        ownerId,
        ownerId,
        "LOCAL_ROOT",
        name,
        "",
        NodeType.TEXT,
        "LOCAL_ROOT",
        1L,
        "text/plain");
    if (!name.endsWith(".txt"))
      throw new IllegalArgumentException("File name doesn't end with .txt");
  }

  public SimplePopulatorTextFile(String nodeId, String ownerId, Long size) {
    super(
        nodeId,
        ownerId,
        ownerId,
        "LOCAL_ROOT",
        "fake.txt",
        "",
        NodeType.TEXT,
        "LOCAL_ROOT",
        size,
        "text/plain");
    if (!name.endsWith(".txt"))
      throw new IllegalArgumentException("File name doesn't end with .txt");
  }

  public SimplePopulatorTextFile(String nodeId, String ownerId, String name, Long size) {
    super(
        nodeId,
        ownerId,
        ownerId,
        "LOCAL_ROOT",
        name,
        "",
        NodeType.TEXT,
        "LOCAL_ROOT",
        size,
        "text/plain");
    if (!name.endsWith(".txt"))
      throw new IllegalArgumentException("File name doesn't end with .txt");
  }
}
