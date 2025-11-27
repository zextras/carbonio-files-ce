// SPDX-FileCopyrightText: 2022 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.rest.services;

import com.google.common.net.MediaType;
import com.google.inject.Inject;
import com.zextras.carbonio.files.Constants;
import com.zextras.carbonio.files.Constants.Db.RootId;
import com.zextras.carbonio.files.config.FilesConfig;
import com.zextras.carbonio.files.dal.DatabaseManager;
import com.zextras.carbonio.files.dal.dao.ebean.ACL.SharePermission;
import com.zextras.carbonio.files.dal.dao.ebean.FileVersion;
import com.zextras.carbonio.files.dal.dao.ebean.Link;
import com.zextras.carbonio.files.dal.dao.ebean.Node;
import com.zextras.carbonio.files.dal.dao.ebean.NodeType;
import com.zextras.carbonio.files.dal.repositories.impl.ebean.utilities.AddedNodeType;
import com.zextras.carbonio.files.dal.repositories.impl.ebean.utilities.FileVersionSort;
import com.zextras.carbonio.files.dal.repositories.interfaces.*;
import com.zextras.carbonio.files.exceptions.*;
import com.zextras.carbonio.files.netty.utilities.BufferInputStream;
import com.zextras.carbonio.files.rest.types.BlobResponse;
import com.zextras.carbonio.files.utilities.MimeTypeUtils;
import com.zextras.carbonio.files.utilities.PermissionsChecker;
import com.zextras.carbonio.usermanagement.entities.UserMyself;
import com.zextras.filestore.api.Filestore;
import com.zextras.filestore.api.UploadResponse;
import com.zextras.filestore.model.FilesIdentifier;
import io.ebean.Transaction;
import io.vavr.control.Try;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;
import java.io.IOException;
import java.io.InputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipOutputStream;

import static com.zextras.carbonio.files.utilities.RenameNodeUtils.searchAlternativeName;

/**
 * Provides methods to handle blob operations like download (via node identifier and via public
 * link), upload of a new node and upload of a new version of a specific node.
 */
public class BlobService {

  private static final Logger logger = LoggerFactory.getLogger(BlobService.class);

  private final NodeRepository nodeRepository;
  private final NotificationRepository notificationRepository;
  private final FileVersionRepository fileVersionRepository;
  private final ShareRepository shareRepository;
  private final LinkRepository linkRepository;
  private final PermissionsChecker permissionsChecker;
  private final MimeTypeUtils mimeTypeUtils;
  private final TombstoneRepository tombstoneRepository;
  private final Filestore fileStore;
  private final FilesConfig filesConfig;
  private final DatabaseManager databaseManagerFlyway;

  @Inject
  public BlobService(
      NodeRepository nodeRepository,
      NotificationRepository notificationRepository,
      FileVersionRepository fileVersionRepository,
      ShareRepository shareRepository,
      LinkRepository linkRepository,
      PermissionsChecker permissionsChecker,
      TombstoneRepository tombstoneRepository,
      MimeTypeUtils mimeTypeUtils,
      Filestore fileStore,
      FilesConfig filesConfig,
      DatabaseManager databaseManagerFlyway
  ) {
    this.nodeRepository = nodeRepository;
    this.notificationRepository = notificationRepository;
    this.fileVersionRepository = fileVersionRepository;
    this.shareRepository = shareRepository;
    this.linkRepository = linkRepository;
    this.permissionsChecker = permissionsChecker;
    this.mimeTypeUtils = mimeTypeUtils;
    this.tombstoneRepository = tombstoneRepository;
    this.fileStore = fileStore;
    this.filesConfig = filesConfig;
    this.databaseManagerFlyway = databaseManagerFlyway;
  }

  public Optional<List<Node>> checkDownloadMultiple(
      List<String> nodeIds,
      UserMyself requester
  ) {
    return checkDownloadMultipleInternal(
        nodeIds,
        node -> permissionsChecker.getPermissions(node.getId(), requester.getId().getUserId()).has(SharePermission.READ_ONLY),
        nodeId -> nodeRepository.calculateRelativeFolderSize(nodeId, requester.getId().getUserId())
            .orElseThrow(() -> new ZipGenerationException("Can't calculate size of folder " + nodeId)),
        requester.getId().getUserId()
    );
  }

