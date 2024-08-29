// SPDX-FileCopyrightText: 2022 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.rest.services;

import com.google.common.net.MediaType;
import com.google.inject.Inject;
import com.zextras.carbonio.files.Files.Db.RootId;
import com.zextras.carbonio.files.config.FilesConfig;
import com.zextras.carbonio.files.dal.EbeanDatabaseManager;
import com.zextras.carbonio.files.dal.dao.User;
import com.zextras.carbonio.files.dal.dao.ebean.ACL.SharePermission;
import com.zextras.carbonio.files.dal.dao.ebean.FileVersion;
import com.zextras.carbonio.files.dal.dao.ebean.Link;
import com.zextras.carbonio.files.dal.dao.ebean.Node;
import com.zextras.carbonio.files.dal.dao.ebean.NodeType;
import com.zextras.carbonio.files.dal.repositories.interfaces.FileVersionRepository;
import com.zextras.carbonio.files.dal.repositories.interfaces.LinkRepository;
import com.zextras.carbonio.files.dal.repositories.interfaces.NodeRepository;
import com.zextras.carbonio.files.dal.repositories.interfaces.ShareRepository;
import com.zextras.carbonio.files.dal.repositories.interfaces.TombstoneRepository;
import com.zextras.carbonio.files.exceptions.DependencyException;
import com.zextras.carbonio.files.exceptions.FileTypeMismatchException;
import com.zextras.carbonio.files.exceptions.MaxNumberOfFileVersionsException;
import com.zextras.carbonio.files.netty.utilities.BufferInputStream;
import com.zextras.carbonio.files.rest.types.BlobResponse;
import com.zextras.carbonio.files.utilities.MimeTypeUtils;
import com.zextras.carbonio.files.utilities.PermissionsChecker;
import com.zextras.filestore.api.Filestore;
import com.zextras.filestore.api.UploadResponse;
import com.zextras.filestore.model.FilesIdentifier;
import io.ebean.Transaction;
import io.vavr.control.Try;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static com.zextras.carbonio.files.utilities.RenameNodeUtils.searchAlternativeName;

/**
 * Provides methods to handle blob operations like download (via node identifier and via public
 * link), upload of a new node and upload of a new version of a specific node.
 */
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
  private final FilesConfig filesConfig;
  private final EbeanDatabaseManager ebeanDatabaseManager;

  @Inject
  public BlobService(
    NodeRepository nodeRepository,
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

  /**
   * Downloads from the {@link Filestore} a blob related to an identifier of a public node.
   *
   * @param nodeId    is a {@link String} representing the node identifier
   * @return an {@link Optional} of {@link BlobResponse} containing the stream of bytes (the blob
   * itself) and all its metadata if the {@link Node} exists, and it is contained on a public folder.
   * Otherwise, it returns an {@link Optional#empty()}.
   *
   * @throws DependencyException if the {@link Filestore} failed to download the blob
   */
  public Optional<BlobResponse> downloadPublicFileById(String nodeId) {
    return nodeRepository
      .getNode(nodeId)
      .filter(linkRepository::hasNodeANotExpiredPublicLink)
      .flatMap(node -> downloadFile(nodeId, null));
  }

  /**
   * Downloads from the {@link Filestore} a specific blob linked to a public link.
   *
   * @param linkId is a {@link String} representing the identifier of a {@link Link} that is linked
   *               to a specific node identifier
   * @return an {@link Optional} of {@link BlobResponse} containing the stream of bytes (the blob
   * itself) and all its metadata if the {@link Link} and the related {@link Node} exist. Otherwise,
   * it returns an {@link Optional#empty()}.
   * @throws DependencyException if the {@link Filestore} failed to download the blob
   */
  public Optional<BlobResponse> downloadFileByLink(String linkId) {
    return linkRepository
      .getLinkByNotExpiredPublicId(linkId)
      .flatMap(link -> downloadFile(link.getNodeId(), null));
  }

  /**
   * Uploads a blob to the {@link Filestore} and, when the upload is completed, it:
   * <ul>
   *   <li>creates the related {@link Node} and {@link FileVersion} metadata</li>
   *   <li>creates the shares for the new node if the destination folder has shares associated</li>
   * </ul>
   *
   * @param requester         is a {@link User} making the upload request
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
      // Here we are sure that the node exists otherwise the permission checker would be failed
      Node destinationFolder = nodeRepository.getNode(folderId).get();
      String nodeId = UUID.randomUUID().toString();
      String nodeOwner = folderId.equals(RootId.LOCAL_ROOT)
        ? requester.getId()
        : destinationFolder.getOwnerId();

      MediaType mediaType = mimeTypeUtils.detectMimeTypeFromFilename(
        filename,
        MediaType.OCTET_STREAM.toString()
      );

      NodeType nodeType = NodeType.getNodeType(mediaType);

      Node newNode = nodeRepository.createNewNode(
        nodeId,
        requester.getId(),
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
            FilesIdentifier.of(nodeId, 1, requester.getId()),
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
          requester.getId(),
          1,
          mediaType.toString(),
          uploadResponse.getSize(),
          uploadResponse.getDigest(),
          false
        );

        newNode.setSize(uploadResponse.getSize());
        nodeRepository.updateNode(newNode);

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
        t.commit();
      }

      return Optional.of(nodeId);
    }

    logger.warn(
      "User {} does not have the necessary permission to upload the node {} on the folder {}",
      requester.getId(),
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
      List<FileVersion> allFileVersion = fileVersionRepository.getFileVersions(nodeId);
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
}
