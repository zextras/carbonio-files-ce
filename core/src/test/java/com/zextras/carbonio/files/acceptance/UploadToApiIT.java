// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.acceptance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zextras.carbonio.files.acceptance.seam.FilesTestApp;
import com.zextras.carbonio.files.acceptance.seam.impl.GuiceNettyFilesTestAppBuilder;
import com.zextras.carbonio.files.api.utilities.entities.SimplePopulatorFolder;
import com.zextras.carbonio.files.api.utilities.entities.SimplePopulatorTextFile;
import com.zextras.carbonio.files.rest.types.UploadToRequest;
import com.zextras.carbonio.files.rest.types.UploadToRequest.TargetModule;
import com.zextras.carbonio.files.utilities.http.HttpRequest;
import com.zextras.carbonio.files.utilities.http.HttpResponse;
import java.util.Map;
import java.util.UUID;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Tasks 4.1/4.2 of the acceptance coverage-expansion plan: {@code POST /upload-to}
 * ({@code ProcedureController}/{@code ProcedureService}), the "attach node to mailbox" route.
 *
 * <p><b>FINDING (seam-usage correction):</b> the brief for this task assumed {@code app.send(...)}
 * was the right seam call because the body is "a small JSON control message, not a blob". That is
 * WRONG: {@code FilesTestApp#send}/{@code TestUtils#sendRequest} UNCONDITIONALLY wraps whatever
 * body it is given as a GraphQL envelope ({@code TestUtils#queryPayload} -> {@code
 * {"query":"<body>"}}) on BOTH transports (see {@code RealHttpFilesTestApp#send}'s own comment:
 * "All send() POSTs target the GraphQL endpoints"). {@code ProcedureController} does not run
 * GraphQL at all -- it deserializes {@code httpRequest.content()} directly into {@link
 * UploadToRequest} with a plain {@code ObjectMapper}, so a {@code send()}-wrapped body would
 * itself be a (valid JSON, wrong-shape) parse target, corrupting every scenario below into the
 * "unrecognized property" flavour of the JSON-parse-failure case. {@link FilesTestApp#sendForm}
 * is the correct call: {@code TestUtils#sendFormRequest}/{@code RealHttpFilesTestApp#sendForm}
 * forward the given body payload string VERBATIM on both transports (no wrapping at all) --
 * exactly what a raw, non-GraphQL JSON control message needs. This is already the established
 * idiom for other raw-JSON/form REST bodies (see {@code DownloadMultipleApiIT}/{@code
 * AliasNotAloneDownloadApiIT}, which also use {@code sendForm} for a non-GraphQL POST body).
 * Every scenario here therefore uses {@code sendForm}, not {@code send}.
 */
class UploadToApiIT {

  static FilesTestApp app;
  static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  private static final String REQUESTER_ID = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
  private static final String OTHER_USER_ID = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";
  private static final String REQUESTER_COOKIE = "ZM_AUTH_TOKEN=fake-token";

  @BeforeAll
  static void init() {
    app =
        GuiceNettyFilesTestAppBuilder.aFilesTestApp()
            .withDatabase()
            .withServiceDiscover()
            .withUserManagement(
                Map.of(
                    "fake-token", REQUESTER_ID,
                    "fake-token-b", OTHER_USER_ID))
            .withStorages()
            .withMailbox()
            .build();
  }

  @AfterEach
  void cleanUp() {
    app.backdoor().resetDatabase();
    app.mocks().reset();
  }

  @AfterAll
  static void cleanUpAll() {
    app.close();
  }

  /** Builds the raw (non-GraphQL) JSON body {@code ProcedureController} deserializes directly. */
  private static String uploadToBody(String nodeId, TargetModule targetModule) throws Exception {
    UploadToRequest request = new UploadToRequest();
    request.setNodeId(UUID.fromString(nodeId));
    request.setTargetModule(targetModule);
    return OBJECT_MAPPER.writeValueAsString(request);
  }

  private HttpResponse uploadTo(String method, String cookie, String body) {
    return app.sendForm(HttpRequest.of(method, "/upload-to", cookie, body));
  }

  @Test
  void givenANonPostVerbUploadToShouldReturn400() throws Exception {
    // Given -- a syntactically-valid body; irrelevant, since ProcedureController#channelRead0
    // rejects any non-POST method before ever looking at the body.
    String body = uploadToBody(REQUESTER_ID, TargetModule.MAILS);

    // When
    HttpResponse httpResponse = uploadTo("GET", REQUESTER_COOKIE, body);

    // Then -- BadRequestException -> ExceptionsHandler's generic 400 branch
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(400);
    Assertions.assertThat(httpResponse.getBodyPayload()).isEqualTo("400 Bad Request");
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
    HttpResponse httpResponse = uploadTo("POST", REQUESTER_COOKIE, malformedBody);

    // Then
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(500);
    Assertions.assertThat(httpResponse.getBodyPayload()).isEqualTo("500 Internal Server Error");
  }

  @Test
  void givenNoPermissionOnTheNodeUploadToShouldReturn404() throws Exception {
    // Given -- a file owned by another user, never shared with the requester
    String fileId = "10000000-0000-0000-0000-000000000001";
    app.backdoor().populator().addNode(new SimplePopulatorTextFile(fileId, OTHER_USER_ID, "private.txt"));
    String body = uploadToBody(fileId, TargetModule.MAILS);

    // When
    HttpResponse httpResponse = uploadTo("POST", REQUESTER_COOKIE, body);

    // Then -- PermissionsChecker returns ACL.NONE -> !.has(READ_ONLY) -> NodeNotFoundException -> 404
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(404);
    Assertions.assertThat(httpResponse.getBodyPayload()).isEqualTo("404 Not Found");
  }

  @Test
  void givenAFolderTargetUploadToShouldReturn400() throws Exception {
    // Given -- the requester owns the folder (so the permission check passes), but
    // ProcedureService#uploadToModule rejects any FOLDER node type.
    String folderId = "10000000-0000-0000-0000-000000000002";
    app.backdoor().populator().addNode(new SimplePopulatorFolder(folderId, REQUESTER_ID, "myFolder"));
    String body = uploadToBody(folderId, TargetModule.MAILS);

    // When
    HttpResponse httpResponse = uploadTo("POST", REQUESTER_COOKIE, body);

    // Then -- BadRequestException (folder) -> ExceptionsHandler's generic 400 branch
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(400);
    Assertions.assertThat(httpResponse.getBodyPayload()).isEqualTo("400 Bad Request");
  }

  @Test
  void givenStoragesDownloadFailsUploadToShouldReturn500() throws Exception {
    // Given -- requester owns the file, but the storages download connection drops
    String fileId = "10000000-0000-0000-0000-000000000003";
    app.backdoor().populator().addNode(new SimplePopulatorTextFile(fileId, REQUESTER_ID, "toattach.txt"));
    app.mocks().storagesDownloadConnectionDrops();
    String body = uploadToBody(fileId, TargetModule.MAILS);

    // When
    HttpResponse httpResponse = uploadTo("POST", REQUESTER_COOKIE, body);

    // Then -- fileStoreClient.download(...) throws -> ProcedureService wraps it in
    // InternalServerErrorException(cause) -> ExceptionsHandler unwraps ONE getCause() level to
    // the raw underlying (non-mapped) exception -> falls into the generic else-branch -> 500
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(500);
    Assertions.assertThat(httpResponse.getBodyPayload()).isEqualTo("500 Internal Server Error");
  }

  @Test
  void givenMailboxAcceptsUploadToShouldReturn200WithTheAttachmentId() throws Exception {
    // Given
    String fileId = "10000000-0000-0000-0000-000000000004";
    app.backdoor().populator().addNode(new SimplePopulatorTextFile(fileId, REQUESTER_ID, "attach.txt"));
    app.mocks().storagesServesBlob(fileId, 1);
    app.mocks().mailboxAccepts("85e4b3d9-1f41-4292-9dc8-e933194cc1f2:dbca72a2-8b05-45c5-a83f-bbae05ab907c");
    String body = uploadToBody(fileId, TargetModule.MAILS);

    // When
    HttpResponse httpResponse = uploadTo("POST", REQUESTER_COOKIE, body);

    // Then
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(200);
    @SuppressWarnings("unchecked")
    Map<String, Object> json = OBJECT_MAPPER.readValue(httpResponse.getBodyPayload(), Map.class);
    Assertions.assertThat(json)
        .containsEntry(
            "attachmentId", "85e4b3d9-1f41-4292-9dc8-e933194cc1f2:dbca72a2-8b05-45c5-a83f-bbae05ab907c");
  }

  @Test
  void givenMailboxDownUploadToShouldReturn500() throws Exception {
    // Given
    String fileId = "10000000-0000-0000-0000-000000000005";
    app.backdoor().populator().addNode(new SimplePopulatorTextFile(fileId, REQUESTER_ID, "attach2.txt"));
    app.mocks().storagesServesBlob(fileId, 1);
    app.mocks().mailboxDown();
    String body = uploadToBody(fileId, TargetModule.MAILS);

    // When
    HttpResponse httpResponse = uploadTo("POST", REQUESTER_COOKIE, body);

    // Then -- MailboxHttpClient#uploadFile sees a non-200 status -> Try.failure(new
    // InternalServerErrorException(errorMessage)) (the String constructor, so getCause() is
    // null) -> ExceptionsHandler's unwrap is a no-op -> matches `cause instanceof
    // InternalServerErrorException` -> 500 with the GENERIC payload (not the error message).
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(500);
    Assertions.assertThat(httpResponse.getBodyPayload()).isEqualTo("500 Internal Server Error");
  }
}