  public Optional<List<Node>> checkDownloadPublicMultiple(
      List<String> nodeIds,
      String nodeLinkId,
      String accessCode
  ) {
    Optional<Link> linkOpt = linkRepository.getLinkByNotExpiredPublicId(nodeLinkId);
    if (linkOpt.isEmpty()) {
      return Optional.empty();
    }

    Link link = linkOpt.get();
    if (link.getAccessCode().isPresent() && !link.getAccessCode().get().equals(accessCode)) {
      return Optional.empty();
    }

    return checkDownloadMultipleInternal(
        nodeIds,
        node -> linkRepository.isLinkValidForNode(nodeLinkId, node) &&
            nodeRepository.getTrashedNode(node.getId()).isEmpty(),
        nodeId -> nodeRepository.calculateAbsoluteFolderSize(nodeId)
            .orElseThrow(() -> new ZipGenerationException("Can't calculate size of folder " + nodeId)),
        null
    );
  }

  private Optional<List<Node>> checkDownloadMultipleInternal(
      List<String> nodeIds,
      Function<Node, Boolean> accessChecker,
      Function<String, Long> folderSizeCalculator,
      String requesterId
  ) {
    if (nodeIds.isEmpty()) {
      logger.error("Cannot create ZIP: no nodes provided");
      return Optional.empty();
    }

    // Consider LOCAL_ROOT as an alias for its children
    // Might want to isolate this in the future if we want to support more aliases, for example all shared with me etc
    if (nodeIds.contains(Constants.Db.RootId.LOCAL_ROOT)) {
      if (nodeIds.size() > 1) {
        logger.warn("Cannot create ZIP: if root is present it must be the only node passed. NodeIds: {}", nodeIds);
        throw new AliasNotAloneInDownload("If LOCAL_ROOT is passed it must be the only node passed.");
      }

      if (requesterId == null) {
        return Optional.empty();
      }

      nodeIds = nodeRepository.getChildrenIds(
          RootId.LOCAL_ROOT,
          Optional.empty(),
          Optional.of(requesterId),
          false);
    }

    List<Node> nodes = new ArrayList<>();
    Long totalSizeRequested = 0L;

    for (String nodeId : nodeIds) {
      Node node = nodeRepository.getNode(nodeId).orElse(null);
      if (node == null || !accessChecker.apply(node)) {
        logger.error("Node with id {} not found", nodeId);
        continue;
      }

      if (node.getNodeType().equals(NodeType.FOLDER)) {
        totalSizeRequested += folderSizeCalculator.apply(node.getId());
      } else {
        totalSizeRequested += node.getSize();
      }

      nodes.add(node);
    }

    double totalSizeRequestedInMb = totalSizeRequested / (1024.0 * 1024.0);
    Optional<Integer> maxFileSize = filesConfig.getMaxDownloadableFileSizeInMb();
    logger.info("Requested file size: {}", totalSizeRequestedInMb);
    logger.info("Max file size: {}", maxFileSize);

    if (maxFileSize.isPresent() && totalSizeRequestedInMb > maxFileSize.get()) {
      throw new FileSizeException("File size exceeds the maximum allowed of " + maxFileSize.get() + "MB");
    }

    return Optional.of(nodes);
  }

  public Optional<BlobResponse> downloadMultiple(
      List<String> nodeIds,
      UserMyself requester
  ) {
    Optional<List<Node>> optNodes = checkDownloadMultiple(nodeIds, requester);
    return optNodes.flatMap(nodes -> createZip(
        nodes,
        node -> permissionsChecker.getPermissions(node.getId(), requester.getId().getUserId()).has(SharePermission.READ_ONLY)
    ));
  }

