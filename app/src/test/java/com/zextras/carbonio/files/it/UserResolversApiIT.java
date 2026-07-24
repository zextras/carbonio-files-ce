// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.it;

import com.zextras.carbonio.files.FilesStackTestResource;
import com.zextras.carbonio.files.TestUtils;
import com.zextras.carbonio.files.api.utilities.GraphqlCommandBuilder;
import com.zextras.carbonio.files.dal.dao.ebean.ACL;
import com.zextras.carbonio.files.dal.dao.ebean.NodeType;
import com.zextras.carbonio.files.it.support.AbstractFilesIT;
import io.restassured.response.Response;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * {@code com.zextras.carbonio.files.acceptance.UserResolversApiIT} rewritten as an out-of-process
 * {@code @QuarkusIntegrationTest} on {@link AbstractFilesIT}. All 6 methods and their assertions
 * are preserved verbatim; only the seeding mechanism and transport changed.
 *
 * <p><b>GOTCHA discovered by this batch (documented on {@link AbstractFilesIT}'s
 * cookie-convention note too):</b> {@code MockUserManagementService#registerToken}'s default
 * overload hardcodes the SAME email ({@code "fake-email@example.com"}) for EVERY registered user,
 * and the "by email" WireMock stub for that literal string is a single GLOBAL mapping in the
 * shared singleton — the LAST class anywhere in the suite to register a NEW token silently
 * overwrites which id that shared email resolves to. Since this class's whole point is asserting
 * that a KNOWN email resolves to THIS class's own {@code REQUESTER_ID}, it cannot rely on the
 * shared default email at all (verified to break once ANY later-alphabetical class in the suite
 * registers a fresh token). Instead it explicitly registers REQUESTER_ID under a
 * class-scoped-unique email via {@code registerUserById}, which overwrites only that one user's
 * email stub without touching the shared default other classes still rely on.
 *
 * <p><b>JDBC-seeded pre-states (D1 rule 4 escape hatch):</b> the two "unresolvable
 * creator/owner id" scenarios need a node whose {@code creator_id}/{@code owner_id} is a GHOST id
 * never registered with user-management. The real upload/createFolder mutations always stamp the
 * AUTHENTICATED caller as both creator and owner — there is no API path to set either to an
 * arbitrary, unregistered id — so both are seeded via {@link AbstractFilesIT#seedInconsistentNode}.
 * The "owner ghost" scenario also needs a share granted BY that ghost owner; since the ghost has
 * no real session/cookie, the share row is inserted directly via {@link
 * AbstractFilesIT#seedShareRawJdbc} rather than the {@code createShare} mutation.
 */
class UserResolversApiIT extends AbstractFilesIT {

  private static final String REQUESTER_ID = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
  private static final String REQUESTER_COOKIE = "ZM_AUTH_TOKEN=fake-token";
  private static final String KNOWN_EMAIL = "user-resolvers-known@example.com";

  @BeforeAll
  static void registerUsers() {
    FilesStackTestResource.getUserManagementService().registerToken("fake-token", REQUESTER_ID);
    // Overwrite REQUESTER_ID's email stub with a class-scoped-unique value (see class javadoc):
    // the shared default email is a suite-wide single mapping and gets stolen by whichever class
    // registers a new user last.
    FilesStackTestResource.getUserManagementService()
        .registerUserById(REQUESTER_ID, KNOWN_EMAIL, "Fake User", "example.com", "active");
  }

  private Response execute(String bodyPayload) {
    return graphql(bodyPayload, REQUESTER_COOKIE);
  }

  @Test
  void givenARegisteredEmailGetAccountByEmailShouldReturnTheUser() {
    // Given — REQUESTER_ID is already registered with KNOWN_EMAIL via the class-level
    // registerToken fixture (MockUserManagementService#registerToken hardcodes this email for
    // every registered user).
    String bodyPayload =
        GraphqlCommandBuilder.aQueryBuilder("getAccountByEmail")
            .withString("email", KNOWN_EMAIL)
            .withWantedResultFormat("{ ... on User { id email full_name } }")
            .build();

    // When
    Response response = execute(bodyPayload);

    // Then
    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    Assertions.assertThat(TestUtils.jsonResponseToErrors(response.getBody().asString())).isEmpty();
    Map<String, Object> account = TestUtils.jsonResponseToMap(response.getBody().asString(), "getAccountByEmail");
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
    Response response = execute(bodyPayload);

    // Then
    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    List<String> errors = TestUtils.jsonResponseToErrors(response.getBody().asString());
    Assertions.assertThat(errors).hasSize(1).containsExactly("Could not find user with identifier " + unknownEmail);
    Assertions.assertThat(TestUtils.jsonResponseToValue(response.getBody().asString(), "getAccountByEmail"))
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
    Response response = execute(bodyPayload);

    // Then
    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    List<Map<String, Object>> accounts = TestUtils.jsonResponseToList(response.getBody().asString(), "getAccountsByEmail");
    Assertions.assertThat(accounts).hasSize(3);
    Assertions.assertThat(accounts.get(0)).isNull();
    Assertions.assertThat(accounts.get(1)).containsEntry("id", REQUESTER_ID).containsEntry("email", KNOWN_EMAIL);
    Assertions.assertThat(accounts.get(2)).isNull();

    List<String> errors = TestUtils.jsonResponseToErrors(response.getBody().asString());
    Assertions.assertThat(errors)
        .hasSize(2)
        .containsExactly(
            "Could not find user with identifier " + missingA,
            "Could not find user with identifier " + missingB);
  }

  @Test
  void givenAnUnresolvableCreatorIdTheCreatorSubFieldShouldSurfaceAccountNotFound() throws SQLException {
    // Given — a file OWNED by the requester (so the requester has full read access trivially) but
    // CREATED by a ghost id never registered in the UM mock. creator/owner are stored in separate
    // columns, so this isolates creator's resolver independently of owner's.
    String ghostCreatorId = "dddddddd-dddd-dddd-dddd-dddddddddddd";
    String nodeId = "00000000-0000-0000-0000-000000000001";
    seedInconsistentNode(
        nodeId, ghostCreatorId, REQUESTER_ID, "LOCAL_ROOT", "creatorless.txt", NodeType.TEXT,
        "LOCAL_ROOT", 1L, "text/plain");

    String bodyPayload =
        GraphqlCommandBuilder.aQueryBuilder("getNode")
            .withString("node_id", nodeId)
            .withWantedResultFormat("{ id name creator { id } owner { id } }")
            .build();

    // When
    Response response = execute(bodyPayload);

    // Then
    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    List<String> errors = TestUtils.jsonResponseToErrors(response.getBody().asString());
    Assertions.assertThat(errors)
        .anySatisfy(
            message ->
                Assertions.assertThat(message).isEqualTo("Could not find user with identifier " + ghostCreatorId));

    // creator: User! is NON-NULL in the schema (unlike owner/last_editor), so per GraphQL
    // null-propagation a null-data DataFetcherResult on `creator` bubbles up to the nearest
    // nullable ancestor — `getNode` itself (schema: `getNode(...): Node`, nullable) — nulling the
    // WHOLE node, not just the `creator` sub-field. This is asserted as the REAL, current
    // behaviour: id/name/owner do NOT survive here alongside the error.
    Map<String, Object> node = TestUtils.jsonResponseToMap(response.getBody().asString(), "getNode");
    Assertions.assertThat(node).isEmpty();
  }

  @Test
  void
      givenAnUnresolvableOwnerIdTheOwnerAndLastEditorSubFieldsShouldBeNullWithScopedErrorsWhileTheRestOfTheNodeResolves()
          throws SQLException {
    // Given — a file CREATED by the requester (so creator resolves fine) but OWNED by a ghost id
    // never registered in the UM mock; a direct Share row grants the requester READ access despite
    // not owning it. Since the file version's last_editor is stamped from the SAME ownerId at
    // seed time, last_editor is unresolvable for the identical reason as owner here.
    String ghostOwnerId = "eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee";
    String nodeId = "00000000-0000-0000-0000-000000000002";
    seedInconsistentNode(
        nodeId, REQUESTER_ID, ghostOwnerId, "LOCAL_ROOT", "ownerless.txt", NodeType.TEXT,
        "LOCAL_ROOT", 1L, "text/plain");
    seedShareRawJdbc(nodeId, REQUESTER_ID, ACL.SharePermission.READ_ONLY);

    String bodyPayload =
        GraphqlCommandBuilder.aQueryBuilder("getNode")
            .withString("node_id", nodeId)
            .withWantedResultFormat("{ id name creator { id } owner { id } last_editor { id } }")
            .build();

    // When
    Response response = execute(bodyPayload);

    // Then — owner/last_editor: User (nullable in the schema) resolve to null WITHOUT bubbling,
    // so the rest of the node (id/name/creator) survives alongside the two scoped errors.
    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    Map<String, Object> node = TestUtils.jsonResponseToMap(response.getBody().asString(), "getNode");
    Assertions.assertThat(node).containsEntry("id", nodeId).containsEntry("name", "ownerless");
    Assertions.assertThat((Map<String, Object>) node.get("creator")).containsEntry("id", REQUESTER_ID);
    Assertions.assertThat(node.get("owner")).isNull();
    Assertions.assertThat(node.get("last_editor")).isNull();

    List<String> errors = TestUtils.jsonResponseToErrors(response.getBody().asString());
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
    String folderId = seedFolder("folder", LOCAL_ROOT, REQUESTER_COOKIE);

    String bodyPayload =
        GraphqlCommandBuilder.aQueryBuilder("getNode")
            .withString("node_id", folderId)
            .withWantedResultFormat("{ id last_editor { id } }")
            .build();

    // When
    Response response = execute(bodyPayload);

    // Then
    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    Assertions.assertThat(TestUtils.jsonResponseToErrors(response.getBody().asString())).isEmpty();
    Map<String, Object> node = TestUtils.jsonResponseToMap(response.getBody().asString(), "getNode");
    Assertions.assertThat(node).containsEntry("id", folderId);
    Assertions.assertThat(node.get("last_editor")).isNull();
  }
}
