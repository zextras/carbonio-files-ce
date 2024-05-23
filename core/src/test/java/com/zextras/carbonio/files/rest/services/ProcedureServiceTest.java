// SPDX-FileCopyrightText: 2024 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.rest.services;

import com.zextras.carbonio.files.clients.MailboxHttpClient;
import com.zextras.carbonio.files.dal.dao.User;
import com.zextras.carbonio.files.dal.dao.ebean.FileVersion;
import com.zextras.carbonio.files.dal.dao.ebean.Node;
import com.zextras.carbonio.files.dal.dao.ebean.NodeType;
import com.zextras.carbonio.files.dal.repositories.interfaces.FileVersionRepository;
import com.zextras.carbonio.files.dal.repositories.interfaces.NodeRepository;
import com.zextras.carbonio.files.exceptions.BadRequestException;
import com.zextras.carbonio.files.exceptions.InternalServerErrorException;
import com.zextras.carbonio.files.rest.types.UploadToRequest.TargetModule;
import com.zextras.carbonio.usermanagement.enumerations.Status;
import com.zextras.carbonio.usermanagement.enumerations.UserType;
import com.zextras.filestore.api.Filestore;
import com.zextras.filestore.model.FilesIdentifier;
import io.vavr.control.Try;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;
import org.apache.commons.io.IOUtils;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class ProcedureServiceTest {

  private NodeRepository nodeRepositoryMock;
  private FileVersionRepository fileVersionRepositoryMock;
  private Filestore fileStoreClientMock;
  private MailboxHttpClient mailboxHttpClientMock;
  private ProcedureService procedureService;

  @BeforeEach
  void setUp() {
    nodeRepositoryMock = Mockito.mock(NodeRepository.class);
    fileVersionRepositoryMock = Mockito.mock(FileVersionRepository.class);
    fileStoreClientMock = Mockito.mock(Filestore.class);
    mailboxHttpClientMock = Mockito.mock(MailboxHttpClient.class);

    procedureService =
        new ProcedureService(
            nodeRepositoryMock,
            fileVersionRepositoryMock,
            fileStoreClientMock,
            mailboxHttpClientMock);
  }

  @Test
  void
      givenADownloadableFileAndTheMailsTargetModuleTheUploadToShouldReturnTheAttachmentIdOfTheFileUploadedToMails()
          throws Exception {
    // Given
    final User requester =
        new User(
            "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa", "", "", "", Status.ACTIVE, UserType.INTERNAL);

    final Node nodeMock = Mockito.mock(Node.class);
    Mockito.when(nodeMock.getNodeType()).thenReturn(NodeType.TEXT);
    Mockito.when(nodeMock.getFullName()).thenReturn("test.txt");
    Mockito.when(nodeMock.getCurrentVersion()).thenReturn(2);

    final FileVersion fileVersionMock = Mockito.mock(FileVersion.class);
    Mockito.when(fileVersionMock.getMimeType()).thenReturn("text/plain");
    Mockito.when(fileVersionMock.getSize()).thenReturn(5L);

    final InputStream blob = IOUtils.toInputStream("content", StandardCharsets.UTF_8);

    Mockito.when(nodeRepositoryMock.getNode("00000000-0000-0000-0000-000000000000"))
        .thenReturn(Optional.of(nodeMock));

    Mockito.when(
            fileVersionRepositoryMock.getLastFileVersion("00000000-0000-0000-0000-000000000000"))
        .thenReturn(Optional.of(fileVersionMock));

    Mockito.when(
            fileStoreClientMock.download(
                FilesIdentifier.of(
                    "00000000-0000-0000-0000-000000000000",
                    2,
                    "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")))
        .thenReturn(blob);

    Mockito.when(
            mailboxHttpClientMock.uploadFile("fake-cookie", "test.txt", "text/plain", blob, 5L))
        .thenReturn(Try.success("fake-attachmentId"));

    // When
    final Try<String> tryAttachmentId =
        procedureService.uploadToModule(
            UUID.fromString("00000000-0000-0000-0000-000000000000"),
            TargetModule.MAILS,
            requester,
            "fake-cookie");

    // Then
    Assertions.assertThat(tryAttachmentId.isSuccess()).isTrue();
    Assertions.assertThat(tryAttachmentId.get()).isEqualTo("fake-attachmentId");
  }

  @Test
  void givenANotDownloadableFileTheUploadToShouldReturnATryFailure() throws Exception {
    // Given
    final User requester =
        new User(
            "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa", "", "", "", Status.ACTIVE, UserType.INTERNAL);

    final Node nodeMock = Mockito.mock(Node.class);
    Mockito.when(nodeMock.getNodeType()).thenReturn(NodeType.TEXT);
    Mockito.when(nodeMock.getCurrentVersion()).thenReturn(2);

    final FileVersion fileVersionMock = Mockito.mock(FileVersion.class);
    Mockito.when(fileVersionMock.getSize()).thenReturn(5L);

    Mockito.when(nodeRepositoryMock.getNode("00000000-0000-0000-0000-000000000000"))
        .thenReturn(Optional.of(nodeMock));

    Mockito.when(
            fileVersionRepositoryMock.getLastFileVersion("00000000-0000-0000-0000-000000000000"))
        .thenReturn(Optional.of(fileVersionMock));

    Mockito.when(
            fileStoreClientMock.download(
                FilesIdentifier.of(
                    "00000000-0000-0000-0000-000000000000",
                    2,
                    "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")))
        .thenThrow(Exception.class);

    // When
    Try<String> tryAttachmentId =
        procedureService.uploadToModule(
            UUID.fromString("00000000-0000-0000-0000-000000000000"),
            TargetModule.MAILS,
            requester,
            "fake-cookie");

    // Then
    Assertions.assertThat(tryAttachmentId.isFailure()).isTrue();
    Assertions.assertThatThrownBy(() -> tryAttachmentId.get())
        .isInstanceOf(InternalServerErrorException.class);

    Mockito.verifyNoInteractions(mailboxHttpClientMock);
  }

  @Test
  void givenADownloadableFileAndAnUnreachableMailboxTheUploadToShouldReturnATryFailure()
      throws Exception {
    // Given
    final User requester =
        new User(
            "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa", "", "", "", Status.ACTIVE, UserType.INTERNAL);

    final Node nodeMock = Mockito.mock(Node.class);
    Mockito.when(nodeMock.getNodeType()).thenReturn(NodeType.TEXT);
    Mockito.when(nodeMock.getFullName()).thenReturn("test.txt");
    Mockito.when(nodeMock.getCurrentVersion()).thenReturn(2);

    final FileVersion fileVersionMock = Mockito.mock(FileVersion.class);
    Mockito.when(fileVersionMock.getMimeType()).thenReturn("text/plain");
    Mockito.when(fileVersionMock.getSize()).thenReturn(5L);

    final InputStream blob = IOUtils.toInputStream("content", StandardCharsets.UTF_8);

    Mockito.when(nodeRepositoryMock.getNode("00000000-0000-0000-0000-000000000000"))
        .thenReturn(Optional.of(nodeMock));

    Mockito.when(
            fileVersionRepositoryMock.getLastFileVersion("00000000-0000-0000-0000-000000000000"))
        .thenReturn(Optional.of(fileVersionMock));

    Mockito.when(
            fileStoreClientMock.download(
                FilesIdentifier.of(
                    "00000000-0000-0000-0000-000000000000",
                    2,
                    "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")))
        .thenReturn(blob);

    Mockito.when(
            mailboxHttpClientMock.uploadFile("fake-cookie", "test.txt", "text/plain", blob, 5L))
        .thenReturn(Try.failure(new InternalServerErrorException(new Exception())));

    // When
    final Try<String> tryAttachmentId =
        procedureService.uploadToModule(
            UUID.fromString("00000000-0000-0000-0000-000000000000"),
            TargetModule.MAILS,
            requester,
            "fake-cookie");

    // Then
    Assertions.assertThat(tryAttachmentId.isFailure()).isTrue();
    Assertions.assertThatThrownBy(() -> tryAttachmentId.get())
        .isInstanceOf(InternalServerErrorException.class);
  }

  @Test
  void givenTheChatTargetModuleTheUploadToShouldReturnATryFailureBecauseNotSupported() {
    // Given & When
    final Try<String> tryAttachmentId =
        procedureService.uploadToModule(
            UUID.fromString("00000000-0000-0000-0000-000000000000"),
            TargetModule.CHATS,
            Mockito.mock(User.class),
            "fake-cookie");

    // Then
    Assertions.assertThat(tryAttachmentId.isFailure()).isTrue();
    Assertions.assertThatThrownBy(() -> tryAttachmentId.get())
        .isInstanceOf(InternalServerErrorException.class)
        .hasMessage("CHATS not supported");

    Mockito.verifyNoInteractions(nodeRepositoryMock);
    Mockito.verifyNoInteractions(fileVersionRepositoryMock);
    Mockito.verifyNoInteractions(fileStoreClientMock);
    Mockito.verifyNoInteractions(mailboxHttpClientMock);
  }

  @Test
  void
      givenAFolderAndTheMailsTargetModuleTheUploadToShouldReturnATryFailureBecauseAFolderCannotBeUploaded() {
    // Given
    final Node nodeMock = Mockito.mock(Node.class);
    Mockito.when(nodeMock.getNodeType()).thenReturn(NodeType.FOLDER);

    Mockito.when(nodeRepositoryMock.getNode("00000000-0000-0000-0000-000000000000"))
        .thenReturn(Optional.of(nodeMock));

    // When
    final Try<String> tryAttachmentId =
        procedureService.uploadToModule(
            UUID.fromString("00000000-0000-0000-0000-000000000000"),
            TargetModule.MAILS,
            Mockito.mock(User.class),
            "fake-cookie");

    // Then
    Assertions.assertThat(tryAttachmentId.isFailure()).isTrue();
    Assertions.assertThatThrownBy(() -> tryAttachmentId.get())
        .isInstanceOf(BadRequestException.class);

    Mockito.verifyNoInteractions(fileVersionRepositoryMock);
    Mockito.verifyNoInteractions(fileStoreClientMock);
    Mockito.verifyNoInteractions(mailboxHttpClientMock);
  }
}