  public Optional<BlobResponse> downloadPublicMultiple(
      List<String> nodeIds,
      String nodeLinkId,
      String accessCode
  ) {
    Optional<List<Node>> optNodes = checkDownloadPublicMultiple(nodeIds, nodeLinkId, accessCode);
    return optNodes.flatMap(nodes -> createZip(
        nodes,
        node -> linkRepository.isLinkValidForNode(nodeLinkId, node) &&
            nodeRepository.getTrashedNode(node.getId()).isEmpty()
    ));
  }

  public Optional<Node> checkDownloadFileById(
      String nodeId,
      UserMyself requester
  ) {
    if (permissionsChecker
        .getPermissions(nodeId, requester.getId().getUserId())
        .has(SharePermission.READ_ONLY)
    ) {
      Node node = nodeRepository.getNode(nodeId).get();
      validateFileSize(node.getSize());
      return Optional.of(node);
    }

    logger.warn(
        "User {} does not have the necessary permission to download the node {}",
        requester.getId(),
        nodeId
    );
    return Optional.empty();
  }

  public Optional<Node> checkDownloadPublicFileById(
      String nodeId,
      String nodeLinkId,
      String accessCode
  ) {
    Optional<Node> nodeOptional = nodeRepository.getNode(nodeId);

    if (nodeOptional.isPresent() &&
        linkRepository.isLinkValidForNode(nodeLinkId, nodeOptional.get()) &&
        nodeRepository.getTrashedNode(nodeId).isEmpty()
    ) {
      Link link = linkRepository.getLinkByNotExpiredPublicId(nodeLinkId).get();

      if (link.getAccessCode().isPresent() && !link.getAccessCode().get().equals(accessCode)) {
        return Optional.empty();
      }

      Node node = nodeOptional.get();
      validateFileSize(node.getSize());
      return Optional.of(node);
    }

    return Optional.empty();
  }

  private void validateFileSize(Long size) {
    double totalSizeRequestedInMb = size / (1024.0 * 1024.0);
    Optional<Integer> maxFileSize = filesConfig.getMaxDownloadableFileSizeInMb();
    logger.info("Requested file size: {}", totalSizeRequestedInMb);
    logger.info("Max file size: {}", maxFileSize);

    if (maxFileSize.isPresent() && totalSizeRequestedInMb > maxFileSize.get()) {
      throw new FileSizeException("File size exceeds the maximum allowed of " + maxFileSize.get() + "MB");
    }
  }

  public Optional<BlobResponse> downloadFileById(
      String nodeId,
      @Nullable Integer version,
      UserMyself requester
  ) {
    Optional<Node> optNode = checkDownloadFileById(nodeId, requester);
    return optNode.flatMap(node -> downloadFile(nodeId, version));
  }

  public Optional<BlobResponse> downloadPublicFileById(
      String nodeId,
      String nodeLinkId,
      String accessCode
  ) {
    Optional<Node> nodeOptional = nodeRepository.getNode(nodeId);

    if (nodeOptional.isPresent() &&
        linkRepository.isLinkValidForNode(nodeLinkId, nodeOptional.get()) &&
        nodeRepository.getTrashedNode(nodeId).isEmpty()
    ) {
      Link link = linkRepository.getLinkByNotExpiredPublicId(nodeLinkId).get();

      if (link.getAccessCode().isPresent() && !link.getAccessCode().get().equals(accessCode)) {
        return Optional.empty();
      }

      return nodeOptional.flatMap(node -> downloadFile(nodeId, null));
    }

    return Optional.empty();
  }

  public Optional<BlobResponse> downloadFileByLink(String linkId) throws AccessCodeRequiredException {
    Optional<Link> linkOptional = linkRepository.getLinkByNotExpiredPublicId(linkId);
    if (linkOptional.isPresent() && linkOptional.get().getAccessCode().isPresent()) {
      throw new AccessCodeRequiredException("Access code is required to download the file");
    }
    return linkOptional
        .flatMap(link -> {
          if (nodeRepository.getTrashedNode(link.getNodeId()).isPresent()) {
            logger.error("Unable to download node {}: the node is trashed", link.getNodeId());
            return Optional.empty();
          }
          return downloadFile(link.getNodeId(), null);
        });
  }

