// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.it;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zextras.carbonio.files.FilesStackTestResource;
import com.zextras.carbonio.files.it.support.AbstractFilesIT;
import com.zextras.carbonio.files.rest.types.UploadToRequest;
import com.zextras.carbonio.files.rest.types.UploadToRequest.TargetModule;
import io.restassured.RestAssured;
import io.restassured.response.Response;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * {@code com.zextras.carbonio.files.acceptance.UploadToApiIT} rewritten as an out-of-process
 * {@code @QuarkusIntegrationTest} on {@link AbstractFilesIT}: {@code POST /upload-to} ({@code
 * ProcedureController}/{@code ProcedureService}), the "attach node to mailbox" route. All 7 methods
 * and their assertions are preserved verbatim; only the seeding (API calls capturing
 * server-generated ids), the mailbox stub (this class's own {@code
 * FilesStackTestResource#getPreviewMailboxWireMock()} usage, replacing {@code Mocks#mailboxAccepts}
 * / {@code Mocks#mailboxDown}), and the transport changed.
 *
 * <p><b>Carried over verbatim from the seam original:</b> {@code ProcedureController} does not run
 * GraphQL at all — it deserializes the raw request body directly into {@link UploadToRequest} with
 * a plain {@code ObjectMapper} — so every scenario here posts the raw (non-GraphQL-wrapped) JSON
 * body straight to {@code /upload-to} via RestAssured, exactly like the old seam's {@code sendForm}
 * (which forwarded the body verbatim, unlike {@code send}'s GraphQL-envelope wrapping).
 */
class UploadToApiIT extends AbstractFilesIT {

  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  private static final String REQUESTER_ID = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
  private static final String OTHER_USER_ID = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";
  private static final String REQUESTER_COOKIE = "ZM_AUTH_TOKEN=fake-token";
  private static final String OTHER_COOKIE = "ZM_AUTH_TOKEN=fake-token-b";

  @BeforeAll
  static void registerUsers() {
    FilesStackTestResource.getUserManagementService().registerToken("fake-token", REQUESTER_ID);
    FilesStackTestResource.getUserManagementService().registerToken("fake-token-b", OTHER_USER_ID);
  }

  /** Restores the preview/mailbox WireMock to its baseline stubs after every test in this class. */
  @AfterEach
  void resetMailboxStubsAfterEach() {
    FilesStackTestResource.resetPreviewMailboxStubs();
  }

  /** Builds the raw (non-GraphQL) JSON body {@code ProcedureController} deserializes directly. */
  private static String uploadToBody(String nodeId, TargetModule targetModule) throws Exception {
    UploadToRequest request = new UploadToRequest();
    request.setNodeId(UUID.fromString(nodeId));
    request.setTargetModule(targetModule);
    return OBJECT_MAPPER.writeValueAsString(request);
  }

  private static Response uploadTo(String method, String cookie, String body) {
    var request =
        RestAssured.given().header("Cookie", cookie).contentType("application/json").body(body);
    return request.request(method, "/upload-to");
  }

  /** Stubs {@code POST /service/upload.*} (mailbox) to accept and return {@code attachmentId}. */
  private static void mailboxAccepts(String attachmentId) {
    FilesStackTestResource.getPreviewMailboxWireMock()
        .stubFor(
            post(urlPathMatching("/service/upload.*"))
                .atPriority(5)
                .willReturn(
                    aResponse().withStatus(200).withBody("200,'null','" + attachmentId + "'")));
  }

  /** Stubs {@code POST /service/upload.*} (mailbox) to fail with a 500. */
  private static void mailboxDown() {
    FilesStackTestResource.getPreviewMailboxWireMock()
        .stubFor(
            post(urlPathMatching("/service/upload.*"))
                .atPriority(5)
                .willReturn(aResponse().withStatus(500)));
  }

  @Test
  void givenANonPostVerbUploadToShouldReturn400() throws Exception {
    // Given -- a syntactically-valid body; irrelevant, since ProcedureController#channelRead0
    // rejects any non-POST method before ever looking at the body.
    String body = uploadToBody(REQUESTER_ID, TargetModule.MAILS);

    // When
    Response response = uploadTo("GET", REQUESTER_COOKIE, body);

    // Then -- BadRequestException -> ExceptionsHandler's generic 400 branch
    Assertions.assertThat(response.getStatusCode()).isEqualTo(400);
    Assertions.assertThat(response.getBody().asString()).isEqualTo("400 Bad Request");
  }

  @Test
  void givenAMalformedJsonBodyUploadToShouldReturn500() {
    // Given -- syntactically-invalid JSON (unterminated object): ObjectMapper#readValue throws a
    // JsonParseException (a JsonProcessingException) with NO cause of its own, so
    // ExceptionsHandler's single getCause() unwrap is a no-op here and the ORIGINAL
    // JsonProcessingException is what matches its `cause instanceof JsonProcessingException`
    // branch -> 500 with the GENERIC payload (not the parser's own message).
    String malformedBody = "{\"nodeId\": \"" + REQUESTER_ID + "\", \"targetModule\": \"MAILS\"";

    // When
    Response response = uploadTo("POST", REQUESTER_COOKIE, malformedBody);

    // Then
    Assertions.assertThat(response.getStatusCode()).isEqualTo(500);
    Assertions.assertThat(response.getBody().asString()).isEqualTo("500 Internal Server Error");
  }

  @Test
  void givenNoPermissionOnTheNodeUploadToShouldReturn404() throws Exception {
    // Given -- a file owned by another user, never shared with the requester
    String fileId =
        seedFile(
            "private.txt", LOCAL_ROOT, "content".getBytes(StandardCharsets.UTF_8), OTHER_COOKIE);
    String body = uploadToBody(fileId, TargetModule.MAILS);

    // When
    Response response = uploadTo("POST", REQUESTER_COOKIE, body);

    // Then -- PermissionsChecker returns ACL.NONE -> !.has(READ_ONLY) -> NodeNotFoundException ->
    // 404
    Assertions.assertThat(response.getStatusCode()).isEqualTo(404);
    Assertions.assertThat(response.getBody().asString()).isEqualTo("404 Not Found");
  }

  @Test
  void givenAFolderTargetUploadToShouldReturn400() throws Exception {
    // Given -- the requester owns the folder (so the permission check passes), but
    // ProcedureService#uploadToModule rejects any FOLDER node type.
    String folderId = seedFolder("myFolder", LOCAL_ROOT, REQUESTER_COOKIE);
    String body = uploadToBody(folderId, TargetModule.MAILS);

    // When
    Response response = uploadTo("POST", REQUESTER_COOKIE, body);

    // Then -- BadRequestException (folder) -> ExceptionsHandler's generic 400 branch
    Assertions.assertThat(response.getStatusCode()).isEqualTo(400);
    Assertions.assertThat(response.getBody().asString()).isEqualTo("400 Bad Request");
  }

  @Test
  void givenStoragesDownloadFailsUploadToShouldReturn500() throws Exception {
    // Given -- requester owns the file, but the storages download connection drops
    String fileId =
        seedFile(
            "toattach.txt",
            LOCAL_ROOT,
            "content".getBytes(StandardCharsets.UTF_8),
            REQUESTER_COOKIE);
    FilesStackTestResource.getStoragesService().setDownloadFails(true);
    String body = uploadToBody(fileId, TargetModule.MAILS);

    // When
    Response response = uploadTo("POST", REQUESTER_COOKIE, body);

    // Then -- fileStoreClient.download(...) throws -> ProcedureService wraps it in
    // InternalServerErrorException(cause) -> ExceptionsHandler unwraps ONE getCause() level to
    // the raw underlying (non-mapped) exception -> falls into the generic else-branch -> 500
    Assertions.assertThat(response.getStatusCode()).isEqualTo(500);
    Assertions.assertThat(response.getBody().asString()).isEqualTo("500 Internal Server Error");
  }

  @Test
  void givenMailboxAcceptsUploadToShouldReturn200WithTheAttachmentId() throws Exception {
    // Given
    String fileId =
        seedFile(
            "attach.txt", LOCAL_ROOT, "content".getBytes(StandardCharsets.UTF_8), REQUESTER_COOKIE);
    mailboxAccepts("85e4b3d9-1f41-4292-9dc8-e933194cc1f2:dbca72a2-8b05-45c5-a83f-bbae05ab907c");
    String body = uploadToBody(fileId, TargetModule.MAILS);

    // When
    Response response = uploadTo("POST", REQUESTER_COOKIE, body);

    // Then
    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    Map<String, Object> json = OBJECT_MAPPER.readValue(response.getBody().asString(), Map.class);
    Assertions.assertThat(json)
        .containsEntry(
            "attachmentId",
            "85e4b3d9-1f41-4292-9dc8-e933194cc1f2:dbca72a2-8b05-45c5-a83f-bbae05ab907c");
  }

  @Test
  void givenACyrillicFilenameUploadToShouldReturn200AndSendAnAsciiContentDisposition()
      throws Exception {
    // Regression: a non-ASCII filename must not be embedded raw in Content-Disposition (the JDK
    // HttpClient rejects header values with a char > 0xFF -> 424); it must be RFC 8187 filename*.
    String fileId =
        seedFile(
            "Привет.txt", LOCAL_ROOT, "content".getBytes(StandardCharsets.UTF_8), REQUESTER_COOKIE);
    mailboxAccepts("85e4b3d9-1f41-4292-9dc8-e933194cc1f2:11111111-2222-3333-4444-555555555555");
    String body = uploadToBody(fileId, TargetModule.MAILS);

    // When
    Response response = uploadTo("POST", REQUESTER_COOKIE, body);

    // Then
    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    Map<String, Object> json = OBJECT_MAPPER.readValue(response.getBody().asString(), Map.class);
    Assertions.assertThat(json)
        .containsEntry(
            "attachmentId",
            "85e4b3d9-1f41-4292-9dc8-e933194cc1f2:11111111-2222-3333-4444-555555555555");
    FilesStackTestResource.getPreviewMailboxWireMock()
        .verify(
            postRequestedFor(urlPathMatching("/service/upload.*"))
                .withHeader(
                    "Content-Disposition",
                    equalTo(
                        "attachment; filename*=UTF-8''%D0%9F%D1%80%D0%B8%D0%B2%D0%B5%D1%82.txt")));
  }

  @Test
  void givenMailboxDownUploadToShouldReturn500() throws Exception {
    // Given
    String fileId =
        seedFile(
            "attach2.txt",
            LOCAL_ROOT,
            "content".getBytes(StandardCharsets.UTF_8),
            REQUESTER_COOKIE);
    mailboxDown();
    String body = uploadToBody(fileId, TargetModule.MAILS);

    // When
    Response response = uploadTo("POST", REQUESTER_COOKIE, body);

    // Then -- MailboxHttpClient#uploadFile sees a non-200 status -> Try.failure(new
    // InternalServerErrorException(errorMessage)) (the String constructor, so getCause() is
    // null) -> ExceptionsHandler's unwrap is a no-op -> matches `cause instanceof
    // InternalServerErrorException` -> 500 with the GENERIC payload (not the error message).
    Assertions.assertThat(response.getStatusCode()).isEqualTo(500);
    Assertions.assertThat(response.getBody().asString()).isEqualTo("500 Internal Server Error");
  }
}
