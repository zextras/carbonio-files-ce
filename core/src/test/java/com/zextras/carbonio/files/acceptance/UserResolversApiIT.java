// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.acceptance;

import com.zextras.carbonio.files.TestUtils;
import com.zextras.carbonio.files.acceptance.seam.FilesTestApp;
import com.zextras.carbonio.files.acceptance.seam.impl.GuiceNettyFilesTestAppBuilder;
import com.zextras.carbonio.files.api.utilities.GraphqlCommandBuilder;
import com.zextras.carbonio.files.api.utilities.entities.PopulatorNode;
import com.zextras.carbonio.files.api.utilities.entities.SimplePopulatorFolder;
import com.zextras.carbonio.files.dal.dao.ebean.ACL;
import com.zextras.carbonio.files.dal.dao.ebean.NodeType;
import com.zextras.carbonio.files.utilities.http.HttpRequest;
import com.zextras.carbonio.files.utilities.http.HttpResponse;
import java.util.List;
import java.util.Map;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Task 5.2 of the acceptance coverage-expansion plan: {@code UserDataFetcher}'s account/creator/
 * owner/last_editor resolvers.
 *
 * <p>Every registered user-management fixture in this suite (both the class-level {@code
 * withUserManagement(Map)} builder call and the {@code Mocks#registerUser} post-build helper) maps
 * to the SAME hardcoded email {@code "fake-email@example.com"} ({@code
 * MockUserManagementService#registerToken}) — there is no seam capability to give two different
 * registered users two different, distinguishable emails. This is not a blocker for what is tested
 * here (the partial-success/order-preservation test below only needs ONE known-resolvable email
 * plus one deliberately-unregistered one), so it is noted here rather than reported as a missing
 * capability the way the {@code getConfigs} ServiceDiscover-stubbing gap is in {@code
 * GetConfigsApiIT}.
 *
 * <p><b>{@code creator}/{@code owner}/{@code last_editor} are coupled in test data:</b> {@code
 * DatabasePopulator#addNode} stores a separate {@code creatorId} but derives the FIRST file
 * version's {@code last_editor} from the SAME {@code ownerId} parameter passed to {@code
 * FileVersionRepository#createNewFileVersion} (see {@code NodeDataFetcher}'s "TODO Move up when
 * the last_editor coherent between node and file version will be coherent" comment). There is no
 * populator method that sets a file version's last-editor independently of the node's owner, so
 * "owner unresolvable" and "last_editor unresolvable" are demonstrated TOGETHER on one file node
 * below (both really do share the same underlying id in this fixture), while {@code creator} is
 * demonstrated independently (it has its own column).
 */
class UserResolversApiIT {

  static FilesTestApp app;

  private static final String REQUESTER_ID = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
  private static final String REQUESTER_COOKIE = "ZM_AUTH_TOKEN=fake-token";
  private static final String KNOWN_EMAIL = "fake-email@example.com";

  @BeforeAll
  static void init() {
    app =
        GuiceNettyFilesTestAppBuilder.aFilesTestApp()
            .withDatabase()
            .withServiceDiscover()
            .withUserManagement(Map.of("fake-token", REQUESTER_ID))
            .build();
  }

  @AfterEach
  void cleanUp() {
    app.backdoor().resetDatabase();
  }

  @AfterAll
  static void cleanUpAll() {
    app.close();
  }

  private HttpResponse execute(String bodyPayload) {
    return app.send(HttpRequest.of("POST", "/graphql/", REQUESTER_COOKIE, bodyPayload));
  }

  @Test
  void givenARegisteredEmailGetAccountByEmailShouldReturnTheUser() {
    // Given — REQUESTER_ID is already registered with KNOWN_EMAIL via the class-level
    // withUserManagement(...) fixture (MockUserManagementService#registerToken hardcodes this
    // email for every registered user).
    String bodyPayload =
        GraphqlCommandBuilder.aQueryBuilder("getAccountByEmail")
            .withString("email", KNOWN_EMAIL)
            .withWantedResultFormat("{ ... on User { id email full_name } }")
            .build();

    // When
    HttpResponse httpResponse = execute(bodyPayload);

    // Then
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(200);
    Assertions.assertThat(TestUtils.jsonResponseToErrors(httpResponse.getBodyPayload())).isEmpty();
    Map<String, Object> account =
        TestUtils.jsonResponseToMap(httpResponse.getBodyPayload(), "getAccountByEmail");
    Assertions.assertThat(account).containsEntry("id", REQUESTER_ID).containsEntry("email", KNOWN_EMAIL);
  }

  @Test
  void givenAnUnregisteredEmailGetAccountByEmailShouldReturnAccountNotFound() {
    // Given
    String unknownEmail = "nobody-knows-this@example.com";
    String bodyPayload =
        GraphqlCommandBuilder.aQueryBuilder("getAccountByEmail")
            .withString("email", unknownEmail)
            .withWantedResultFormat("{ ... on User { id } }")
            .build();

    // When
    HttpResponse httpResponse = execute(bodyPayload);

    // Then
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(200);
    List<String> errors = TestUtils.jsonResponseToErrors(httpResponse.getBodyPayload());
    Assertions.assertThat(errors)
        .hasSize(1)
        .containsExactly("Could not find user with identifier " + unknownEmail);
    Assertions.assertThat(TestUtils.jsonResponseToValue(httpResponse.getBodyPayload(), "getAccountByEmail"))
        .isEmpty();
  }

  @SuppressWarnings("unchecked")
  @Test
  void givenAMixOfKnownAndUnknownEmailsGetAccountsByEmailShouldReturnPartialSuccessInOrder() {
    // Given — [unknown, known, unknown]: order-preservation must hold even with data sandwiched
    // between two failures, not just alternating from the first element.
    String missingA = "missing-a@example.com";
    String missingB = "missing-b@example.com";
    String bodyPayload =
        GraphqlCommandBuilder.aQueryBuilder("getAccountsByEmail")
            .withListOfStrings("emails", new String[] {missingA, KNOWN_EMAIL, missingB})
            .withWantedResultFormat("{ ... on User { id email } }")
            .build();

    // When
    HttpResponse httpResponse = execute(bodyPayload);

    // Then
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(200);
    List<Map<String, Object>> accounts =
        TestUtils.jsonResponseToList(httpResponse.getBodyPayload(), "getAccountsByEmail");
    Assertions.assertThat(accounts).hasSize(3);
    Assertions.assertThat(accounts.get(0)).isNull();
    Assertions.assertThat(accounts.get(1)).containsEntry("id", REQUESTER_ID).containsEntry("email", KNOWN_EMAIL);
    Assertions.assertThat(accounts.get(2)).isNull();

    List<String> errors = TestUtils.jsonResponseToErrors(httpResponse.getBodyPayload());
    Assertions.assertThat(errors)
        .hasSize(2)
        .containsExactly(
            "Could not find user with identifier " + missingA,
            "Could not find user with identifier " + missingB);
  }

  @Test
  void givenAnUnresolvableCreatorIdTheCreatorSubFieldShouldSurfaceAccountNotFound() {
    // Given — a file OWNED by the requester (so the requester has full read access trivially) but
    // CREATED by a ghost id never registered in the UM mock. creator/owner are stored in separate
    // columns by DatabasePopulator#addNode, so this isolates creator's resolver independently of
    // owner's.
    String ghostCreatorId = "dddddddd-dddd-dddd-dddd-dddddddddddd";
    String nodeId = "00000000-0000-0000-0000-000000000001";
    app.backdoor()
        .populator()
        .addNode(
            new PopulatorNode(
                nodeId, ghostCreatorId, REQUESTER_ID, "LOCAL_ROOT", "creatorless.txt", "",
                NodeType.TEXT, "LOCAL_ROOT", 1L, "text/plain"));

    String bodyPayload =
        GraphqlCommandBuilder.aQueryBuilder("getNode")
            .withString("node_id", nodeId)
            .withWantedResultFormat("{ id name creator { id } owner { id } }")
            .build();

    // When
    HttpResponse httpResponse = execute(bodyPayload);

    // Then
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(200);
    List<String> errors = TestUtils.jsonResponseToErrors(httpResponse.getBodyPayload());
    Assertions.assertThat(errors)
        .anySatisfy(
            message ->
                Assertions.assertThat(message)
                    .isEqualTo("Could not find user with identifier " + ghostCreatorId));

    // creator: User! is NON-NULL in the schema (unlike owner/last_editor), so per GraphQL
    // null-propagation a null-data DataFetcherResult on `creator` bubbles up to the nearest
    // nullable ancestor — `getNode` itself (schema: `getNode(...): Node`, nullable) — nulling the
    // WHOLE node, not just the `creator` sub-field. This is asserted as the REAL, current
    // behaviour (a divergence from the plan's generic "error scoped to that sub-field, rest of
    // node resolves" expectation, which only actually holds for the NULLABLE owner/last_editor
    // fields — see the next test): id/name/owner do NOT survive here alongside the error.
    Map<String, Object> node = TestUtils.jsonResponseToMap(httpResponse.getBodyPayload(), "getNode");
    Assertions.assertThat(node).isEmpty();
  }

  @Test
  void
      givenAnUnresolvableOwnerIdTheOwnerAndLastEditorSubFieldsShouldBeNullWithScopedErrorsWhileTheRestOfTheNodeResolves() {
    // Given — a file CREATED by the requester (so creator resolves fine) but OWNED by a ghost id
    // never registered in the UM mock; a direct Share row grants the requester READ access despite
    // not owning it (same deliberately-inconsistent-fixture technique as GetNodeEdgeApiIT/
    // GetPathApiIT). Because DatabasePopulator#addNode derives the file version's last_editor from
    // the SAME ownerId, last_editor is unresolvable for the identical reason as owner here.
    String ghostOwnerId = "eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee";
    String nodeId = "00000000-0000-0000-0000-000000000002";
    app.backdoor()
        .populator()
        .addNode(
            new PopulatorNode(
                nodeId, REQUESTER_ID, ghostOwnerId, "LOCAL_ROOT", "ownerless.txt", "",
                NodeType.TEXT, "LOCAL_ROOT", 1L, "text/plain"))
        .addShare(nodeId, REQUESTER_ID, ACL.SharePermission.READ_ONLY);

    String bodyPayload =
        GraphqlCommandBuilder.aQueryBuilder("getNode")
            .withString("node_id", nodeId)
            .withWantedResultFormat("{ id name creator { id } owner { id } last_editor { id } }")
            .build();

    // When
    HttpResponse httpResponse = execute(bodyPayload);

    // Then — owner/last_editor: User (nullable in the schema) resolve to null WITHOUT bubbling,
    // so the rest of the node (id/name/creator) survives alongside the two scoped errors.
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(200);
    Map<String, Object> node = TestUtils.jsonResponseToMap(httpResponse.getBodyPayload(), "getNode");
    Assertions.assertThat(node).containsEntry("id", nodeId).containsEntry("name", "ownerless");
    Assertions.assertThat((Map<String, Object>) node.get("creator")).containsEntry("id", REQUESTER_ID);
    Assertions.assertThat(node.get("owner")).isNull();
    Assertions.assertThat(node.get("last_editor")).isNull();

    List<String> errors = TestUtils.jsonResponseToErrors(httpResponse.getBodyPayload());
    Assertions.assertThat(errors)
        .filteredOn(message -> message.equals("Could not find user with identifier " + ghostOwnerId))
        .hasSize(2); // one for `owner`, one for `last_editor` — same ghost id, two separate fields
  }

  @Test
  void givenAFolderThatWasNeverEditedTheLastEditorSubFieldShouldBeNullWithNoError() {
    // Given — a plain folder. NodeDataFetcher only ever populates the LAST_EDITOR key in the
    // field-resolver's local context for FILE nodes (it reads it off the file version's
    // last_editor column); for FOLDER/ROOT nodes the key is never put into the context at all, so
    // UserDataFetcher#getUserFetcher's local-context lookup finds nothing and returns an empty
    // (no data, no error) result — not an accountNotFound error.
    String folderId = "10000000-0000-0000-0000-000000000001";
    app.backdoor().populator().addNode(new SimplePopulatorFolder(folderId, REQUESTER_ID));

    String bodyPayload =
        GraphqlCommandBuilder.aQueryBuilder("getNode")
            .withString("node_id", folderId)
            .withWantedResultFormat("{ id last_editor { id } }")
            .build();

    // When
    HttpResponse httpResponse = execute(bodyPayload);

    // Then
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(200);
    Assertions.assertThat(TestUtils.jsonResponseToErrors(httpResponse.getBodyPayload())).isEmpty();
    Map<String, Object> node = TestUtils.jsonResponseToMap(httpResponse.getBodyPayload(), "getNode");
    Assertions.assertThat(node).containsEntry("id", folderId);
    Assertions.assertThat(node.get("last_editor")).isNull();
  }
}