  public Optional<String> uploadFile(
      String requesterId,
      Optional<UserMyself> requesterEntity,
      BufferInputStream bufferInputStream,
      long blobLength,
      String folderId,
      String filename,
      String description
  ) {
    if (permissionsChecker
        .getPermissions(folderId, requesterId)
        .has(SharePermission.READ_AND_WRITE)
    ) {
      Node destinationFolder = nodeRepository.getNode(folderId).get();
      String nodeId = UUID.randomUUID().toString();
      String nodeOwner = folderId.equals(RootId.LOCAL_ROOT)
          ? requesterId
          : destinationFolder.getOwnerId();

      MediaType mediaType = mimeTypeUtils.detectMimeTypeFromFilename(
          filename,
          MediaType.OCTET_STREAM.toString()
      );

      NodeType nodeType = NodeType.getNodeType(mediaType);

      Node newNode = nodeRepository.createNewNode(
          nodeId,
          requesterId,
          nodeOwner,
          folderId,
          searchAlternativeName(nodeRepository, filename.trim(), folderId, nodeOwner),
          description,
          nodeType,
          NodeType.ROOT.equals(destinationFolder.getNodeType())
              ? folderId
              : destinationFolder.getAncestorIds() + "," + folderId,
          0L
      );

      UploadResponse uploadResponse = Try.of(() ->
          fileStore
              .uploadPost(
                  FilesIdentifier.of(nodeId, 1, requesterId),
                  bufferInputStream,
                  blobLength
              )
      ).getOrElseThrow(failure -> {
        nodeRepository.deleteNode(nodeId);
        throw new DependencyException(
            String.format("Storages failed: unable to upload node with id %s and version 1", nodeId),
            failure
        );
      });

      logger.info(
          "Uploaded file to storages successfully: nodeId {}, version 1, size: {}, digest: {}",
          nodeId,
          uploadResponse.getSize(),
          uploadResponse.getDigest()
      );

      if (!verifyBlobExists(nodeId, 1, nodeOwner)) {
        nodeRepository.deleteNode(nodeId);
        throw new DependencyException(
            String.format("Upload verification failed: blob not accessible for node %s", nodeId)
        );
      }

      try (Transaction t = databaseManagerFlyway.getEbeanDatabase().beginTransaction()) {
        fileVersionRepository.createNewFileVersion(
            nodeId,
            requesterId,
            1,
            mediaType.toString(),
            uploadResponse.getSize(),
            uploadResponse.getDigest(),
            false
        );

        newNode.setSize(uploadResponse.getSize());
        nodeRepository.updateNode(newNode);

        List<String> usersToNotify = new ArrayList<>();

        shareRepository
            .getShares(folderId, Collections.emptyList())
            .forEach(share -> {
                  if (!share.getTargetUserId().equals(requesterId)) {
                    usersToNotify.add(share.getTargetUserId());
                  }
                  shareRepository.upsertShare(
                      nodeId,
                      share.getTargetUserId(),
                      share.getPermissions(),
                      false,
                      false,
                      share.getExpiredAt()
                  );
                }
            );
        t.commit();

        if (!destinationFolder.getNodeType().equals(NodeType.ROOT) &&
            !requesterId.equals(destinationFolder.getOwnerId()) &&
            !usersToNotify.contains(requesterId)) {
          usersToNotify.add(destinationFolder.getOwnerId());
        }

        if (!usersToNotify.isEmpty() && filesConfig.areNotificationsEnabled() && requesterEntity.isPresent())
          notificationRepository.createAddedNodeNotification(newNode, destinationFolder, requesterEntity.get(), AddedNodeType.UPLOAD, usersToNotify);
      }

      return Optional.of(nodeId);
    }

    logger.warn(
        "User {} does not have the necessary permission to upload the node {} on the folder {}",
        requesterId,
        filename,
        folderId
    );
    return Optional.empty();
  }

