// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.rest;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import com.zextras.carbonio.files.FilesStackTestResource;
import com.zextras.carbonio.files.dal.dao.ebean.NodeType;
import com.zextras.carbonio.files.dal.repositories.interfaces.FileVersionRepository;
import com.zextras.carbonio.files.dal.repositories.interfaces.NodeRepository;
import com.zextras.filestore.api.Filestore;
import com.zextras.filestore.model.FilesIdentifier;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * End-to-end integration test for the P5b {@code /upload-to} procedure endpoint: {@code
 * com.zextras.carbonio.files.rest.resources.ProcedureResource} + {@code ProcedureService} +
 * {@code MailboxHttpClient} against a WireMock-stubbed carbonio-mailbox (see {@code
 * FilesStackTestResource#setupPreviewAndMailboxStubs}, which serves a canned success CSV response
 * for any {@code POST service/upload}). Real Postgres + the app's REAL {@code Filestore}/{@code
 * StoragesClient} talking real HTTP to the {@link
 * com.zextras.carbonio.files.it.support.MockStoragesService} fake, no real carbonio-mailbox.
 *
 * <p>Status-code assertions match the legacy Netty {@code ProcedureController} semantics: a folder
 * target is 400; node-not-found and no-permission both collapse to 404.
 */
@QuarkusTest
@QuarkusTestResource(FilesStackTestResource.class)
class ProcedureResourceIT {

  @Inject NodeRepository nodeRepository;
  @Inject FileVersionRepository fileVersionRepository;
  @Inject Filestore filestore;

  private String fileNodeId;
  private String folderNodeId;
  private String otherOwnerNodeId;

  @BeforeEach
  void seed() throws Exception {
    byte[] content = "hello mailbox".getBytes(StandardCharsets.UTF_8);

    // A file OWNED by the stubbed test user, with a blob in the fake Filestore.
    fileNodeId = UUID.randomUUID().toString();
    nodeRepository.createNewNode(
        fileNodeId,
        FilesStackTestResource.TEST_USER_ID,
        FilesStackTestResource.TEST_USER_ID,
        "LOCAL_ROOT",
        "doc-" + fileNodeId + ".txt",
        "desc",
        NodeType.TEXT,
        "LOCAL_ROOT",
        (long) content.length);
    fileVersionRepository.createNewFileVersion(
        fileNodeId,
        FilesStackTestResource.TEST_USER_ID,
        1,
        "text/plain",
        content.length,
        "digest-" + fileNodeId,
        false);
    filestore.uploadPost(
        FilesIdentifier.of(fileNodeId, 1, FilesStackTestResource.TEST_USER_ID),
        new ByteArrayInputStream(content),
        content.length);

    // A folder OWNED by the stubbed test user: uploading it must be rejected (400).
    folderNodeId = UUID.randomUUID().toString();
    nodeRepository.createNewNode(
        folderNodeId,
        FilesStackTestResource.TEST_USER_ID,
        FilesStackTestResource.TEST_USER_ID,
        "LOCAL_ROOT",
        "folder-" + folderNodeId,
        "desc",
        NodeType.FOLDER,
        "LOCAL_ROOT",
        0L);

    // A file owned by ANOTHER user and NOT shared: the test user has no permission on it.
    otherOwnerNodeId = UUID.randomUUID().toString();
    nodeRepository.createNewNode(
        otherOwnerNodeId,
        "other-owner",
        "other-owner",
        "LOCAL_ROOT",
        "other-" + otherOwnerNodeId + ".txt",
        "desc",
        NodeType.TEXT,
        "LOCAL_ROOT",
        0L);
    fileVersionRepository.createNewFileVersion(
        otherOwnerNodeId, "other-owner", 1, "text/plain", 0L, "digest-other", false);
  }

  @Test
  void uploadToMailsSucceeds() {
    String attachmentId =
        given()
            .cookie("ZM_AUTH_TOKEN", FilesStackTestResource.AUTH_TOKEN)
            .contentType("application/json")
            .body(uploadToBody(fileNodeId, "MAILS"))
            .when()
            .post("/upload-to")
            .then()
            .statusCode(200)
            .extract()
            .path("attachmentId");
    assertThat(attachmentId).isEqualTo("fallback-attachment-id");
  }

  @Test
  void uploadOfFolderReturns400() {
    given()
        .cookie("ZM_AUTH_TOKEN", FilesStackTestResource.AUTH_TOKEN)
        .contentType("application/json")
        .body(uploadToBody(folderNodeId, "MAILS"))
        .when()
        .post("/upload-to")
        .then()
        .statusCode(400);
  }

  @Test
  void uploadOfNonPermittedNodeReturns404() {
    given()
        .cookie("ZM_AUTH_TOKEN", FilesStackTestResource.AUTH_TOKEN)
        .contentType("application/json")
        .body(uploadToBody(otherOwnerNodeId, "MAILS"))
        .when()
        .post("/upload-to")
        .then()
        .statusCode(404);
  }

  @Test
  void uploadOfMissingNodeReturns404() {
    given()
        .cookie("ZM_AUTH_TOKEN", FilesStackTestResource.AUTH_TOKEN)
        .contentType("application/json")
        .body(uploadToBody(UUID.randomUUID().toString(), "MAILS"))
        .when()
        .post("/upload-to")
        .then()
        .statusCode(404);
  }

  @Test
  void uploadWithoutCookieReturns401() {
    given()
        .contentType("application/json")
        .body(uploadToBody(fileNodeId, "MAILS"))
        .when()
        .post("/upload-to")
        .then()
        .statusCode(401);
  }

  private static String uploadToBody(String nodeId, String targetModule) {
    return "{\"nodeId\":\"" + nodeId + "\",\"targetModule\":\"" + targetModule + "\"}";
  }
}
