// SPDX-FileCopyrightText: 2024 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.api.utilities;

import com.google.inject.Injector;
import com.zextras.carbonio.files.Files;
import com.zextras.carbonio.files.api.utilities.entities.PopulatorNode;
import com.zextras.carbonio.files.dal.dao.ebean.ACL;
import com.zextras.carbonio.files.dal.dao.ebean.FileVersion;
import com.zextras.carbonio.files.dal.dao.ebean.Node;
import com.zextras.carbonio.files.dal.dao.ebean.NodeType;
import com.zextras.carbonio.files.dal.repositories.interfaces.FileVersionRepository;
import com.zextras.carbonio.files.dal.repositories.interfaces.LinkRepository;
import com.zextras.carbonio.files.dal.repositories.interfaces.NodeRepository;
import com.zextras.carbonio.files.dal.repositories.interfaces.ShareRepository;

import java.util.*;
import java.util.concurrent.TimeUnit;

import static org.testcontainers.shaded.org.awaitility.Awaitility.await;

public class DatabasePopulator {
  static NodeRepository nodeRepository;
  static FileVersionRepository fileVersionRepository;
  static LinkRepository linkRepository;
  static ShareRepository shareRepository;

  public DatabasePopulator(Injector injector) {
    nodeRepository = injector.getInstance(NodeRepository.class);
    fileVersionRepository = injector.getInstance(FileVersionRepository.class);
    linkRepository = injector.getInstance(LinkRepository.class);
    shareRepository = injector.getInstance(ShareRepository.class);
  }

  public static DatabasePopulator aNodePopulator(Injector injector) {
    return new DatabasePopulator(injector);
  }

  public DatabasePopulator addNode(PopulatorNode node) {
    nodeRepository.createNewNode(
        node.getNodeId(),
        node.getCreatorId(),
        node.getOwnerId(),
        node.getParentId(),
        node.getName(),
        node.getDescription(),
        node.getType(),
        node.getAncestorIds(),
        node.getSize());

    if (!node.getType().equals(NodeType.FOLDER))
      fileVersionRepository.createNewFileVersion(
          node.getNodeId(), node.getOwnerId(), 1, node.getMimeType(), node.getSize(), "", false);

    delay();
    return this;
  }

  public DatabasePopulator addShare(
      String nodeId, String targetUserId, ACL.SharePermission permission) {
    shareRepository.upsertShare(
        nodeId, targetUserId, ACL.decode(permission), true, false, Optional.empty());
    delay();
    return this;
  }

  public DatabasePopulator addVersion(String nodeId) {
    Optional<Node> optionalNode = nodeRepository.getNode(nodeId);
    if (optionalNode.isEmpty()) throw new IllegalArgumentException("Node does not exist");

    List<FileVersion> versions = fileVersionRepository.getFileVersions(nodeId, false);
    if (versions.isEmpty())
      throw new IllegalArgumentException("No initial version found for this node");
    FileVersion lastVersion = versions.get(versions.size() - 1);

    Node node = optionalNode.get();
    fileVersionRepository.createNewFileVersion(
        node.getId(),
        node.getOwnerId(),
        lastVersion.getVersion() + 1,
        lastVersion.getMimeType(),
        node.getSize(),
        "",
        false);

    delay();
    return this;
  }

  public DatabasePopulator addLink(
      String linkId,
      String nodeId,
      String publicId,
      Optional<Long> expAt,
      Optional<String> description) {
    Optional<Node> optionalNode = nodeRepository.getNode(nodeId);
    if (optionalNode.isEmpty()) throw new IllegalArgumentException("Node does not exist");
    linkRepository.createLink(linkId, nodeId, publicId, expAt, description);
    delay();
    return this;
  }

  public DatabasePopulator addFlag(String nodeId, String requesterId) {
    nodeRepository.flagForUser(nodeId, requesterId, true);
    delay();
    return this;
  }

  public DatabasePopulator addNodeToTrash(String nodeId, String nodeParentId) {
    Optional<Node> trashedNode = nodeRepository.getNode(nodeId);
    if (trashedNode.isEmpty()) throw new IllegalArgumentException("Node does not exist");
    trashedNode.get().setAncestorIds(Files.Db.RootId.TRASH_ROOT);
    trashedNode.get().setParentId(Files.Db.RootId.TRASH_ROOT);
    nodeRepository.trashNode(nodeId, nodeParentId);
    nodeRepository.updateNode(trashedNode.get());
    delay();
    return this;
  }

  private void delay() {
    await().atLeast(1, TimeUnit.MILLISECONDS);
  }
}