  public Optional<Integer> uploadFileVersion(
      UserMyself requester,
      BufferInputStream bufferInputStream,
      long blobLength,
      String nodeId,
      String filename,
      boolean overwrite
  ) {

    if (permissionsChecker
        .getPermissions(nodeId, requester.getId().getUserId())
        .has(SharePermission.READ_AND_WRITE)
    ) {
      List<FileVersion> allFileVersion = fileVersionRepository.getFileVersions(nodeId, List.of(FileVersionSort.VERSION_DESC));
      int maxNumberOfVersions = filesConfig.getMaxNumberOfFileVersion();

      if (!overwrite && allFileVersion.size() > maxNumberOfVersions) {
        throw new MaxNumberOfFileVersionsException(String.format(
            "Node %s has reached max number of versions (%d), cannot add more versions",
            nodeId,
            maxNumberOfVersions
        ));
      }

      Node node = nodeRepository.getNode(nodeId).get();

      MediaType mediaType = mimeTypeUtils.detectMimeTypeFromFilename(
          filename,
          MediaType.OCTET_STREAM.toString()
      );

      NodeType nodeType = NodeType.getNodeType(mediaType);

      if (nodeType != node.getNodeType()) {
        throw new FileTypeMismatchException(String.format(
            "Node %s with wrong type %s, should be the same as old versions: %s",
            nodeId,
            nodeType,
            node.getNodeType()
        ));
      }

      return uploadFileVersionOperation(
          requester,
          bufferInputStream,
          blobLength,
          node,
          mediaType,
          overwrite
      ).map(versionUploaded -> {
        if (!overwrite && allFileVersion.size() >= maxNumberOfVersions) {
          List<FileVersion> allVersionsNotKeptForever = allFileVersion
              .stream()
              .filter(version -> !version.isKeptForever())
              .collect(Collectors.toList());
          FileVersion oldestVersionToDelete =
              allVersionsNotKeptForever.get(allVersionsNotKeptForever.size() - 1);

          fileVersionRepository.deleteFileVersion(oldestVersionToDelete);
          tombstoneRepository.createTombstonesBulk(
              List.of(oldestVersionToDelete),
              node.getOwnerId()
          );

          logger.info(
              "File version limit for node {} has been reached, deleting version {} to make space for the new one",
              oldestVersionToDelete.getNodeId(),
              oldestVersionToDelete.getVersion()
          );
        }

        return versionUploaded;
      });
    } else {
      logger.warn(
          "User {} does not have the necessary permission to upload a new version of the node {}",
          requester.getId(),
          nodeId
      );
      return Optional.empty();
    }
  }

  private Optional<BlobResponse> downloadFile(
      String nodeId,
      @Nullable Integer version
  ) {
    logger.info(String.format(
        "Start to download node with id %s and version %s",
        nodeId,
        version == null ? "latest" : version
    ));
    return nodeRepository
        .getNode(nodeId)
        .map(node -> fileVersionRepository
            .getFileVersion(node.getId(), version == null ? node.getCurrentVersion() : version)
            .map(fileVersion -> Try
                .of(() -> fileStore
                    .download(FilesIdentifier.of(
                        fileVersion.getNodeId(),
                        fileVersion.getVersion(),
                        node.getOwnerId())
                    )
                )
                .map(blob -> new BlobResponse(
                    blob,
                    node.getFullName(),
                    fileVersion.getSize(),
                    fileVersion.getMimeType())
                )
                .getOrElseThrow(failure -> {
                  throw new DependencyException(String.format(
                      "Storages failed: unable to download node with id %s and version %s",
                      fileVersion.getNodeId(),
                      fileVersion.getVersion()),
                      failure
                  );
                })))
        .orElseGet(() -> {
          logger.error("Unable to download node {}: the node id does not exist", nodeId);
          return Optional.empty();
        });
  }

