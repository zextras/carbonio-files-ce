// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.rest;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.notNullValue;

import com.zextras.carbonio.files.FilesStackTestResource;
import com.zextras.carbonio.files.dal.dao.ebean.Node;
import com.zextras.carbonio.files.dal.dao.ebean.NodeType;
import com.zextras.carbonio.files.dal.repositories.interfaces.FileVersionRepository;
import com.zextras.carbonio.files.dal.repositories.interfaces.LinkRepository;
import com.zextras.carbonio.files.dal.repositories.interfaces.NodeRepository;
import com.zextras.filestore.api.Filestore;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * End-to-end integration test for the P4a blob REST core on Quarkus/RESTEasy Reactive: it proves
 * streaming upload and download work through the full stack (auth filter -&gt; resource -&gt;
 * {@link com.zextras.carbonio.files.rest.services.BlobService} -&gt; Panache DAL + the in-memory
 * {@link InMemoryFilestore} fake), that a new version can be uploaded, that a multi-download returns
 * a valid ZIP, and that permission and public-link paths behave.
 *
 * <p>Uses {@code @QuarkusTest} + {@link FilesStackTestResource} (real Postgres testcontainer, Consul
 * WireMock, in-process user-management gRPC stub). Prerequisite folders are seeded in-JVM via the
 * injected repositories; fresh UUIDs per test keep rows from colliding with the shared Postgres.
 */
@QuarkusTest
@QuarkusTestResource(FilesStackTestResource.class)
class BlobResourceIT {

  @Inject NodeRepository nodeRepository;
  @Inject FileVersionRepository fileVersionRepository;
  @Inject LinkRepository linkRepository;
  @Inject Filestore filestore;

  private String authFolderId;
  private String otherFolderId;

  @BeforeEach
  void seed() {
    // A folder OWNED by the stubbed test user: uploads target it (OWNER => READ_AND_WRITE).
    authFolderId = UUID.randomUUID().toString();
    nodeRepository.createNewNode(
        authFolderId,
        FilesStackTestResource.TEST_USER_ID,
        FilesStackTestResource.TEST_USER_ID,
        "LOCAL_ROOT",
        "auth-folder-" + authFolderId,
        "desc",
        NodeType.FOLDER,
        "LOCAL_ROOT",
        0L);

    // A folder owned by ANOTHER user and NOT shared: the test user has no permission on it.
    otherFolderId = UUID.randomUUID().toString();
    nodeRepository.createNewNode(
        otherFolderId,
        "other-owner",
        "other-owner",
        "LOCAL_ROOT",
        "other-folder-" + otherFolderId,
        "desc",
        NodeType.FOLDER,
        "LOCAL_ROOT",
        0L);
  }

  // ----------------------------------------------------------------------- (a) upload + download

  @Test
  void uploadThenDownloadRoundTrips() {
    byte[] content = "hello streaming world".getBytes(StandardCharsets.UTF_8);
    String nodeId = upload(authFolderId, "hello.txt", content);

    // DB row assertions (Node + FileVersion) — read inside a transaction for an active session.
    Node node =
        QuarkusTransaction.requiringNew().call(() -> nodeRepository.getNode(nodeId).orElse(null));
    assertThat(node).isNotNull();
    assertThat(node.getSize()).isEqualTo((long) content.length);
    assertThat(node.getCurrentVersion()).isEqualTo(1);

    boolean fileVersionExists =
        QuarkusTransaction.requiringNew()
            .call(() -> fileVersionRepository.getFileVersion(nodeId, 1).isPresent());
    assertThat(fileVersionExists).isTrue();

    // Blob physically stored in the fake filestore.
    assertThat(((InMemoryFilestore) filestore).has(nodeId, 1)).isTrue();

    // Download and compare bytes.
    byte[] downloaded =
        given()
            .cookie("ZM_AUTH_TOKEN", FilesStackTestResource.AUTH_TOKEN)
            .when()
            .get("/download/" + nodeId)
            .then()
            .statusCode(200)
            .extract()
            .asByteArray();
    assertThat(downloaded).isEqualTo(content);
  }

  // ------------------------------------------------------------------------------ (b) upload-version

