// SPDX-FileCopyrightText: 2022 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.rest.services;

import com.google.common.net.MediaType;
import com.google.inject.Inject;
import com.zextras.carbonio.files.Files.Db.RootId;
import com.zextras.carbonio.files.dal.dao.User;
import com.zextras.carbonio.files.dal.dao.ebean.ACL.SharePermission;
import com.zextras.carbonio.files.dal.dao.ebean.FileVersion;
import com.zextras.carbonio.files.dal.dao.ebean.Node;
import com.zextras.carbonio.files.dal.dao.ebean.NodeType;
import com.zextras.carbonio.files.dal.repositories.interfaces.FileVersionRepository;
import com.zextras.carbonio.files.dal.repositories.interfaces.LinkRepository;
import com.zextras.carbonio.files.dal.repositories.interfaces.NodeRepository;
import com.zextras.carbonio.files.dal.repositories.interfaces.ShareRepository;
import com.zextras.carbonio.files.dal.repositories.interfaces.TombstoneRepository;
import com.zextras.carbonio.files.exceptions.InternalServerErrorException;
import com.zextras.carbonio.files.netty.utilities.BufferInputStream;
import com.zextras.carbonio.files.rest.types.BlobResponse;
import com.zextras.carbonio.files.utilities.MimeTypeUtils;
import com.zextras.carbonio.files.utilities.PermissionsChecker;
import com.zextras.filestore.api.Filestore;
import com.zextras.filestore.api.UploadResponse;
import com.zextras.filestore.model.FilesIdentifier;
import io.ebean.annotation.Transactional;
import io.vavr.control.Try;
import java.text.MessageFormat;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class BlobService {

  private static final Logger logger = LoggerFactory.getLogger(BlobService.class);

  private final NodeRepository nodeRepository;
  private final FileVersionRepository fileVersionRepository;
  private final ShareRepository shareRepository;
  private final LinkRepository linkRepository;
  private final PermissionsChecker permissionsChecker;
  private final MimeTypeUtils mimeTypeUtils;
  private final TombstoneRepository tombstoneRepository;
  private final Filestore fileStore;


  @Inject
  public BlobService(
    NodeRepository nodeRepository,
    FileVersionRepository fileVersionRepository,
    ShareRepository shareRepository,
    LinkRepository linkRepository,
    PermissionsChecker permissionsChecker,
    TombstoneRepository tombstoneRepository,
    MimeTypeUtils mimeTypeUtils,
    Filestore fileStore
  ) {
    this.nodeRepository = nodeRepository;
    this.fileVersionRepository = fileVersionRepository;
    this.shareRepository = shareRepository;
    this.linkRepository = linkRepository;
    this.permissionsChecker = permissionsChecker;
    this.mimeTypeUtils = mimeTypeUtils;
    this.tombstoneRepository = tombstoneRepository;
    this.fileStore = fileStore;
  }

  public Optional<BlobResponse> downloadFile(
    String nodeId,
    Integer version
  ) {
    return nodeRepository
      .getNode(nodeId)
      .map(node -> fileVersionRepository
        .getFileVersion(node.getId(), version == null ? node.getCurrentVersion() : version)
        .flatMap(fileVersion -> Try
          .of(() -> fileStore
            .download(FilesIdentifier.of(
              fileVersion.getNodeId(),
              fileVersion.getVersion(),
              node.getOwnerId())
            )
          )
          .map((blob) -> new BlobResponse(
            blob,
            node.getFullName(),
            fileVersion.getSize(),
            fileVersion.getMimeType())
          )
          .orElse(() -> {
            logger.error(
              "Unable to download file with id {} and version {}",
              fileVersion.getNodeId(),
              fileVersion.getVersion()
            );
            return Optional.empty();
          }).toJavaOptional())
      .orElseGet(() -> {
        logger.error("Unable to download file {}: the node id does not exist", nodeId);
        return Optional.empty();
      });
  }

  public Optional<BlobResponse> downloadFileById(
    String nodeId,
    Integer version,
    User requester
  ) {
    if (permissionsChecker
      .getPermissions(nodeId, requester.getId())
      .has(SharePermission.READ_ONLY)
    ) {
      return downloadFile(nodeId, version);
    }

    logger.warn(
      "User {} does not have the necessary permission to download the node {}",
      requester.getId(),
      nodeId
    );
    return Optional.empty();
  }

  public Optional<BlobResponse> downloadFileByLink(String linkId) {
    return linkRepository
      .getLinkByPublicId(linkId)
      .flatMap(link -> downloadFile(link.getNodeId(), null));
  }

  @Transactional
  public Optional<String> uploadFile(
    User requester,
    BufferInputStream bufferInputStream,
    long blobLength,
    String folderId,
    String filename,
    String description
  ) {

    if (permissionsChecker
      .getPermissions(folderId, requester.getId())
      .has(SharePermission.READ_AND_WRITE)
    ) {

      String nodeId = UUID.randomUUID().toString();
      String nodeOwner = folderId.equals(RootId.LOCAL_ROOT)
        ? requester.getId()
        : nodeRepository.getNode(folderId)
          .map(Node::getOwnerId)
          .orElse(requester.getId());

      MediaType mediaType = mimeTypeUtils.detectMimeTypeFromFilename(
        filename,
        MediaType.OCTET_STREAM.toString()
      );

      NodeType nodeType = NodeType.getNodeType(mediaType);

      logger.debug("attributes read: " + filename);
      UploadResponse uploadResponse;
      try {
        uploadResponse = fileStore
          .uploadPost(
            FilesIdentifier.of(nodeId, 1, requester.getId()),
            bufferInputStream,
            blobLength
          );
        logger.debug(MessageFormat.format(
          "Uploaded file to storage with nodeId {0}, size: {1}, digest: {2}",
          nodeId,
          uploadResponse.getDigest(),
          uploadResponse.getSize()
        ));
      } catch (Exception exception) {
        logger.error(String.format(
          "Filestore failed to upload the node %s: %s",
          nodeId,
          exception
        ));

        return Try.failure(new InternalServerErrorException("Upload new node failed"));
      }

      Node folder = nodeRepository.getNode(folderId).get();
      nodeRepository.createNewNode(
        nodeId,
        requester.getId(),
        nodeOwner,
        folderId,
        searchAlternativeName(filename.trim(), folderId, nodeOwner),
        description,
        nodeType,
        NodeType.ROOT.equals(folder.getNodeType())
          ? folderId
          : folder.getAncestorIds() + "," + folderId,
        uploadResponse.getSize()
      );

      fileVersionRepository.createNewFileVersion(
        nodeId,
        requester.getId(),
        1,
        mediaType.toString(),
        uploadResponse.getSize(),
        uploadResponse.getDigest(),
        false
      );

      // Add new shares for the new file
      // Create share also for the requester if it is not the owner of the parent folder
      shareRepository
        .getShares(folderId, Collections.emptyList())
        .forEach(share -> shareRepository.upsertShare(
            nodeId,
            share.getTargetUserId(),
            share.getPermissions(),
            false,
            false,
            share.getExpiredAt()
          )
        );

      return Optional.of(nodeId);
      //return Try.success(nodeId);
    }

    return Optional.empty();
    //return Try.failure(new NodePermissionException());
  }

  @Transactional
  public Optional<Integer> uploadFileVersion(
    User requester,
    BufferInputStream bufferInputStream,
    long blobLength,
    String nodeId,
    String filename,
    boolean overwrite,
    int maxNumberOfVersions
  ) {

    if (permissionsChecker
      .getPermissions(nodeId, requester.getId())
      .has(SharePermission.READ_AND_WRITE)
    ) {

      MediaType mediaType = mimeTypeUtils.detectMimeTypeFromFilename(
        filename,
        MediaType.OCTET_STREAM.toString()
      );

      NodeType nodeType = NodeType.getNodeType(mediaType);

      Optional<Node> node = nodeRepository.getNode(nodeId);
      if (node.isEmpty()) {
        logger.debug(MessageFormat.format(
          "Node {0} not found",
          nodeId
        ));
        //return Try.failure(new NodeNotFoundException());
        return Optional.empty();
      }
      Node currNode = node.get();

      if (nodeType != node.get().getNodeType()) {
        logger.debug(MessageFormat.format(
          "Node {0} with wrong type {1}, should be the same as old versions: {2}",
          nodeId,
          node.get().getNodeType(),
          nodeType
        ));
        //return Try.failure(new BadRequest());
        return Optional.empty();
      }
      FileVersion oldestVersionNotKeptForever = null;
      if (!overwrite && getNumberOfVersionOfFile(nodeId) >= maxNumberOfVersions) {
        List<FileVersion> allFileVersion = fileVersionRepository.getFileVersions(nodeId);
        int keepForeverCounter = 0;
        for (FileVersion version : allFileVersion) {
          keepForeverCounter = version.isKeptForever()
            ? keepForeverCounter + 1
            : keepForeverCounter;
        }
        List<FileVersion> allVersionsNotKeptForever = allFileVersion.stream()
          .filter(version -> !version.isKeptForever())
          .collect(Collectors.toList());
        oldestVersionNotKeptForever = allVersionsNotKeptForever.get(
          allVersionsNotKeptForever.size() - 1);
        /*
        The List of not keep forever element is never <1, at this point the element that are
        kept forever are always less than max allowed.
        */
      }

      Optional<Integer> uploadResult = uploadFileVersionOperation(
        requester,
        bufferInputStream,
        blobLength,
        nodeId,
        currNode,
        mediaType,
        overwrite
      );
      logger.debug(MessageFormat.format(
        "File version upload result: {0} with node: {1}",
        uploadResult.isPresent()
          ? "success"
          : "failure",
        nodeId
      ));

      if (uploadResult.isPresent() && oldestVersionNotKeptForever != null) {
        fileVersionRepository.deleteFileVersion(oldestVersionNotKeptForever);
        tombstoneRepository.createTombstonesBulk(List.of(oldestVersionNotKeptForever), currNode.getOwnerId());
        logger.debug(MessageFormat.format(
          "After successful upload file version limit has been reached, deleting version {0} of {1} to make space for a new version",
          oldestVersionNotKeptForever.getVersion(),
          oldestVersionNotKeptForever.getNodeId()
        ));
      }
      return uploadResult;
    } else {
      logger.warn(
        "User {} does not have the necessary permission to upload the node {}",
        requester.getId(),
        nodeId
      );
      //return Try.failure(new NodePermissionException());
      return Optional.empty();
    }
  }


  private Optional<Integer> uploadFileVersionOperation(
    User requester,
    BufferInputStream bufferInputStream,
    long blobLength,
    String nodeId,
    Node node,
    MediaType mediaType,
    boolean overwrite
  ) {

    int newVersion = overwrite
      ? node.getCurrentVersion()
      : node.getCurrentVersion() + 1;

    UploadResponse uploadResponse = null;
    try {
      if (overwrite) {
        uploadResponse = fileStore
          .uploadPut(
            FilesIdentifier.of(nodeId, newVersion, requester.getId()),
            bufferInputStream,
            blobLength
          );

      } else {
        uploadResponse = fileStore
          .uploadPost(
            FilesIdentifier.of(nodeId, newVersion, requester.getId()),
            bufferInputStream,
            blobLength
          );
      }
    } catch (Exception exception) {
      logger.error("Unable to upload the file {} with version {}", nodeId, newVersion, exception);
      return Optional.empty();
    }

    if (overwrite) {
      FileVersion fileVersionOld = fileVersionRepository.getFileVersion(nodeId, newVersion).get();
      fileVersionRepository.deleteFileVersion(fileVersionOld);
    } else {
      node.setCurrentVersion(newVersion);
    }

    Optional<FileVersion> result = fileVersionRepository.createNewFileVersion(
      nodeId,
      requester.getId(),
      newVersion,
      mediaType.toString(),
      uploadResponse.getSize(),
      uploadResponse.getDigest(),
      false
    );
    node.setSize(uploadResponse.getSize());
    node.setLastEditorId(requester.getId());
    nodeRepository.updateNode(node);

    return result.map(FileVersion::getVersion);
    /*
    return result.map(fileVersion -> Try.success(fileVersion.getVersion()))
      .orElseGet(() -> Try.failure(new InternalServerErrorException("Upload failed")));

    */

  }

  /**
   * This method is used to search an alternative name if the filename is already present in the
   * destination folder.
   *
   * @param filename            a {@link String} representing the filename of the node I have to
   *                            upload.
   * @param destinationFolderId is a {@link String } representing the id of the destination folder.
   * @return a {@link String} of the alternative name if the filename is already taken or the chosen
   * filename.
   */
  private String searchAlternativeName(
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

  public int getNumberOfVersionOfFile(String nodeId) {
    return fileVersionRepository.getFileVersions(nodeId).size();
  }
}