  private Optional<Integer> uploadFileVersionOperation(
      UserMyself requester,
      BufferInputStream bufferInputStream,
      long blobLength,
      Node node,
      MediaType mediaType,
      boolean overwrite
  ) {

    String nodeId = node.getId();
    int versionToUpload = node.getCurrentVersion();

    UploadResponse uploadResponse;
    try {
      if (overwrite) {
        uploadResponse = fileStore
            .uploadPut(
                FilesIdentifier.of(nodeId, versionToUpload, requester.getId().getUserId()),
                bufferInputStream,
                blobLength
            );

        fileVersionRepository.deleteFileVersions(nodeId,
            Collections.singletonList(versionToUpload));

      } else {
        versionToUpload += 1;
        uploadResponse = fileStore
            .uploadPost(
                FilesIdentifier.of(nodeId, versionToUpload, requester.getId().getUserId()),
                bufferInputStream,
                blobLength
            );
      }
    } catch (Exception exception) {
      throw new DependencyException(
          String.format(
              "Storages failed: unable to upload node with id %s and version %d",
              nodeId,
              versionToUpload
          ),
          exception
      );
    }

    if (!verifyBlobExists(nodeId, versionToUpload, node.getOwnerId())) {
      throw new DependencyException(
          String.format("Upload verification failed: blob not accessible for node %s version %d", nodeId, versionToUpload)
      );
    }

    try (Transaction t = databaseManagerFlyway.getEbeanDatabase().beginTransaction()) {
      Optional<FileVersion> result = fileVersionRepository.createNewFileVersion(
          nodeId,
          requester.getId().getUserId(),
          versionToUpload,
          mediaType.toString(),
          uploadResponse.getSize(),
          uploadResponse.getDigest(),
          false
      );
      node.setSize(uploadResponse.getSize());
      node.setLastEditorId(requester.getId().getUserId());
      node.setCurrentVersion(versionToUpload);
      nodeRepository.updateNode(node);

      t.commit();

      logger.info(
          "Uploaded file to storages successfully: nodeId {}, version {}, size: {}, digest: {}",
          nodeId,
          versionToUpload,
          uploadResponse.getSize(),
          uploadResponse.getDigest()
      );

      return result.map(FileVersion::getVersion);
    }
  }

  private Optional<BlobResponse> createZip(
      List<Node> nodes,
      Function<Node, Boolean> accessChecker
  ) {
    logger.info("Creating ZIP with {} nodes", nodes.size());

    String zipName = "Files.zip";
    if (nodes.size() == 1) {
      zipName = nodes.get(0).getName() + ".zip";
    }

    try {
      PipedInputStream pipedInput = new PipedInputStream(8192);
      PipedOutputStream pipedOutput = new PipedOutputStream(pipedInput);

      CompletableFuture.runAsync(() -> {
        try (ZipOutputStream zos = new ZipOutputStream(pipedOutput)) {
          Set<String> usedPaths = new HashSet<>();
          for (Node node : nodes) {
            addNodeToZip(node, zos, "", accessChecker, usedPaths);
          }
          zos.finish();
        } catch (IOException e) {
          logger.error("Error creating ZIP stream", e);
          throw new ZipGenerationException("Failed to create ZIP stream", e);
        } catch (Exception e) {
          logger.error("Unexpected error during ZIP creation", e);
          throw new ZipGenerationException("Unexpected error during ZIP creation", e);
        } finally {
          try {
            pipedOutput.close();
          } catch (IOException ex) {
            logger.debug("Error closing pipedOutput", ex);
            throw new ZipGenerationException("Unexpected error during ZIP creation", ex);
          }
        }
      });

      return Optional.of(new BlobResponse(
          pipedInput,
          zipName,
          null,
          "application/zip")
      );

    } catch (IOException e) {
      logger.error("Failed to initialize ZIP stream", e);
      throw new ZipGenerationException("Failed to initialize ZIP stream", e);
    }
  }

