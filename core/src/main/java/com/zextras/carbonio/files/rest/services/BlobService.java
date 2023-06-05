// SPDX-FileCopyrightText: 2022 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.rest.services;

import com.google.common.net.MediaType;
import com.google.inject.Inject;
import com.zextras.carbonio.files.Files.Db.RootId;
import com.zextras.carbonio.files.config.FilesConfig;
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
import com.zextras.carbonio.files.exceptions.NodeNotFoundException;
import com.zextras.carbonio.files.exceptions.NodePermissionException;
import com.zextras.carbonio.files.netty.utilities.BufferInputStream;
import com.zextras.carbonio.files.rest.types.BlobResponse;
import com.zextras.carbonio.files.utilities.MimeTypeUtils;
import com.zextras.carbonio.files.utilities.PermissionsChecker;
import com.zextras.carbonio.usermanagement.exceptions.BadRequest;
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

  private final NodeRepository        nodeRepository;
  private final FileVersionRepository fileVersionRepository;
  private final ShareRepository       shareRepository;
  private final LinkRepository        linkRepository;
  private final PermissionsChecker    permissionsChecker;
  private final FilesConfig           filesConfig;
  private final MimeTypeUtils         mimeTypeUtils;
  private final TombstoneRepository   tombstoneRepository;

  private static final Logger logger = LoggerFactory.getLogger(BlobService.class);

  @Inject
  public BlobService(
    NodeRepository nodeRepository,
    FileVersionRepository fileVersionRepository,
    ShareRepository shareRepository,
    LinkRepository linkRepository,
    PermissionsChecker permissionsChecker,
    FilesConfig filesConfig,
    TombstoneRepository tombstoneRepository,
    MimeTypeUtils mimeTypeUtils
  ) {
    this.nodeRepository = nodeRepository;
    this.fileVersionRepository = fileVersionRepository;
    this.shareRepository = shareRepository;
    this.linkRepository = linkRepository;
    this.permissionsChecker = permissionsChecker;
    this.mimeTypeUtils = mimeTypeUtils;
    this.tombstoneRepository = tombstoneRepository;
    this.filesConfig = filesConfig;
  }

  public Try<BlobResponse> downloadFile(
    String nodeId,
    Optional<Integer> version,
    User requester
  ) {
    return nodeRepository
      .getNode(nodeId)
      .map(node -> fileVersionRepository
        .getFileVersion(node.getId(), version.orElse(node.getCurrentVersion()))
        .map(fileVersion -> Try
          .of(() -> filesConfig
            .getStorages()
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
        ).orElseGet(() -> Try.failure(new NodeNotFoundException()))
      ).orElseGet(() -> Try.failure(new NodeNotFoundException()));
  }

  public Try<BlobResponse> downloadFileByLink(String linkId) {
    return linkRepository.getLinkByPublicId(linkId)
      .map(link -> nodeRepository.getNode(link.getNodeId()))
      .filter(Optional::isPresent)
      .map(Optional::get)
      .map(node -> fileVersionRepository.getFileVersion(node.getId(), node.getCurrentVersion())
        .map(fileVersion -> Try
          .of(() -> filesConfig
            .getStorages()
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
        ).orElseGet(() -> Try.failure(new NodeNotFoundException()))
      ).orElseGet(() -> Try.failure(new NodeNotFoundException()));
  }

  @Transactional
  public Try<String> uploadFile(
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
        uploadResponse = filesConfig
          .getStorages()
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

      return Try.success(nodeId);
    }

    return Try.failure(new NodePermissionException());
  }

  @Transactional
  public Try<Integer> uploadFileVersion(
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
        return Try.failure(new NodeNotFoundException());
      }
      Node currNode = node.get();

      if (nodeType != node.get().getNodeType()) {
        logger.debug(MessageFormat.format(
          "Node {0} with wrong type {1}, should be the same as old versions: {2}",
          nodeId,
          node.get().getNodeType(),
          nodeType
        ));
        return Try.failure(new BadRequest());
      }
      FileVersion lastVersion = null;
      if (!overwrite && getNumberOfVersionOfFile(nodeId) >= maxNumberOfVersions) {
        List<FileVersion> allVersion = fileVersionRepository.getFileVersions(nodeId);
        int keepForeverCounter = 0;
        for (FileVersion version : allVersion) {
          keepForeverCounter = version.isKeptForever()
            ? keepForeverCounter + 1
            : keepForeverCounter;
        }
        List<FileVersion> allVersionsNotKeptForever = allVersion.stream()
          .filter(version -> !version.isKeptForever())
          .collect(Collectors.toList());
        lastVersion = allVersionsNotKeptForever.get(
          allVersionsNotKeptForever.size() - 1);
        /*
        The List of not keep forever element is never <1, at this point the element that are
        kept forever are always less than max allowed.
        */
      }

      Try<Integer> uploadResult = uploadFileVersionOperation(
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
        uploadResult.isSuccess()
          ? "success"
          : "failure",
        nodeId
      ));

      if (uploadResult.isSuccess() && lastVersion != null) {
        fileVersionRepository.deleteFileVersion(lastVersion);
        tombstoneRepository.createTombstonesBulk(List.of(lastVersion), currNode.getOwnerId());
        logger.debug(MessageFormat.format(
          "After successful upload file version limit has been reached, deleting version {0} of {1} to make space for a new version",
          lastVersion.getVersion(),
          lastVersion.getNodeId()
        ));
      }
      return uploadResult;
    } else {
      logger.debug(MessageFormat.format(
        "User {0} does not have the necessary permission for node {1}",
        requester.getId(),
        nodeId
      ));
      return Try.failure(new NodePermissionException());
    }
  }

  private Try<Integer> uploadFileVersionOperation(
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
        uploadResponse = filesConfig
          .getStorages()
          .uploadPut(
            FilesIdentifier.of(nodeId, newVersion, requester.getId()),
            bufferInputStream,
            blobLength
          );

      } else {
        uploadResponse = filesConfig
          .getStorages()
          .uploadPost(
            FilesIdentifier.of(nodeId, newVersion, requester.getId()),
            bufferInputStream,
            blobLength
          );
      }
    } catch (Exception exception) {
      logger.error(String.format(
        "Filestore failed to upload the version %d for node %s: %s",
        newVersion,
        nodeId,
        exception
      ));

      return Try.failure(new InternalServerErrorException("Upload new version failed"));
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

    return result
      .map(fileVersion -> Try.success(fileVersion.getVersion()))
      .orElseGet(() -> Try.failure(new InternalServerErrorException("Upload failed")));

  }

  /**
   * This method is used to search an alternative name if the filename is already present in the
   * destination folder.
   *
   * @param filename a {@link String} representing the filename of the node I have to upload.
   * @param destinationFolderId is a {@link String } representing the id of the destination folder.
   *
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