  @Test
  void uploadVersionCreatesSecondVersion() {
    byte[] v1 = "version one".getBytes(StandardCharsets.UTF_8);
    byte[] v2 = "version two is longer".getBytes(StandardCharsets.UTF_8);
    String nodeId = upload(authFolderId, "doc.txt", v1);

    int version =
        given()
            .cookie("ZM_AUTH_TOKEN", FilesStackTestResource.AUTH_TOKEN)
            .header("NodeId", nodeId)
            .header("Filename", base64("doc.txt"))
            .contentType("application/octet-stream")
            .body(v2)
            .when()
            .post("/upload-version")
            .then()
            .statusCode(200)
            .body("nodeId", notNullValue())
            .extract()
            .path("version");
    assertThat(version).isEqualTo(2);

    assertThat(((InMemoryFilestore) filestore).has(nodeId, 2)).isTrue();

    // Latest download returns v2.
    byte[] downloaded =
        given()
            .cookie("ZM_AUTH_TOKEN", FilesStackTestResource.AUTH_TOKEN)
            .when()
            .get("/download/" + nodeId)
            .then()
            .statusCode(200)
            .extract()
            .asByteArray();
    assertThat(downloaded).isEqualTo(v2);

    // Explicit version 1 still returns v1.
    byte[] firstVersion =
        given()
            .cookie("ZM_AUTH_TOKEN", FilesStackTestResource.AUTH_TOKEN)
            .when()
            .get("/download/" + nodeId + "/1")
            .then()
            .statusCode(200)
            .extract()
            .asByteArray();
    assertThat(firstVersion).isEqualTo(v1);
  }

  // ------------------------------------------------------------------------- (c) download-multiple

  @Test
  void downloadMultipleReturnsValidZip() throws Exception {
    byte[] alpha = "AAAA".getBytes(StandardCharsets.UTF_8);
    byte[] beta = "BBBBBB".getBytes(StandardCharsets.UTF_8);
    String alphaId = upload(authFolderId, "alpha.txt", alpha);
    String betaId = upload(authFolderId, "beta.txt", beta);

    byte[] zipBytes =
        given()
            .cookie("ZM_AUTH_TOKEN", FilesStackTestResource.AUTH_TOKEN)
            .contentType("application/x-www-form-urlencoded")
            .formParam("nodeIds", "[\"" + alphaId + "\",\"" + betaId + "\"]")
            .when()
            .post("/download-multiple")
            .then()
            .statusCode(200)
            .extract()
            .asByteArray();

    Map<String, byte[]> entries = unzip(zipBytes);
    assertThat(entries).containsKeys("alpha.txt", "beta.txt");
    assertThat(entries.get("alpha.txt")).isEqualTo(alpha);
    assertThat(entries.get("beta.txt")).isEqualTo(beta);
  }

  // ------------------------------------------------------------- (d) permission collapses to 404

  // Legacy parity (pinned by the acceptance suite, e.g. AuthenticatedDownloadApiIT): the legacy
  // Netty ExceptionsHandler collapsed BOTH "node not found" and "requester lacks permission" into
  // the same 404, so a permission denial must NOT surface as a "more precise" 403.
  @Test
  void downloadOfNonPermittedNodeReturns404() {
    given()
        .cookie("ZM_AUTH_TOKEN", FilesStackTestResource.AUTH_TOKEN)
        .when()
        .get("/download/" + otherFolderId)
        .then()
        .statusCode(404);
  }

  @Test
  void downloadWithoutCookieReturns401() {
    given().when().get("/download/" + authFolderId).then().statusCode(401);
  }

  // ------------------------------------------------------------------------------ (e) public download

  @Test
  void publicDownloadViaLinkRoundTrips() {
    byte[] content = "public content".getBytes(StandardCharsets.UTF_8);
    String nodeId = upload(authFolderId, "shared.txt", content);

    String publicId = UUID.randomUUID().toString().replace("-", "");
    linkRepository.createLink(
        UUID.randomUUID().toString(),
        nodeId,
        publicId,
        Optional.empty(),
        Optional.empty(),
        Optional.empty());

    byte[] downloaded =
        given()
            .when()
            .get("/public/download/" + nodeId + "?node_link_id=" + publicId)
            .then()
            .statusCode(200)
            .extract()
            .asByteArray();
    assertThat(downloaded).isEqualTo(content);
  }

  // --------------------------------------------------------------------------------------- helpers

  /** Uploads {@code content} to {@code folderId} as {@code filename} for the stubbed test user. */
  private String upload(String folderId, String filename, byte[] content) {
    return given()
        .cookie("ZM_AUTH_TOKEN", FilesStackTestResource.AUTH_TOKEN)
        .header("ParentId", folderId)
        .header("Filename", base64(filename))
        .contentType("application/octet-stream")
        .body(content)
        .when()
        .post("/upload")
        .then()
        .statusCode(200)
        .body("nodeId", notNullValue())
        .extract()
        .path("nodeId");
  }

  private static String base64(String value) {
    return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
  }

  private static Map<String, byte[]> unzip(byte[] zipBytes) throws Exception {
    Map<String, byte[]> entries = new HashMap<>();
    try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
      ZipEntry entry;
      while ((entry = zis.getNextEntry()) != null) {
        entries.put(entry.getName(), zis.readAllBytes());
      }
    }
    return entries;
  }
}