  private void addNodeToZip(
      Node node,
      ZipOutputStream zos,
      String path,
      Function<Node, Boolean> accessChecker,
      Set<String> usedPaths
  ) {
    try {
      if (accessChecker.apply(node)) {
        if (node.getNodeType().equals(NodeType.FOLDER)) {
          addFolderToZip(node, zos, path, accessChecker, usedPaths);
        } else {
          addFileToZip(node, zos, path, usedPaths);
        }
      }
    } catch (ZipException e) {
      if (e.getMessage() != null && e.getMessage().startsWith("duplicate entry")) {
        logger.warn("Skipping duplicate entry for node: {} ({}), path: {}",
            node.getId(), node.getFullName(), path);
      } else {
        throw new ZipGenerationException("Error adding node " + node.getId() + " to ZIP", e);
      }
    } catch (Exception e) {
      throw new ZipGenerationException("Error adding node " + node.getId() + " to ZIP", e);
    }
  }

  private void addFolderToZip(
      Node folder,
      ZipOutputStream zos,
      String parentPath,
      Function<Node, Boolean> accessChecker,
      Set<String> usedPaths
  ) throws IOException {
    String folderPath = getUniqueNameForZip(
        parentPath.isEmpty() ? folder.getFullName() : parentPath + "/" + folder.getFullName(),
        usedPaths,
        true
    );

    ZipEntry folderEntry = new ZipEntry(folderPath + "/");
    zos.putNextEntry(folderEntry);
    zos.closeEntry();

    List<String> childrenIds = nodeRepository.getChildrenIds(
        folder.getId(),
        Optional.empty(),
        Optional.empty(),
        false
    );

    for (String childId : childrenIds) {
      Node childNode = nodeRepository.getNode(childId)
          .orElseThrow(() -> new ZipGenerationException("Child node not found: " + childId));

      addNodeToZip(childNode, zos, folderPath, accessChecker, usedPaths);
    }
  }

  private void addFileToZip(Node node, ZipOutputStream zos, String parentPath, Set<String> usedPaths) throws IOException {
    FileVersion fileVersion = fileVersionRepository.getLastFileVersion(node.getId()).get();

    String filePath = getUniqueNameForZip(
        parentPath.isEmpty() ? node.getFullName() : parentPath + "/" + node.getFullName(),
        usedPaths,
        false
    );

    ZipEntry entry = new ZipEntry(filePath);
    entry.setSize(fileVersion.getSize());
    zos.putNextEntry(entry);

    try (InputStream fileStream = fileStore.download(FilesIdentifier.of(
        fileVersion.getNodeId(),
        fileVersion.getVersion(),
        node.getOwnerId()))) {

      byte[] buffer = new byte[8192];
      int bytesRead;
      while ((bytesRead = fileStream.read(buffer)) != -1) {
        zos.write(buffer, 0, bytesRead);
      }
    } catch (Exception e) {
      throw new DependencyException(
          String.format("Storages failed: unable to download node with id %s", node.getId()),
          e
      );
    }

    zos.closeEntry();
  }

  private String getUniqueNameForZip(String originalName, Set<String> usedPaths, boolean isFolder) {
    String targetPath = isFolder ? originalName + "/" : originalName;

    if (!usedPaths.contains(targetPath)) {
      usedPaths.add(targetPath);
      return originalName;
    }

    String baseName = originalName;
    String extension = "";

    if (!isFolder) {
      int lastDotIndex = originalName.lastIndexOf('.');
      if (lastDotIndex > 0 && lastDotIndex < originalName.length() - 1) {
        baseName = originalName.substring(0, lastDotIndex);
        extension = originalName.substring(lastDotIndex);
      }
    }

    int counter = 1;
    String newName;
    String newPath;

    do {
      newName = baseName + " (" + counter + ")" + extension;
      newPath = isFolder ? newName + "/" : newName;
      counter++;
    } while (usedPaths.contains(newPath));

    usedPaths.add(newPath);
    return newName;
  }

  // It seems that manual checking is sometimes necessary
  private boolean verifyBlobExists(String nodeId, int version, String nodeOwner) {
    try {
      InputStream blobStream = fileStore.download(
          FilesIdentifier.of(nodeId, version, nodeOwner)
      );
      blobStream.close();
      return true;
    } catch (Exception e) {
      return false;
    }
  }
}