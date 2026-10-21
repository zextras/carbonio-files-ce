// SPDX-FileCopyrightText: 2022 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.rest.services;

import static com.zextras.carbonio.files.utilities.RenameNodeUtils.searchAlternativeName;

import com.google.common.net.MediaType;
import com.zextras.carbonio.files.Constants;
import com.zextras.carbonio.files.Constants.Db.RootId;
import com.zextras.carbonio.files.config.FilesConfig;
import com.zextras.carbonio.files.dal.dao.UserMyself;
import com.zextras.carbonio.files.dal.dao.ebean.ACL.SharePermission;
import com.zextras.carbonio.files.dal.dao.ebean.FileVersion;
import com.zextras.carbonio.files.dal.dao.ebean.Link;
import com.zextras.carbonio.files.dal.dao.ebean.Node;
import com.zextras.carbonio.files.dal.dao.ebean.NodeType;
import com.zextras.carbonio.files.dal.repositories.impl.ebean.utilities.AddedNodeType;
import com.zextras.carbonio.files.dal.repositories.impl.ebean.utilities.FileVersionSort;
import com.zextras.carbonio.files.dal.repositories.interfaces.FileVersionRepository;
import com.zextras.carbonio.files.dal.repositories.interfaces.LinkRepository;
import com.zextras.carbonio.files.dal.repositories.interfaces.NodeRepository;
import com.zextras.carbonio.files.dal.repositories.interfaces.NotificationRepository;
import com.zextras.carbonio.files.dal.repositories.interfaces.ShareRepository;
import com.zextras.carbonio.files.dal.repositories.interfaces.TombstoneRepository;
import com.zextras.carbonio.files.exceptions.AccessCodeRequiredException;
import com.zextras.carbonio.files.exceptions.AliasNotAloneInDownload;
import com.zextras.carbonio.files.exceptions.DependencyException;
import com.zextras.carbonio.files.exceptions.FileSizeException;
import com.zextras.carbonio.files.exceptions.FileTypeMismatchException;
import com.zextras.carbonio.files.exceptions.MaxNumberOfFileVersionsException;
import com.zextras.carbonio.files.exceptions.OverwriteInProgressException;
import com.zextras.carbonio.files.exceptions.ZipGenerationException;
import com.zextras.carbonio.files.rest.services.OverwriteLeases.Lease;
import com.zextras.carbonio.files.rest.services.VersionReservations.Reservation;
import com.zextras.carbonio.files.rest.types.BlobResponse;
import com.zextras.carbonio.files.utilities.MimeTypeUtils;
import com.zextras.carbonio.files.utilities.PermissionsChecker;
import com.zextras.filestore.api.Filestore;
import com.zextras.filestore.api.UploadResponse;
import com.zextras.filestore.model.BulkDeleteRequestItem;
import com.zextras.filestore.model.BulkDeleteResponseItem;
import com.zextras.filestore.model.FilesIdentifier;
import com.zextras.filestore.model.IdentifierType;
import io.micrometer.core.instrument.MeterRegistry;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.vavr.control.Try;
import jakarta.annotation.Nullable;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Provides methods to handle blob operations like download (via node identifier and via public
 * link), upload of a new node and upload of a new version of a specific node.
 *
 * <p>Quarkus port of the legacy Guice {@code BlobService}. The logic is preserved 1:1 with three
 * transport-driven adaptations:
 *
 * <ul>
 *   <li>the two manual Ebean transactions (upload / upload-version) become {@link
 *       QuarkusTransaction#requiringNew()} blocks, so the request-scoped {@code EntityManager} and
 *       the {@code @Transactional} repository methods join a single JTA transaction (same atomicity
 *       as the old {@code io.ebean.Transaction}). The DB-before-blob delete/write ordering is
 *       preserved verbatim. Since the version reservations, upload-version writes its blob outside
 *       any transaction: see {@link VersionReservations}.
 *   <li>upload bodies are plain {@link InputStream}s streamed straight from RESTEasy Reactive (the
 *       legacy Netty {@code BufferInputStream} is gone).
 *   <li>the ZIP multi-download no longer uses a {@code PipedInputStream} + off-thread producer:
 *       walking the node tree needs the request-scoped {@code EntityManager}, which is only alive
 *       on the request worker thread. Instead the tree is walked EAGERLY here (returning a
 *       lightweight {@link ZipDownload} plan of blob identifiers + paths, no blob bytes buffered)
 *       and the actual blob bytes are streamed lazily by the REST resource — which only needs the
 *       (request-context free) {@link Filestore}. The unique-name / folder-dedup logic is ported
 *       unchanged.
 * </ul>
 */
@ApplicationScoped
public class BlobService {

  private static final String NODE_NOT_FOUND = "Node not found: ";
  private static final Logger logger = LoggerFactory.getLogger(BlobService.class);

  // P4b: re-added upload counters dropped by P4a; same metric name/tags as the legacy
  // Guice PrometheusService (files.upload, tagged by service=files and uri=/upload(-version)).
  private static final String METRIC_UPLOAD = "files.upload";

  private final NodeRepository nodeRepository;
  private final NotificationRepository notificationRepository;
  private final FileVersionRepository fileVersionRepository;
  private final ShareRepository shareRepository;
  private final LinkRepository linkRepository;
  private final PermissionsChecker permissionsChecker;
  private final MimeTypeUtils mimeTypeUtils;
  private final Filestore fileStore;
  private final FilesConfig filesConfig;
  private final MeterRegistry meterRegistry;

  // P9 CE seams. QuotaChecker: CE ships a NoOpQuotaChecker default; Advanced supplies an
  // @Alternative @Priority(1) that enforces quota. UploadCompletionListener: CE registers none, so
  // the fan-out after a service-account upload is a no-op.
  private final QuotaChecker quotaChecker;
  private final Instance<UploadCompletionListener> uploadCompletionListeners;
  private final VersionReservations versionReservations;
  private final TombstoneRepository tombstoneRepository;
  private final OverwriteLeases overwriteLeases;

  @Inject
  public BlobService(
      NodeRepository nodeRepository,
      NotificationRepository notificationRepository,
      FileVersionRepository fileVersionRepository,
      ShareRepository shareRepository,
      LinkRepository linkRepository,
      PermissionsChecker permissionsChecker,
      MimeTypeUtils mimeTypeUtils,
      Filestore fileStore,
      FilesConfig filesConfig,
      MeterRegistry meterRegistry,
      QuotaChecker quotaChecker,
      @Any Instance<UploadCompletionListener> uploadCompletionListeners,
      VersionReservations versionReservations,
      TombstoneRepository tombstoneRepository,
      OverwriteLeases overwriteLeases) {
    this.nodeRepository = nodeRepository;
    this.notificationRepository = notificationRepository;
    this.fileVersionRepository = fileVersionRepository;
    this.shareRepository = shareRepository;
    this.linkRepository = linkRepository;
    this.permissionsChecker = permissionsChecker;
    this.mimeTypeUtils = mimeTypeUtils;
    this.fileStore = fileStore;
    this.filesConfig = filesConfig;
    this.meterRegistry = meterRegistry;
    this.quotaChecker = quotaChecker;
    this.uploadCompletionListeners = uploadCompletionListeners;
    this.versionReservations = versionReservations;
    this.tombstoneRepository = tombstoneRepository;
    this.overwriteLeases = overwriteLeases;
  }

  public Optional<List<Node>> checkDownloadMultiple(List<String> nodeIds, UserMyself requester) {
    return checkDownloadMultipleInternal(
        nodeIds,
        node ->
            permissionsChecker
                .getPermissions(node.getId(), requester.getId().getUserId())
                .has(SharePermission.READ_ONLY),
        nodeId ->
            nodeRepository
                .calculateRelativeFolderSize(nodeId, requester.getId().getUserId())
                .orElseThrow(
                    () -> new ZipGenerationException("Can't calculate size of folder " + nodeId)),
        requester.getId().getUserId());
  }

  public Optional<List<Node>> checkDownloadPublicMultiple(
      List<String> nodeIds, String nodeLinkId, String accessCode) {
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
        node ->
            linkRepository.isLinkValidForNode(nodeLinkId, node)
                && nodeRepository.getTrashedNode(node.getId()).isEmpty(),
        nodeId ->
            nodeRepository
                .calculateAbsoluteFolderSize(nodeId)
                .orElseThrow(
                    () -> new ZipGenerationException("Can't calculate size of folder " + nodeId)),
        null);
  }

  private Optional<List<Node>> checkDownloadMultipleInternal(
      List<String> nodeIds,
      Function<Node, Boolean> accessChecker,
      Function<String, Long> folderSizeCalculator,
      String requesterId) {
    if (nodeIds.isEmpty()) {
      logger.error("Cannot create ZIP: no nodes provided");
      return Optional.empty();
    }

    // Consider LOCAL_ROOT as an alias for its children
    // Might want to isolate this in the future if we want to support more aliases, for example all
    // shared with me etc
    if (nodeIds.contains(Constants.Db.RootId.LOCAL_ROOT)) {
      if (nodeIds.size() > 1) {
        logger.warn(
            "Cannot create ZIP: if root is present it must be the only node passed. NodeIds: {}",
            nodeIds);
        throw new AliasNotAloneInDownload(
            "If LOCAL_ROOT is passed it must be the only node passed.");
      }

      if (requesterId == null) {
        return Optional.empty();
      }

      nodeIds =
          nodeRepository.getChildrenIds(
              RootId.LOCAL_ROOT, Optional.empty(), Optional.of(requesterId), false);
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
      throw new FileSizeException(
          "File size exceeds the maximum allowed of " + maxFileSize.get() + "MB");
    }

    return Optional.of(nodes);
  }

  public Optional<ZipDownload> downloadMultiple(List<String> nodeIds, UserMyself requester) {
    Optional<List<Node>> optNodes = checkDownloadMultiple(nodeIds, requester);
    return optNodes.flatMap(
        nodes ->
            buildZipPlan(
                nodes,
                node ->
                    permissionsChecker
                        .getPermissions(node.getId(), requester.getId().getUserId())
                        .has(SharePermission.READ_ONLY)));
  }

  public Optional<ZipDownload> downloadPublicMultiple(
      List<String> nodeIds, String nodeLinkId, String accessCode) {
    Optional<List<Node>> optNodes = checkDownloadPublicMultiple(nodeIds, nodeLinkId, accessCode);
    return optNodes.flatMap(
        nodes ->
            buildZipPlan(
                nodes,
                node ->
                    linkRepository.isLinkValidForNode(nodeLinkId, node)
                        && nodeRepository.getTrashedNode(node.getId()).isEmpty()));
  }

  public Optional<Node> checkDownloadFileById(String nodeId, UserMyself requester) {
    if (permissionsChecker
        .getPermissions(nodeId, requester.getId().getUserId())
        .has(SharePermission.READ_ONLY)) {
      Node node = nodeRepository.getNode(nodeId).get();
      validateFileSize(node.getSize());
      return Optional.of(node);
    }

    logger.warn(
        "User {} does not have the necessary permission to download the node {}",
        requester.getId(),
        nodeId);
    return Optional.empty();
  }

  public Optional<Node> checkDownloadPublicFileById(
      String nodeId, String nodeLinkId, String accessCode) {
    Optional<Node> nodeOptional = nodeRepository.getNode(nodeId);

    if (nodeOptional.isPresent()
        && linkRepository.isLinkValidForNode(nodeLinkId, nodeOptional.get())
        && nodeRepository.getTrashedNode(nodeId).isEmpty()) {
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
      throw new FileSizeException(
          "File size exceeds the maximum allowed of " + maxFileSize.get() + "MB");
    }
  }

  public Optional<BlobResponse> downloadFileById(
      String nodeId, @Nullable Integer version, UserMyself requester) {
    Optional<Node> optNode = checkDownloadFileById(nodeId, requester);
    return optNode.flatMap(node -> downloadFile(nodeId, version));
  }

  public Optional<BlobResponse> downloadPublicFileById(
      String nodeId, String nodeLinkId, String accessCode) {
    Optional<Node> nodeOptional = nodeRepository.getNode(nodeId);

    if (nodeOptional.isPresent()
        && linkRepository.isLinkValidForNode(nodeLinkId, nodeOptional.get())
        && nodeRepository.getTrashedNode(nodeId).isEmpty()) {
      Link link = linkRepository.getLinkByNotExpiredPublicId(nodeLinkId).get();

      if (link.getAccessCode().isPresent() && !link.getAccessCode().get().equals(accessCode)) {
        return Optional.empty();
      }

      return nodeOptional.flatMap(node -> downloadFile(nodeId, null));
    }

    return Optional.empty();
  }

  public Optional<BlobResponse> downloadFileByLink(String linkId)
      throws AccessCodeRequiredException {
    Optional<Link> linkOptional = linkRepository.getLinkByNotExpiredPublicId(linkId);
    if (linkOptional.isPresent() && linkOptional.get().getAccessCode().isPresent()) {
      throw new AccessCodeRequiredException("Access code is required to download the file");
    }
    return linkOptional.flatMap(
        link -> {
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
      InputStream blobStream,
      long blobLength,
      String folderId,
      String filename,
      String description) {
    if (permissionsChecker
        .getPermissions(folderId, requesterId)
        .has(SharePermission.READ_AND_WRITE)) {
      Node destinationFolder = nodeRepository.getNode(folderId).get();
      String nodeId = UUID.randomUUID().toString();
      String nodeOwner =
          folderId.equals(RootId.LOCAL_ROOT) ? requesterId : destinationFolder.getOwnerId();

      // P9 CE seam: quota check before the blob is stored (no-op in CE).
      quotaChecker.ensureNotOverQuota(nodeOwner, blobLength);

      MediaType mediaType =
          mimeTypeUtils.detectMimeTypeFromFilename(filename, MediaType.OCTET_STREAM.toString());

      NodeType nodeType = NodeType.getNodeType(mediaType);

      String nodeName = searchAlternativeName(nodeRepository, filename.trim(), folderId, nodeOwner);
      nodeRepository.createNewNode(
          nodeId,
          requesterId,
          nodeOwner,
          folderId,
          nodeName,
          description,
          nodeType,
          NodeType.ROOT.equals(destinationFolder.getNodeType())
              ? folderId
              : destinationFolder.getAncestorIds() + "," + folderId,
          0L);

      UploadResponse uploadResponse =
          Try.of(
                  () ->
                      fileStore.uploadPost(
                          FilesIdentifier.of(nodeId, 1, nodeOwner), blobStream, blobLength))
              .getOrElseThrow(
                  failure -> {
                    nodeRepository.deleteNode(nodeId);
                    throw new DependencyException(
                        String.format(
                            "Storages failed: unable to upload node with id %s and version 1",
                            nodeId),
                        failure);
                  });

      logger.info(
          "Uploaded file to storages successfully: nodeId {}, version 1, size: {}, digest: {}",
          nodeId,
          uploadResponse.getSize(),
          uploadResponse.getDigest());

      if (!verifyBlobExists(nodeId, 1, nodeOwner)) {
        nodeRepository.deleteNode(nodeId);
        throw new DependencyException(
            String.format("Upload verification failed: blob not accessible for node %s", nodeId));
      }

      Node uploadedNode =
          QuarkusTransaction.requiringNew()
              .call(
                  () -> {
                    Node createdNode =
                        nodeRepository
                            .getNode(nodeId)
                            .orElseThrow(() -> new NoSuchElementException(NODE_NOT_FOUND + nodeId));
                    fileVersionRepository.createNewFileVersion(
                        nodeId,
                        requesterId,
                        1,
                        mediaType.toString(),
                        uploadResponse.getSize(),
                        uploadResponse.getDigest(),
                        false);

                    createdNode.setSize(uploadResponse.getSize());
                    nodeRepository.updateNode(createdNode);

                    List<String> usersToNotify = new ArrayList<>();

                    shareRepository
                        .getShares(folderId, Collections.emptyList())
                        .forEach(
                            share -> {
                              if (!share.getTargetUserId().equals(requesterId)) {
                                usersToNotify.add(share.getTargetUserId());
                              }
                              shareRepository.upsertShare(
                                  nodeId,
                                  share.getTargetUserId(),
                                  share.getPermissions(),
                                  false,
                                  false,
                                  share.getExpiredAt());
                            });

                    if (!destinationFolder.getNodeType().equals(NodeType.ROOT)
                        && !requesterId.equals(destinationFolder.getOwnerId())
                        && !usersToNotify.contains(requesterId)) {
                      usersToNotify.add(destinationFolder.getOwnerId());
                    }

                    if (!usersToNotify.isEmpty()
                        && filesConfig.areNotificationsEnabled()
                        && requesterEntity.isPresent())
                      notificationRepository.createAddedNodeNotification(
                          createdNode,
                          destinationFolder,
                          requesterEntity.get(),
                          AddedNodeType.UPLOAD,
                          usersToNotify);
                    return createdNode;
                  });

      meterRegistry.counter(METRIC_UPLOAD, "service", "files", "uri", "/upload").increment();

      // P9 CE seam: notify listeners after a successful service-account upload (no user requester
      // entity). CE registers none, so this is a no-op fan-out.
      if (requesterEntity.isEmpty()) {
        uploadCompletionListeners.forEach(
            listener -> listener.onUploadCompleted(uploadedNode, destinationFolder, requesterId));
      }

      return Optional.of(nodeId);
    }

    logger.warn(
        "User {} does not have the necessary permission to upload the node {} on the folder {}",
        requesterId,
        filename,
        folderId);
    return Optional.empty();
  }

  public Optional<Integer> uploadFileVersion(
      UserMyself requester,
      InputStream blobStream,
      long blobLength,
      String nodeId,
      String filename,
      boolean overwrite) {
    String requesterId = requester.getId().getUserId();

    if (!permissionsChecker
        .getPermissions(nodeId, requesterId)
        .has(SharePermission.READ_AND_WRITE)) {
      logger.warn(
          "User {} does not have the necessary permission to upload a new version of the node {}",
          requesterId,
          nodeId);
      return Optional.empty();
    }

    if (overwrite) {
      return overwriteCurrentVersion(requester, blobStream, blobLength, nodeId, filename);
    }

    Node node =
        nodeRepository
            .getNode(nodeId)
            .orElseThrow(() -> new NoSuchElementException(NODE_NOT_FOUND + nodeId));

    // P9 CE seam: quota check before the new version blob is stored (no-op in CE).
    quotaChecker.ensureNotOverQuota(node.getOwnerId(), blobLength);

    int maxNumberOfVersions = filesConfig.getMaxNumberOfVersions();
    if (fileVersionRepository.getFileVersions(nodeId, List.of(FileVersionSort.VERSION_DESC)).size()
        > maxNumberOfVersions) {
      throw new MaxNumberOfFileVersionsException(
          String.format(
              "Node %s has reached max number of versions (%d), cannot add more versions",
              nodeId, maxNumberOfVersions));
    }

    MediaType mediaType =
        mimeTypeUtils.detectMimeTypeFromFilename(filename, MediaType.OCTET_STREAM.toString());

    NodeType nodeType = NodeType.getNodeType(mediaType);
    if (nodeType != node.getNodeType()) {
      throw new FileTypeMismatchException(
          String.format(
              "Node %s with wrong type %s, should be the same as old versions: %s",
              nodeId, nodeType, node.getNodeType()));
    }

    int version =
        uploadNewVersion(requesterId, blobStream, blobLength, node, mediaType, maxNumberOfVersions);

    meterRegistry.counter(METRIC_UPLOAD, "service", "files", "uri", "/upload/version").increment();
    return Optional.of(version);
  }

  private int uploadNewVersion(
      String requesterId,
      InputStream blobStream,
      long blobLength,
      Node node,
      MediaType mediaType,
      int maxNumberOfVersions) {
    String nodeId = node.getId();
    String ownerId = node.getOwnerId();
    Reservation reservation =
        versionReservations
            .reserve(nodeId, ownerId)
            .orElseThrow(() -> new NoSuchElementException(NODE_NOT_FOUND + nodeId));
    int version = reservation.version();

    UploadResponse uploadResponse =
        writeVersionBlob(
            nodeId,
            version,
            ownerId,
            () ->
                fileStore.uploadPost(
                    FilesIdentifier.of(nodeId, version, ownerId), blobStream, blobLength));

    List<FileVersion> trimmedVersions =
        QuarkusTransaction.requiringNew()
            .call(
                () -> {
                  long versionDate = versionReservations.confirm(reservation);
                  requireOwner(nodeId, ownerId);
                  fileVersionRepository
                      .createFileVersionAt(
                          nodeId,
                          requesterId,
                          version,
                          mediaType.toString(),
                          uploadResponse.getSize(),
                          uploadResponse.getDigest(),
                          versionDate)
                      .orElseThrow(() -> new NoSuchElementException(NODE_NOT_FOUND + nodeId));
                  nodeRepository.moveCurrentVersion(
                      nodeId, version, versionDate, uploadResponse.getSize(), requesterId);
                  return trimOldestVersion(nodeId, ownerId, version, maxNumberOfVersions);
                });

    deleteTrimmedBlobs(nodeId, ownerId, trimmedVersions);
    logUploaded(nodeId, version, uploadResponse);
    return version;
  }

  /**
   * Rewrites the current version in place, never a new version: one overwrite of a node at a time
   * ({@link OverwriteLeases}), the blob written outside any transaction and never cut, the version
   * rewritten only once the storage answered and only if it still exists (a version deleted
   * meanwhile is never brought back). Once a newer version exists it keeps its date, so the dates
   * follow the numbers.
   */
  private Optional<Integer> overwriteCurrentVersion(
      UserMyself requester,
      InputStream blobStream,
      long blobLength,
      String nodeId,
      String filename) {
    String requesterId = requester.getId().getUserId();
    Node node =
        nodeRepository
            .getNode(nodeId)
            .orElseThrow(() -> new NoSuchElementException(NODE_NOT_FOUND + nodeId));

    // P9 CE seam: quota check before the new version blob is stored (no-op in CE).
    quotaChecker.ensureNotOverQuota(node.getOwnerId(), blobLength);

    MediaType mediaType =
        mimeTypeUtils.detectMimeTypeFromFilename(filename, MediaType.OCTET_STREAM.toString());
    NodeType nodeType = NodeType.getNodeType(mediaType);
    if (nodeType != node.getNodeType()) {
      throw new FileTypeMismatchException(
          String.format(
              "Node %s with wrong type %s, should be the same as old versions: %s",
              nodeId, nodeType, node.getNodeType()));
    }

    Lease lease =
        overwriteLeases
            .take(nodeId)
            .orElseThrow(
                () ->
                    new OverwriteInProgressException(
                        "Another overwrite of node " + nodeId + " is in progress"));
    int version = lease.version();
    String ownerId = node.getOwnerId();
    boolean ended = false;
    try {
      UploadResponse uploadResponse =
          writeVersionBlob(
              nodeId,
              version,
              ownerId,
              () ->
                  fileStore.uploadPut(
                      FilesIdentifier.of(nodeId, version, ownerId), blobStream, blobLength));

      boolean rewritten =
          QuarkusTransaction.requiringNew()
              .call(
                  () -> {
                    // The node row first, as when the lease was taken: no lock-order inversion.
                    nodeRepository
                        .reserveTimestamp(nodeId)
                        .orElseThrow(() -> new NoSuchElementException(NODE_NOT_FOUND + nodeId));
                    if (!overwriteLeases.confirm(lease)) {
                      return false;
                    }
                    FileVersion replaced =
                        fileVersionRepository
                            .getFileVersion(nodeId, version)
                            .orElseThrow(
                                () ->
                                    new NoSuchElementException(
                                        "Version "
                                            + version
                                            + " of node "
                                            + nodeId
                                            + " was deleted while it was overwritten"));
                    long versionDate =
                        fileVersionRepository.existsAbove(nodeId, version)
                            ? replaced.getUpdatedAt()
                            : lease.date();
                    fileVersionRepository.deleteFileVersion(replaced);
                    fileVersionRepository
                        .createFileVersionAt(
                            nodeId,
                            requesterId,
                            version,
                            mediaType.toString(),
                            uploadResponse.getSize(),
                            uploadResponse.getDigest(),
                            versionDate)
                        .orElseThrow(() -> new NoSuchElementException(NODE_NOT_FOUND + nodeId));
                    nodeRepository.moveCurrentVersion(
                        nodeId, version, versionDate, uploadResponse.getSize(), requesterId);
                    return true;
                  });
      ended = true;
      if (rewritten) {
        logUploaded(nodeId, version, uploadResponse);
      } else {
        logger.warn(
            "Overwrite of version {} of node {} outlived its lease: the database is left as it is",
            version,
            nodeId);
      }
      meterRegistry
          .counter(METRIC_UPLOAD, "service", "files", "uri", "/upload/version")
          .increment();
      return Optional.of(version);
    } finally {
      if (!ended) {
        overwriteLeases.release(lease);
      }
    }
  }

  /** A transfer of ownership meanwhile moved the node: its blobs now live under another account. */
  private void requireOwner(String nodeId, String ownerId) {
    if (nodeRepository.getNode(nodeId).filter(n -> ownerId.equals(n.getOwnerId())).isEmpty()) {
      throw new IllegalStateException("Owner of node " + nodeId + " changed during the save");
    }
  }

  private UploadResponse writeVersionBlob(
      String nodeId, int version, String ownerId, Callable<UploadResponse> write) {
    UploadResponse uploadResponse;
    try {
      uploadResponse = write.call();
    } catch (Exception exception) {
      throw new DependencyException(
          String.format(
              "Storages failed: unable to upload node with id %s and version %d", nodeId, version),
          exception);
    }

    if (!verifyBlobExists(nodeId, version, ownerId)) {
      throw new DependencyException(
          String.format(
              "Upload verification failed: blob not accessible for node %s version %d",
              nodeId, version));
    }
    return uploadResponse;
  }

  /**
   * Same rule as before the reservations: past the limit, the oldest version not kept forever goes,
   * never the new version nor the highest (current) one. Runs in the confirming transaction; the
   * blob is deleted after the commit and the tombstone keeps it for the purge if that fails.
   */
  private List<FileVersion> trimOldestVersion(
      String nodeId, String ownerId, int newVersion, int maxNumberOfVersions) {
    List<FileVersion> versions =
        fileVersionRepository.getFileVersions(nodeId, List.of(FileVersionSort.VERSION_DESC));
    if (versions.size() <= maxNumberOfVersions) {
      return List.of();
    }
    int highestVersion = versions.get(0).getVersion();
    List<FileVersion> candidates =
        versions.reversed().stream()
            .filter(fileVersion -> !fileVersion.isKeptForever())
            .filter(fileVersion -> fileVersion.getVersion() != newVersion)
            .filter(fileVersion -> fileVersion.getVersion() != highestVersion)
            .toList();
    for (FileVersion candidate : candidates) {
      if (fileVersionRepository.removeVersionNotKeptForever(nodeId, candidate.getVersion())) {
        tombstoneRepository.createTombstonesBulk(List.of(candidate), ownerId);
        return List.of(candidate);
      }
      // Deleted by a concurrent transaction: trim the next one only if still over the limit.
      if (fileVersionRepository
              .getFileVersions(nodeId, List.of(FileVersionSort.VERSION_DESC))
              .size()
          <= maxNumberOfVersions) {
        return List.of();
      }
    }
    return List.of();
  }

  /** Runs after the commit: never fails the save, a tombstone left behind waits for the purge. */
  private void deleteTrimmedBlobs(String nodeId, String ownerId, List<FileVersion> trimmed) {
    if (trimmed.isEmpty()) {
      return;
    }
    try {
      bulkDeleteTrimmedBlobs(nodeId, ownerId, trimmed);
    } catch (RuntimeException e) {
      logger.warn(
          "Cleanup of the trimmed versions of node {} failed: {}. Tombstones remain.",
          nodeId,
          e.getMessage());
    }
  }

  private void bulkDeleteTrimmedBlobs(String nodeId, String ownerId, List<FileVersion> trimmed) {
    List<BulkDeleteResponseItem> failedItems;
    try {
      failedItems =
          fileStore.bulkDelete(
              IdentifierType.files,
              ownerId,
              trimmed.stream()
                  .map(fv -> BulkDeleteRequestItem.filesItem(fv.getNodeId(), fv.getVersion()))
                  .toList());
    } catch (Exception e) {
      logger.warn(
          "Bulk delete of the trimmed versions of node {} failed: {}. Tombstones remain.",
          nodeId,
          e.getMessage());
      return;
    }
    if (failedItems == null) {
      logger.warn(
          "Bulk delete of the trimmed versions of node {} returned null. Tombstones remain.",
          nodeId);
      return;
    }
    trimmed.stream()
        .filter(
            fv ->
                failedItems.stream()
                    .noneMatch(
                        item ->
                            fv.getNodeId().equals(item.getNode())
                                && item.getVersion().map(v -> v == fv.getVersion()).orElse(true)))
        .forEach(
            fv -> {
              tombstoneRepository.deleteTombstonesByNodeAndVersion(fv.getNodeId(), fv.getVersion());
              logger.info(
                  "File version limit for node {} has been reached, deleted version {}",
                  fv.getNodeId(),
                  fv.getVersion());
            });
  }

  private static void logUploaded(String nodeId, int version, UploadResponse uploadResponse) {
    logger.info(
        "Uploaded file to storages successfully: nodeId {}, version {}, size: {}, digest: {}",
        nodeId,
        version,
        uploadResponse.getSize(),
        uploadResponse.getDigest());
  }

  private Optional<BlobResponse> downloadFile(String nodeId, @Nullable Integer version) {
    logger.info(
        String.format(
            "Start to download node with id %s and version %s",
            nodeId, version == null ? "latest" : version));
    return nodeRepository
        .getNode(nodeId)
        .map(
            node ->
                fileVersionRepository
                    .getFileVersion(
                        node.getId(), version == null ? node.getCurrentVersion() : version)
                    .map(
                        fileVersion ->
                            Try.of(
                                    () ->
                                        fileStore.download(
                                            FilesIdentifier.of(
                                                fileVersion.getNodeId(),
                                                fileVersion.getVersion(),
                                                node.getOwnerId())))
                                .map(
                                    blob ->
                                        new BlobResponse(
                                            blob,
                                            node.getFullName(),
                                            fileVersion.getSize(),
                                            fileVersion.getMimeType()))
                                .getOrElseThrow(
                                    failure -> {
                                      throw new DependencyException(
                                          String.format(
                                              "Storages failed: unable to download node with id %s"
                                                  + " and version %s",
                                              fileVersion.getNodeId(), fileVersion.getVersion()),
                                          failure);
                                    })))
        .orElseGet(
            () -> {
              logger.error("Unable to download node {}: the node id does not exist", nodeId);
              return Optional.empty();
            });
  }

  /**
   * Eagerly walks the node tree (request-context bound) and returns a lightweight ZIP plan: the
   * archive name plus one {@link ZipItem} per folder/file entry (blob identifiers + paths + sizes,
   * NO blob bytes). The actual blob bytes are streamed later, off the request-scoped session, by
   * the REST resource. Port of the legacy {@code createZip}/{@code addNodeToZip} traversal — same
   * unique-name and folder-dedup behaviour, only the sink changed (a plan list instead of a live
   * {@code ZipOutputStream}).
   */
  private Optional<ZipDownload> buildZipPlan(
      List<Node> nodes, Function<Node, Boolean> accessChecker) {
    logger.info("Creating ZIP with {} nodes", nodes.size());

    String zipName = "Files.zip";
    if (nodes.size() == 1) {
      zipName = nodes.get(0).getName() + ".zip";
    }

    List<ZipItem> items = new ArrayList<>();
    Set<String> usedPaths = new HashSet<>();
    for (Node node : nodes) {
      addNodeToPlan(node, "", accessChecker, usedPaths, items);
    }

    return Optional.of(new ZipDownload(zipName, items));
  }

  private void addNodeToPlan(
      Node node,
      String path,
      Function<Node, Boolean> accessChecker,
      Set<String> usedPaths,
      List<ZipItem> items) {
    try {
      if (accessChecker.apply(node)) {
        if (node.getNodeType().equals(NodeType.FOLDER)) {
          addFolderToPlan(node, path, accessChecker, usedPaths, items);
        } else {
          addFileToPlan(node, path, usedPaths, items);
        }
      }
    } catch (Exception e) {
      throw new ZipGenerationException("Error adding node " + node.getId() + " to ZIP", e);
    }
  }

  private void addFolderToPlan(
      Node folder,
      String parentPath,
      Function<Node, Boolean> accessChecker,
      Set<String> usedPaths,
      List<ZipItem> items) {
    String folderPath =
        getUniqueNameForZip(
            parentPath.isEmpty() ? folder.getFullName() : parentPath + "/" + folder.getFullName(),
            usedPaths,
            true);

    items.add(ZipItem.folder(folderPath));

    List<String> childrenIds =
        nodeRepository.getChildrenIds(folder.getId(), Optional.empty(), Optional.empty(), false);

    for (String childId : childrenIds) {
      Node childNode =
          nodeRepository
              .getNode(childId)
              .orElseThrow(() -> new ZipGenerationException("Child node not found: " + childId));

      addNodeToPlan(childNode, folderPath, accessChecker, usedPaths, items);
    }
  }

  private void addFileToPlan(
      Node node, String parentPath, Set<String> usedPaths, List<ZipItem> items) {
    FileVersion fileVersion = fileVersionRepository.getLastFileVersion(node.getId()).get();

    String filePath =
        getUniqueNameForZip(
            parentPath.isEmpty() ? node.getFullName() : parentPath + "/" + node.getFullName(),
            usedPaths,
            false);

    items.add(
        ZipItem.file(
            filePath,
            fileVersion.getNodeId(),
            fileVersion.getVersion(),
            node.getOwnerId(),
            fileVersion.getSize()));
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

  /**
   * Opens a blob stream for a planned ZIP file entry. Called by the REST resource while streaming
   * the archive; needs only the (request-context free) {@link Filestore}.
   */
  public InputStream openZipEntryStream(ZipItem item) throws Exception {
    return fileStore.download(
        FilesIdentifier.of(item.getNodeId(), item.getVersion(), item.getOwnerId()));
  }

  // It seems that manual checking is sometimes necessary
  private boolean verifyBlobExists(String nodeId, int version, String nodeOwner) {
    try {
      InputStream blobStream = fileStore.download(FilesIdentifier.of(nodeId, version, nodeOwner));
      blobStream.close();
      return true;
    } catch (Exception e) {
      return false;
    }
  }

  /** Lightweight, fully-resolved plan of a ZIP multi-download (no blob bytes buffered). */
  public static final class ZipDownload {

    private final String filename;
    private final List<ZipItem> items;

    public ZipDownload(String filename, List<ZipItem> items) {
      this.filename = filename;
      this.items = items;
    }

    public String getFilename() {
      return filename;
    }

    public List<ZipItem> getItems() {
      return items;
    }
  }

  /** A single planned entry of a ZIP archive: either a folder marker or a file blob reference. */
  public static final class ZipItem {

    private final String path;
    private final boolean folder;
    private final String nodeId;
    private final int version;
    private final String ownerId;
    private final long size;

    private ZipItem(
        String path, boolean folder, String nodeId, int version, String ownerId, long size) {
      this.path = path;
      this.folder = folder;
      this.nodeId = nodeId;
      this.version = version;
      this.ownerId = ownerId;
      this.size = size;
    }

    static ZipItem folder(String path) {
      return new ZipItem(path, true, null, 0, null, 0L);
    }

    static ZipItem file(String path, String nodeId, int version, String ownerId, long size) {
      return new ZipItem(path, false, nodeId, version, ownerId, size);
    }

    public String getPath() {
      return path;
    }

    public boolean isFolder() {
      return folder;
    }

    public String getNodeId() {
      return nodeId;
    }

    public int getVersion() {
      return version;
    }

    public String getOwnerId() {
      return ownerId;
    }

    public long getSize() {
      return size;
    }
  }
}
