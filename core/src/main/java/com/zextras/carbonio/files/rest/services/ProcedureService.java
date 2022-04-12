// SPDX-FileCopyrightText: 2022 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.rest.services;

import com.google.inject.Inject;
import com.zextras.carbonio.files.Files.Config.Mailbox;
import com.zextras.carbonio.files.Files.Config.Storages;
import com.zextras.carbonio.files.clients.MailboxHttpClient;
import com.zextras.carbonio.files.config.FilesConfig;
import com.zextras.carbonio.files.dal.dao.User;
import com.zextras.carbonio.files.dal.dao.ebean.FileVersion;
import com.zextras.carbonio.files.dal.dao.ebean.Node;
import com.zextras.carbonio.files.dal.dao.ebean.NodeType;
import com.zextras.carbonio.files.dal.repositories.interfaces.FileVersionRepository;
import com.zextras.carbonio.files.dal.repositories.interfaces.NodeRepository;
import com.zextras.carbonio.files.exceptions.BadRequestException;
import com.zextras.carbonio.files.rest.types.UploadToRequest.TargetModule;
import com.zextras.filestore.model.FilesIdentifier;
import com.zextras.storages.api.StoragesClient;
import io.vavr.control.Try;
import java.io.InputStream;
import java.util.Properties;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handles all the procedures to perform on external services.
 */
public class ProcedureService {

  private final static Logger logger = LoggerFactory.getLogger(ProcedureService.class);

  private final NodeRepository        nodeRepository;
  private final FileVersionRepository fileVersionRepository;
  private final String mailboxUrl;
  private final String storagesUrl;

  @Inject
  public ProcedureService(
    NodeRepository nodeRepository,
    FileVersionRepository fileVersionRepository,
    FilesConfig filesConfig
  ) {
    this.nodeRepository = nodeRepository;
    this.fileVersionRepository = fileVersionRepository;

    Properties properties = filesConfig.getProperties();
    mailboxUrl = "http"
      + properties.getProperty(Mailbox.URL, "127.78.0.2")
      + properties.getProperty(Mailbox.PORT, "20004");
    storagesUrl = "http"
      + properties.getProperty(Storages.URL, "127.78.0.2")
      + properties.getProperty(Storages.PORT, "20002")
      + "/";
  }

  /**
   * Allows to upload the {@param nodeId} to the carbonio mailbox store. These are the target module
   * supported:
   * <ul>
   *   <li>{@link TargetModule#MAILS}</li>
   *   <li>{@link TargetModule#CALENDARS}</li>
   *   <li>{@link TargetModule#CONTACTS}</li>
   * </ul>
   *
   * @param nodeId is a {@link String} of the node id to upload.
   * @param targetModule is a {@link TargetModule} representing the module to upload.
   * @param requester is a {@link User} representing the requester of this operation.
   * @param cookiesRequester is a {@link String} representing the requester cookies necessary to
   * perform the upload operation.
   *
   * @return a {@link Try} containing a {@link String} representing the mailbox attachment id
   * uploaded or a {@link Throwable} if something goes wrong. These are the possible failures:
   * <ul>
   *   <li>{@link BadRequestException} when the node is not a file</li>
   *   <li>{@link Exception} when the download of the blob to upload fails</li>
   *   <li>{@link Exception} when the {@link TargetModule} is {@link TargetModule#CHATS} because,
   *   for now, it is not supported</li>
   * </ul>
   */
  public Try<String> uploadToModule(
    UUID nodeId,
    TargetModule targetModule,
    User requester,
    String cookiesRequester
  ) {

    if (!targetModule.equals(TargetModule.CHATS)) {
      Node nodeToUpload = nodeRepository.getNode(nodeId.toString()).get();

      if (!nodeToUpload.getNodeType().equals(NodeType.FOLDER)) {
        FileVersion fileVersion = fileVersionRepository.getLastFileVersion(nodeId.toString()).get();
        InputStream blob;
        try {
          blob = StoragesClient
            .atUrl(storagesUrl)
            .download(FilesIdentifier.of(
              nodeId.toString(),
              nodeToUpload.getCurrentVersion(),
              requester.getUuid())
            );
        } catch (Exception exception) {
          logger.error("Failed to download the node: " + nodeId);
          return Try.failure(exception);
        }

        return MailboxHttpClient
          .atURL(mailboxUrl)
          .uploadFile(
            cookiesRequester,
            nodeToUpload.getFullName(),
            fileVersion.getMimeType(),
            blob
          );
      }
      logger.error("Folder cannot be uploaded to: " + targetModule + " store");
      return Try.failure(new BadRequestException());
    }
    return Try.failure(new Exception(TargetModule.CHATS + " not supported"));
  }
}
