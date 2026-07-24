// SPDX-FileCopyrightText: 2024 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.api.utilities;

import com.zextras.carbonio.files.Constants;
import com.zextras.carbonio.files.FilesStackTestResource;
import com.zextras.carbonio.files.api.utilities.entities.PopulatorNode;
import com.zextras.carbonio.files.dal.dao.ebean.ACL;
import com.zextras.carbonio.files.dal.dao.ebean.FileVersion;
import com.zextras.carbonio.files.dal.dao.ebean.Node;
import com.zextras.carbonio.files.dal.dao.ebean.NodeType;
import com.zextras.carbonio.files.dal.repositories.impl.ebean.utilities.FileVersionSort;
import com.zextras.carbonio.files.dal.repositories.interfaces.FileVersionRepository;
import com.zextras.carbonio.files.dal.repositories.interfaces.LinkRepository;
import com.zextras.carbonio.files.dal.repositories.interfaces.NodeRepository;
import com.zextras.carbonio.files.dal.repositories.interfaces.ShareRepository;
import com.zextras.carbonio.files.it.support.MockStoragesService;
import io.quarkus.narayana.jta.QuarkusTransaction;
import org.apache.commons.lang3.RandomStringUtils;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public class DatabasePopulator {
  static NodeRepository nodeRepository;
  static FileVersionRepository fileVersionRepository;
  static LinkRepository linkRepository;
  static ShareRepository shareRepository;

  public DatabasePopulator(
      NodeRepository nodeRepository,
      FileVersionRepository fileVersionRepository,
      LinkRepository linkRepository,
      ShareRepository shareRepository) {
    DatabasePopulator.nodeRepository = nodeRepository;
    DatabasePopulator.fileVersionRepository = fileVersionRepository;
    DatabasePopulator.linkRepository = linkRepository;
    DatabasePopulator.shareRepository = shareRepository;
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

    if (!node.getType().equals(NodeType.FOLDER)) {
      fileVersionRepository.createNewFileVersion(
          node.getNodeId(), node.getOwnerId(), 1, node.getMimeType(), node.getSize(), "", false);
      seedBlob(node.getNodeId(), 1);
    }

    delay();
    return this;
  }

  /**
   * Seeds deterministic placeholder bytes for a freshly-created file version's blob (version 1 via
   * {@link #addNode}, or any later version via {@link #addVersion}) into the shared {@link
   * MockStoragesService} HTTP fake, keyed the same way it (and {@code BlobService}, via the app's
   * REAL {@code StoragesClient}) resolve blobs (node id + version) and using the same {@code (nodeId
   * + version)} byte convention as {@code Mocks#storagesServesBlob}. {@code MockStoragesService}
   * itself falls back to this exact convention for any never-explicitly-seeded node/version, so this
   * call is a belt-and-braces seed (kept for parity/clarity), not strictly required. Folders have no
   * blob and are never passed here.
   */
  private static void seedBlob(String nodeId, int version) {
    FilesStackTestResource.getStoragesService()
        .seed(nodeId, version, (nodeId + version).getBytes(StandardCharsets.UTF_8));
  }

  public DatabasePopulator addShare(
      String nodeId, String targetUserId, ACL.SharePermission permission) {
    shareRepository.upsertShare(
        nodeId, targetUserId, ACL.decode(permission), true, false, Optional.empty());
    delay();
    return this;
  }

  public DatabasePopulator addVersion(String nodeId){
    return addVersion(nodeId, false);
  }

  public DatabasePopulator addVersion(String nodeId, boolean keepForever) {
    // Quarkus: the read methods (getNode/getFileVersions) are NOT @Transactional, so the whole
    // read+write sequence must run inside one active transaction/EntityManager scope.
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              Optional<Node> optionalNode = nodeRepository.getNode(nodeId);
              if (optionalNode.isEmpty())
                throw new IllegalArgumentException("Node does not exist");

              List<FileVersion> versions =
                  fileVersionRepository.getFileVersions(
                      nodeId, List.of(FileVersionSort.VERSION_DESC));
              Collections.reverse(versions);
              if (versions.isEmpty())
                throw new IllegalArgumentException("No initial version found for this node");
              FileVersion lastVersion = versions.get(versions.size() - 1);

              Node node = optionalNode.get();
              int newVersion = lastVersion.getVersion() + 1;
              Optional<FileVersion> version =
                  fileVersionRepository.createNewFileVersion(
                      node.getId(),
                      node.getOwnerId(),
                      newVersion,
                      lastVersion.getMimeType(),
                      node.getSize(),
                      "",
                      false);
              seedBlob(node.getId(), newVersion);

              if (keepForever) {
                fileVersionRepository.updateFileVersion(version.get().keepForever(true));
              }

              node.setCurrentVersion(newVersion);
              nodeRepository.updateNode(node);
            });

    delay();
    return this;
  }

  public DatabasePopulator addLink(
      String linkId,
      String nodeId,
      String publicId,
      Optional<Long> expAt,
      Optional<String> description,
      Optional<String> accessCode) {
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              Optional<Node> optionalNode = nodeRepository.getNode(nodeId);
              if (optionalNode.isEmpty())
                throw new IllegalArgumentException("Node does not exist");
              linkRepository.createLink(linkId, nodeId, publicId, expAt, description, accessCode);
            });
    delay();
    return this;
  }

  /**
   * Creates multiple links on a node without the per-insert delay.
   * Use this when testing link count limits where link timestamps are irrelevant.
   */
  public DatabasePopulator addLinks(String nodeId, int count) {
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              Optional<Node> optionalNode = nodeRepository.getNode(nodeId);
              if (optionalNode.isEmpty())
                throw new IllegalArgumentException("Node does not exist");
              for (int i = 0; i < count; i++) {
                linkRepository.createLink(
                    UUID.randomUUID().toString(),
                    nodeId,
                    RandomStringUtils.secure().nextAlphanumeric(32),
                    Optional.of(5L),
                    Optional.of("bulk-link"),
                    Optional.empty());
              }
            });
    return this;
  }

  public DatabasePopulator addFlag(String nodeId, String requesterId) {
    nodeRepository.flagForUser(nodeId, requesterId, true);
    delay();
    return this;
  }

  public DatabasePopulator addNodeToTrash(String nodeId, String nodeParentId) {
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              Optional<Node> trashedNode = nodeRepository.getNode(nodeId);
              if (trashedNode.isEmpty())
                throw new IllegalArgumentException("Node does not exist");
              trashedNode.get().setAncestorIds(Constants.Db.RootId.TRASH_ROOT);
              trashedNode.get().setParentId(Constants.Db.RootId.TRASH_ROOT);
              nodeRepository.trashNode(nodeId, nodeParentId);
              nodeRepository.updateNode(trashedNode.get());
            });
    delay();
    return this;
  }

  /**
   * Waits until the system clock advances by at least 1ms so that consecutive
   * inserts get distinct epoch-millis timestamps (needed for sort-by-time tests).
   */
  private void delay() {
    long start = System.currentTimeMillis();
    while (System.currentTimeMillis() == start) {
      Thread.onSpinWait();
    }
  }
}
