// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.rest.services;

import com.zextras.carbonio.files.clients.MailboxHttpClient;
import com.zextras.carbonio.files.dal.dao.UserMyself;
import com.zextras.carbonio.files.dal.dao.ebean.FileVersion;
import com.zextras.carbonio.files.dal.dao.ebean.Node;
import com.zextras.carbonio.files.dal.dao.ebean.NodeType;
import com.zextras.carbonio.files.dal.repositories.interfaces.FileVersionRepository;
import com.zextras.carbonio.files.dal.repositories.interfaces.NodeRepository;
import com.zextras.carbonio.files.exceptions.DependencyException;
import com.zextras.carbonio.files.rest.types.UploadToRequest.TargetModule;
import com.zextras.filestore.api.Filestore;
import com.zextras.filestore.model.FilesIdentifier;
import io.vavr.control.Try;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.InputStream;
import java.text.MessageFormat;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handles all the procedures to perform on external services. Quarkus/CDI port of the legacy
 * Guice {@code ProcedureService}: logic preserved 1:1, only the transport/DI details change
 * ({@link MailboxHttpClient} is now a CDI bean built on the JDK http client instead of Apache's).
 */
@ApplicationScoped
public class ProcedureService {

  private static final Logger logger = LoggerFactory.getLogger(ProcedureService.class);

  private final NodeRepository nodeRepository;
  private final FileVersionRepository fileVersionRepository;
  private final MailboxHttpClient mailboxHttpClient;
  private final Filestore fileStoreClient;

  @Inject
  public ProcedureService(
      NodeRepository nodeRepository,
      FileVersionRepository fileVersionRepository,
      Filestore fileStoreClient,
      MailboxHttpClient mailboxHttpClient) {
    this.nodeRepository = nodeRepository;
    this.fileVersionRepository = fileVersionRepository;
    this.fileStoreClient = fileStoreClient;
    this.mailboxHttpClient = mailboxHttpClient;
  }

  /**
   * Uploads {@code nodeId}'s blob to the carbonio mailbox store. Supported target modules: {@link
   * TargetModule#MAILS}, {@link TargetModule#CALENDARS}, {@link TargetModule#CONTACTS}.
   *
   * @return a {@link Try} containing the mailbox attachment id uploaded, or a failure:
   *     <ul>
   *       <li>{@link IllegalArgumentException} when the node is a folder (→ 400, matching the
   *           legacy {@code BadRequestException})
   *       <li>{@link DependencyException} when the blob download from storages fails (→ 500)
   *       <li>whatever {@link MailboxHttpClient#uploadFile} returns on a mailbox-side failure
   *     </ul>
   */
  public Try<String> uploadToModule(
      UUID nodeId, TargetModule targetModule, UserMyself requester, String cookiesRequester) {

    Node nodeToUpload =
        nodeRepository
            .getNode(nodeId.toString())
            .orElseThrow(() -> new NoSuchElementException("Node " + nodeId + " does not exist"));

    if (!nodeToUpload.getNodeType().equals(NodeType.FOLDER)) {
      FileVersion fileVersion =
          fileVersionRepository
              .getLastFileVersion(nodeId.toString())
              .orElseThrow(() -> new NoSuchElementException("No version found for node " + nodeId));
      InputStream blob;
      try {
        blob =
            fileStoreClient.download(
                FilesIdentifier.of(
                    nodeId.toString(),
                    nodeToUpload.getCurrentVersion(),
                    requester.getId().getUserId()));
      } catch (Exception exception) {
        logger.error(MessageFormat.format("Failed to download the node: {0}", nodeId));
        return Try.failure(
            new DependencyException("Failed to download the node: " + nodeId, exception));
      }

      return mailboxHttpClient.uploadFile(
          cookiesRequester,
          nodeToUpload.getFullName(),
          fileVersion.getMimeType(),
          blob,
          fileVersion.getSize());
    }
    logger.error(MessageFormat.format("Folder cannot be uploaded to {0} store", targetModule));
    return Try.failure(
        new IllegalArgumentException("Folder cannot be uploaded to " + targetModule + " store"));
  }
}
