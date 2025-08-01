// SPDX-FileCopyrightText: 2022 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.rest.services;

import com.google.common.net.MediaType;
import com.google.inject.Inject;
import com.zextras.carbonio.files.Constants;
import com.zextras.carbonio.files.Constants.Db.RootId;
import com.zextras.carbonio.files.config.FilesConfig;
import com.zextras.carbonio.files.dal.EbeanDatabaseManager;
import com.zextras.carbonio.files.dal.dao.User;
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
  private final EbeanDatabaseManager ebeanDatabaseManager;

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
      EbeanDatabaseManager ebeanDatabaseManager
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
    this.ebeanDatabaseManager = ebeanDatabaseManager;
  }

  public Optional<List<Node>> checkDownloadMultiple(
      List<String> nodeIds,
      User requester
  ) {
    if (nodeIds.isEmpty()) {
      logger.error("Cannot create ZIP: no nodes provided");
      return Optional.empty();
    }

    // Useful to cache both nodes and versions, since it's better to avoid multiple repository calls when not needed.
    // We handle that here because throwing an exception during zip generation will result in an empty zip since stream
    // has already started, so to minimize that we make sure the data needed exists before starting streaming.
    List<Node> nodes = new ArrayList<>();
    Set<String> processedNodeIds = new HashSet<>();
    String referenceParentId = null;
    Long totalSizeRequested = 0L;

    // If we encounter LOCAL_ROOT as node id, no other node can be passed since they obviously will not be on the same
    // level of hierarchy.
    if (nodeIds.contains(Constants.Db.RootId.LOCAL_ROOT)) {
      if (nodeIds.size() > 1) {
        logger.warn("Cannot create ZIP: if root is present it must be the only node passed. NodeIds: {}", nodeIds);
        throw new NodesOnDifferentLevelsException("All nodes must be in the same directory to create a ZIP file");
      }
      // Here we consider the LOCAL_ROOT as if the client requested the list of its children,
      // useful to avoid selecting all nodes and pass the root as an "alias".
      // Here we replace the input list with the children of root if LOCAL_ROOT is the only element of the list.
      nodeIds = nodeRepository.getChildrenIds(
          RootId.LOCAL_ROOT,
          Optional.empty(),
          Optional.of(requester.getId()),
          false);
    }

    for (String nodeId : nodeIds) {
      if (processedNodeIds.contains(nodeId)) {
        logger.debug("Duplicate nodeId {} ignored", nodeId);
        continue;
      }

      Node node = nodeRepository.getNode(nodeId).orElse(null);
      if (node == null) {
        logger.error("Node with id {} not found", nodeId); // Since permissions control passed, this should never happen
        return Optional.empty();
      }

      // Since this feature is not planned to work on shared nodes for now, here I avoided checking for permissions,
      // and instead I only check ownership. To be changed if in the future we want it to work with shared nodes.
      if (!node.getOwnerId().equals(requester.getId())) {
        logger.warn(
            "User {} is not the owner of the node {}. Operation aborted.",
            requester.getId(),
            nodeId
        );
        return Optional.empty();
      }

      // Cache parent node and return empty optional if nodes are on different levels of hierarchy.
      // If node doesn't have a parent it's a root or
      // is broken in some other way, if it's LOCAL_ROOT it's ok, return 404 otherwise.
      String currentParentId = node.getParentId().orElse(null);
      if (currentParentId == null && !node.getId().equals(Constants.Db.RootId.LOCAL_ROOT)) {
        logger.error("Parent not found for node with id {}", nodeId);
        return Optional.empty();
      }

      if (referenceParentId == null) {
        referenceParentId = currentParentId;
      } else if (!Objects.equals(referenceParentId, currentParentId)) {
        logger.warn("Cannot create ZIP: nodes are not on the same level. NodeIds: {}", nodeIds);
        throw new NodesOnDifferentLevelsException("All nodes must be in the same directory to create a ZIP file");
      }

      // Let's calculate the size of every node and sum it
      if (node.getNodeType().equals(NodeType.FOLDER)) {
        totalSizeRequested += nodeRepository.calculateFolderSize(node.getId()).orElse(0L);
      } else {
        totalSizeRequested += node.getSize();
      }

      nodes.add(node);
      processedNodeIds.add(nodeId);
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

  /**
   * Here we check permissions for each node, while also checking if they all are on the same level (parent directory)
   * and if they all exist with their version. If even a single node has any of these problems, we return an empty optional.
   * We build here the list of nodes to avoid multiple repository access when not needed.
   * We also handle duplicates ignoring them, avoiding returning an error.
   * If the list contains LOCAL_ROOT it's a special case where we download everything, and since the root does not
   * have a parent we handle that differently.
   */
  public Optional<BlobResponse> downloadMultiple(
      List<String> nodeIds,
      User requester
  ) {
    Optional<List<Node>> optNodes = checkDownloadMultiple(nodeIds, requester);
    return optNodes.flatMap(this::downloadMultipleInZip);
  }

  public Optional<Node> checkDownloadFileById(
      String nodeId,
      User requester
  ) {
    if (permissionsChecker
        .getPermissions(nodeId, requester.getId())
        .has(SharePermission.READ_ONLY)
    ) {
      Node node = nodeRepository.getNode(nodeId).get();
      double totalSizeRequestedInMb = node.getSize() / (1024.0 * 1024.0);
      Optional<Integer> maxFileSize = filesConfig.getMaxDownloadableFileSizeInMb();
      logger.info("Requested file size: {}", totalSizeRequestedInMb);
      logger.info("Max file size: {}", maxFileSize);
      if (maxFileSize.isPresent() && totalSizeRequestedInMb > maxFileSize.get()) {
        throw new FileSizeException("File size exceeds the maximum allowed of " + maxFileSize.get() + "MB");
      }
      return Optional.of(node);
    }

    logger.warn(
        "User {} does not have the necessary permission to download the node {}",
        requester.getId(),
        nodeId
    );
    return Optional.empty();
  }

  /**
   * Downloads from the {@link Filestore} a blob related to a node identifier and/or a specific
   * version.
   *
   * @param nodeId    is a {@link String} representing the node identifier
   * @param version   is s {@link Integer} representing the node version. If the version is null,
   *                  the method downloads the latest version of the node
   * @param requester is a {@link User} making the download request
   * @return an {@link Optional} of {@link BlobResponse} containing the stream of bytes (the blob
   * itself) and all its metadata if the requester has the {@link SharePermission#READ} permission
   * and the {@link Node} exists. Otherwise, it returns an {@link Optional#empty()}.
   * @throws DependencyException if the {@link Filestore} failed to download the blob
   */
  public Optional<BlobResponse> downloadFileById(
      String nodeId,
      @Nullable Integer version,
      User requester
  ) {
    Optional<Node> optNode = checkDownloadFileById(nodeId, requester);
    if (optNode.isPresent()) {
      return downloadFile(nodeId, version);
    } else {
      return Optional.empty();
    }
  }

  /**
   * Downloads from the {@link Filestore} a blob related to an identifier of a public node.
   *
   * @param nodeId     is a {@link String} representing the node identifier
   * @param nodeLinkId is a {@link String} representing the link public id
   * @param accessCode
   * @return an {@link Optional} of {@link BlobResponse} containing the stream of bytes (the blob
   * itself) and all its metadata if the {@link Node} exists, and it is contained on a public folder with a valid link.
   * Otherwise, it returns an {@link Optional#empty()}.
   * @throws DependencyException if the {@link Filestore} failed to download the blob
   */
  public Optional<BlobResponse> downloadPublicFileById(String nodeId, String nodeLinkId, String accessCode) {
    Optional<Node> nodeOptional = nodeRepository.getNode(nodeId);

    if (nodeOptional.isPresent() &&
        linkRepository.isLinkValidForNode(nodeLinkId, nodeOptional.get()) &&
        nodeRepository.getTrashedNode(nodeId).isEmpty() // Should not be trashed, if it is download will fail
    ) {
      Link link = linkRepository.getLinkByNotExpiredPublicId(nodeLinkId).get();
      // If file is protected by access code, check if the access code is correct and return empty if not
      if (link.getAccessCode().isPresent() && !link.getAccessCode().get().equals(accessCode)) {
        return Optional.empty();
      }
      return nodeOptional.flatMap(node -> downloadFile(nodeId, null));
    }

    return Optional.empty();
  }

  /**
   * Downloads from the {@link Filestore} a specific blob linked to a public link.
   *
   * @param linkId is a {@link String} representing the identifier of a {@link Link} that is linked
   *               to a specific node identifier
   * @return an {@link Optional} of {@link BlobResponse} containing the stream of bytes (the blob
   * itself) and all its metadata if the {@link Link} and the related {@link Node} exist. Otherwise,
   * it returns an {@link Optional#empty()}.
   * @throws DependencyException         if the {@link Filestore} failed to download the blob
   * @throws AccessCodeRequiredException if the link is protected by an access code (no direct download allowed)
   */
  public Optional<BlobResponse> downloadFileByLink(String linkId) throws AccessCodeRequiredException {
    Optional<Link> linkOptional = linkRepository.getLinkByNotExpiredPublicId(linkId);
    if (linkOptional.isPresent() && linkOptional.get().getAccessCode().isPresent()) {
      throw new AccessCodeRequiredException("Access code is required to download the file");
    }
    return linkOptional
        .flatMap(link -> {
          if (nodeRepository.getTrashedNode(link.getNodeId()).isPresent()) {
            logger.error("Unable to download node {}: the node is trashed", link.getNodeId());
            return Optional.empty(); // Return empty if the node is trashed exactly as if the node didn't exist
          }
          return downloadFile(link.getNodeId(), null);
        });
  }

  /**
   * Uploads a blob to the {@link Filestore} and, when the upload is completed, it:
   * <ul>
   *   <li>creates the related {@link Node} and {@link FileVersion} metadata</li>
   *   <li>creates the shares for the new node if the destination folder has shares associated</li>
   * </ul>
   *
   * @param requesterId       is a {@link String} id of user making the upload request
   * @param bufferInputStream is a {@link BufferInputStream} of the blob to upload
   * @param blobLength        is a <code>long</code> representing the length of the blob
   * @param folderId          is a {@link String} representing the folder identifier where the node
   *                          will be uploaded
   * @param filename          is a {@link String} representing the full filename (name and
   *                          extension) of the node
   * @param description       is a {@link String} representing the description of the node
   * @return an {@link Optional} of {@link String} containing the identifier of the node associated
   * to the blob uploaded if the requester has the {@link SharePermission#READ_AND_WRITE} permission
   * on the destination folder and if the destination folder exists. Otherwise, it returns an
   * {@link Optional#empty()}.
   * @throws DependencyException if the {@link Filestore} failed to upload the blob
   */
  public Optional<String> uploadFile(
      String requesterId,
      Optional<User> requesterEntity,
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
      // Here we are sure that the node exists otherwise the permission checker would be failed
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

      try (Transaction t = ebeanDatabaseManager.getEbeanDatabase().beginTransaction()) {
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

        // Add new shares for the new file
        // Create share also for the requester if it is not the owner of the parent folder
        shareRepository
            .getShares(folderId, Collections.emptyList())
            .forEach(share -> {
                  // Don't notify the requester since it's dumb
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

        // If the requester is the owner of the parent folder, do not notify him since he did the upload himself
        // Also exclude uploads on root, since root can't be shared and does not have an owner
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

  /**
   * Uploads a blob to the {@link Filestore} representing a version of an existing {@link Node}. The
   * uploaded version can be a new one if the {@param overwrite} is <code>false</code>, otherwise
   * the version is overwritten to the latest one. When the upload is completed, it:
   * <ul>
   *   <li>checks if the number of versions a node can have has already reached the limit</li>
   *   <li>creates the related {@link FileVersion} metadata</li>
   *   <li>updates the {@link Node} metadata associated to it</li>
   *   <li>checks if it is necessary to make space for future versions deleting the oldest one that
   *   is not marked with the "keep forever" flag.</li>
   * </ul>
   *
   * @param requester         is a {@link User} making the upload version request
   * @param bufferInputStream is a {@link BufferInputStream} of the blob to upload
   * @param blobLength        is a <code>long</code> representing the length of the blob
   * @param nodeId            is a {@link String} representing the node identifier to which add the
   *                          new version
   * @param filename          is a {@link String} representing the full filename (name and
   *                          extension) of the node. It is necessary in order to check if the
   *                          {@link NodeType} of the new version is the same of all the other
   *                          version types.
   * @param overwrite         is a <code>boolean</code> that can be <code>true</code> if the
   *                          requester wants to overwrite the latest version of a specific node;
   *                          <code>false</code> if the requester wants to upload a new version
   * @return an {@link Optional} of {@link Integer} containing the version of the node uploaded if
   * the requester has the {@link SharePermission#READ_AND_WRITE} permission on the {@link Node} and
   * if the {@link Node} itself exists. Otherwise, it returns an {@link Optional#empty()}.
   * @throws MaxNumberOfFileVersionsException if the specific {@link Node} already reached the
   *                                          maximum number of version that a node can have
   * @throws FileTypeMismatchException        if the requester wants to upload a blob with a
   *                                          different {@link NodeType} than previous versions
   * @throws DependencyException              if the {@link Filestore} failed to upload the blob
   */
  public Optional<Integer> uploadFileVersion(
      User requester,
      BufferInputStream bufferInputStream,
      long blobLength,
      String nodeId,
      String filename,
      boolean overwrite
  ) {

    if (permissionsChecker
        .getPermissions(nodeId, requester.getId())
        .has(SharePermission.READ_AND_WRITE)
    ) {
      List<FileVersion> allFileVersion = fileVersionRepository.getFileVersions(nodeId, List.of(FileVersionSort.VERSION_DESC));
      int maxNumberOfVersions = filesConfig.getMaxNumberOfFileVersion();

      // This check seems (at first) useless since there is a mechanism to remove the oldest version
      // not flagged as keep forever. However, it remains useful when the sysadmin reduces the config
      // regarding the maximum number of versions a node can have
      if (!overwrite && allFileVersion.size() > maxNumberOfVersions) {
        throw new MaxNumberOfFileVersionsException(String.format(
            "Node %s has reached max number of versions (%d), cannot add more versions",
            nodeId,
            maxNumberOfVersions
        ));
      }

      // Here we are sure that the node exists otherwise the permission checker would be failed
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
        // Detect if it is necessary to delete an old version to make space for the new one
        // respecting the maxNumberOfVersion limit.
        // allFileVersion.size() does not contain the just created new FileVersion so the comparison
        // with the maxNumberOfVersion must be more strict (>=). (this saves a query to the db)
        if (!overwrite && allFileVersion.size() >= maxNumberOfVersions) {
          List<FileVersion> allVersionsNotKeptForever = allFileVersion
              .stream()
              .filter(version -> !version.isKeptForever())
              .collect(Collectors.toList());
          FileVersion oldestVersionToDelete =
              allVersionsNotKeptForever.get(allVersionsNotKeptForever.size() - 1);

          // The List of not keep forever elements is never <1, at this point the element that are
          // kept forever are always less than max allowed.
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
      User requester,
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
                FilesIdentifier.of(nodeId, versionToUpload, requester.getId()),
                bufferInputStream,
                blobLength
            );

        // Delete the metadata of the old version since they will be recreated below
        fileVersionRepository.deleteFileVersions(nodeId,
            Collections.singletonList(versionToUpload));

      } else {
        versionToUpload += 1;
        uploadResponse = fileStore
            .uploadPost(
                FilesIdentifier.of(nodeId, versionToUpload, requester.getId()),
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

    try (Transaction t = ebeanDatabaseManager.getEbeanDatabase().beginTransaction()) {
      Optional<FileVersion> result = fileVersionRepository.createNewFileVersion(
          nodeId,
          requester.getId(),
          versionToUpload,
          mediaType.toString(),
          uploadResponse.getSize(),
          uploadResponse.getDigest(),
          false
      );
      node.setSize(uploadResponse.getSize());
      node.setLastEditorId(requester.getId());
      // The update of the current version can be overkill when the version is overwritten but,
      // since we are already doing the sql query to update the other node metadata, it doesn't add
      // any extra costs, and it makes the code more readable.
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

  private Optional<BlobResponse> downloadMultipleInZip(List<Node> nodes) {
    logger.info("Creating ZIP with {} nodes", nodes.size());

    try {
      PipedInputStream pipedInput = new PipedInputStream(8192);
      PipedOutputStream pipedOutput = new PipedOutputStream(pipedInput);

      CompletableFuture.runAsync(() -> {
        try (ZipOutputStream zos = new ZipOutputStream(pipedOutput)) {
          for (Node node : nodes) {
            addNodeToZip(node, zos, "");
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
          "files.zip",
          null,
          "application/zip")
      );

    } catch (IOException e) {
      logger.error("Failed to initialize ZIP stream", e);
      throw new ZipGenerationException("Failed to initialize ZIP stream", e);
    }
  }

  private void addNodeToZip(Node node, ZipOutputStream zos, String path) {
    try {
      if (node.getNodeType().equals(NodeType.FOLDER)) {
        addFolderToZip(node, zos, path);
      } else {
        addFileToZip(node, zos, path);
      }
    } catch (ZipException e) {
      if (e.getMessage() != null && e.getMessage().startsWith("duplicate entry")) {
        logger.warn("Skipping duplicate entry for node: {} ({}), path: {}",
            node.getId(), node.getFullName(), path); // should never happen (last famous words)
      } else {
        throw new ZipGenerationException("Error adding node " + node.getId() + " to ZIP", e);
      }
    } catch (Exception e) {
      throw new ZipGenerationException("Error adding node " + node.getId() + " to ZIP", e);
    }
  }

  private void addFolderToZip(Node folder, ZipOutputStream zos, String parentPath) throws IOException {
    String folderPath = parentPath.isEmpty() ? folder.getFullName() : parentPath + "/" + folder.getFullName();

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

      addNodeToZip(childNode, zos, folderPath);
    }
  }

  private void addFileToZip(Node node, ZipOutputStream zos, String parentPath) throws IOException {
    FileVersion fileVersion = fileVersionRepository.getLastFileVersion(node.getId()).get(); // a file always has a version

    String filePath = parentPath.isEmpty() ? node.getFullName() : parentPath + "/" + node.getFullName();

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
          String.format("Storages failed: unable to upload node with id %s and version 1", node.getId()),
          e
      );
    }

    zos.closeEntry();
  }
}
