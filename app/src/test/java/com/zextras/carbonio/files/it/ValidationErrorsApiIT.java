// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.it;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zextras.carbonio.files.FilesStackTestResource;
import com.zextras.carbonio.files.TestUtils;
import com.zextras.carbonio.files.api.utilities.GraphqlCommandBuilder;
import com.zextras.carbonio.files.it.support.AbstractFilesIT;
import io.restassured.response.Response;
import java.util.List;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * {@code com.zextras.carbonio.files.acceptance.ValidationErrorsApiIT} rewritten as an
 * out-of-process {@code @QuarkusIntegrationTest} on {@link AbstractFilesIT}. Covers {@code
 * GenericControllerEvaluator}'s field-validation error paths. Each test sends a GraphQL operation
 * with exactly one invalid field and asserts exactly one validation error with the exact message
 * text produced by {@code GenericControllerEvaluator}.
 *
 * <p>Validation runs in a {@code FieldValidationInstrumentation} that fires BEFORE any resolver
 * executes (it aborts the whole operation via {@code AbortExecutionException} as soon as any bound
 * rule fails), so none of these scenarios need any node/share/link to actually exist in the
 * database — only argument shape matters; {@code VALID_NODE_ID} is a syntactically-valid-length
 * placeholder that is never actually resolved. Doubles as a native-smoke surface under {@code
 * -Dnative}. All 11 methods and their assertions are preserved verbatim; only the transport
 * changed.
 */
class ValidationErrorsApiIT extends AbstractFilesIT {

  private static final String REQUESTER_ID = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
  private static final String REQUESTER_COOKIE = "ZM_AUTH_TOKEN=fake-token";
  private static final String INVALID_NODE_NAME =
      "Invalid node name. The name cannot be empty, longer than 1024 characters, nor be composed"
          + " only by blank spaces.";
  private static final String VALID_NODE_ID = "00000000-0000-0000-0000-000000000042";

  @BeforeAll
  static void registerUsers() {
    FilesStackTestResource.getUserManagementService().registerToken("fake-token", REQUESTER_ID);
  }

  private static Response execute(String bodyPayload) {
    return graphql(bodyPayload, REQUESTER_COOKIE);
  }

  private static void assertValidationError(
      Response response, String expectedMessage, String... expectedPath) {
    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    JsonNode body;
    try {
      body = new ObjectMapper().readTree(response.getBody().asString());
    } catch (Exception e) {
      throw new AssertionError(e);
    }
    JsonNode errors = body.get("errors");
    Assertions.assertThat(errors).hasSize(1);
    JsonNode error = errors.get(0);
    Assertions.assertThat(error.get("message").asText()).isEqualTo(expectedMessage);
    Assertions.assertThat(error.get("path")).hasSize(expectedPath.length);
    for (int i = 0; i < expectedPath.length; i++) {
      Assertions.assertThat(error.get("path").get(i).asText()).isEqualTo(expectedPath[i]);
    }
    JsonNode errorCode = error.path("extensions").path("errorCode");
    Assertions.assertThat(errorCode.isMissingNode() || errorCode.isNull())
        .as("validation errors carry no errorCode")
        .isTrue();
    JsonNode data = body.get("data");
    Assertions.assertThat(data == null || data.isNull() || data.path(expectedPath[0]).isNull())
        .as("validation errors return no data")
        .isTrue();
  }

  @Test
  void givenInvalidEmailOnGetAccountByEmailThenExactlyOneValidationError() {
    // Given
    String bodyPayload =
        GraphqlCommandBuilder.aQueryBuilder("getAccountByEmail")
            .withString("email", "not-an-email")
            .withWantedResultFormat("{ ... on User { id } }")
            .build();

    // When
    Response response = execute(bodyPayload);

    // Then
    assertValidationError(response, "Invalid Email", "getAccountByEmail");
  }

  /**
   * FINDING: {@code GenericControllerEvaluator#checkLinkPassword} is dead code — it is never wired
   * into any {@code InputFieldsController} rule, AND the {@code createLink} schema field has no
   * {@code password} argument at all (confirmed against {@code schema.graphql}). The closest honest
   * behaviour to pin down is: passing an unrecognised {@code password} argument to {@code
   * createLink} fails standard GraphQL document validation (unknown argument), NOT the advertised
   * custom message.
   */
  @Test
  void givenPasswordArgumentOnCreateLinkThenFailsSchemaValidationNotCustomPasswordCheck() {
    // Given — "password" is not a declared argument of createLink; checkLinkPassword is never
    // invoked by InputFieldsController, so this argument cannot reach it either way.
    String bodyPayload =
        "mutation { createLink(node_id: \\\""
            + VALID_NODE_ID
            + "\\\", password: \\\"short\\\") { id } }";

    // When
    Response response = execute(bodyPayload);

    // Then — a standard GraphQL "unknown argument" validation error, not our custom validator.
    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    List<String> errors = TestUtils.jsonResponseToErrors(response.getBody().asString());
    Assertions.assertThat(errors).hasSize(1);
    Assertions.assertThat(errors.get(0))
        .as("createLink has no password argument in schema.graphql; checkLinkPassword is dead code")
        .contains("Validation error")
        .contains("password");
  }

  /**
   * FINDING: {@code findNodes}' top-level {@code limit} argument has NO bound {@code
   * FieldValidationInstrumentation} rule at all — only {@code getNode.children}'s {@code limit} is
   * validated (see {@code GraphQLProvider#buildValidationInstrumentation}, which binds {@code
   * childrenArgumentValidation} to {@code "/getNode/children"}, and has no rule for {@code
   * "/findNodes"}). The reachable path is exercised here instead.
   */
  @Test
  void givenOutOfRangeLimitOnGetNodeChildrenThenExactlyOneValidationError() {
    // Given
    String bodyPayload =
        GraphqlCommandBuilder.aQueryBuilder("getNode")
            .withString("node_id", "LOCAL_ROOT")
            .withWantedResultFormat(
                "{ ... on Folder { children(limit: -1, sort: NAME_ASC) { nodes { id } } } }")
            .build();

    // When
    Response response = execute(bodyPayload);

    // Then
    assertValidationError(
        response,
        "Invalid limit value. The allowed range is between 0 and 50.",
        "getNode",
        "children");
  }

  @Test
  void givenTooLongDescriptionOnUpdateNodeThenExactlyOneValidationError() {
    // Given
    String tooLongDescription = "a".repeat(1025);
    String bodyPayload =
        GraphqlCommandBuilder.aMutationBuilder("updateNode")
            .withString("node_id", VALID_NODE_ID)
            .withString("description", tooLongDescription)
            .withWantedResultFormat("{ id }")
            .build();

    // When
    Response response = execute(bodyPayload);

    // Then
    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    List<String> errors = TestUtils.jsonResponseToErrors(response.getBody().asString());
    Assertions.assertThat(errors)
        .hasSize(1)
        .containsExactly(
            "Invalid node description. Length cannot be empty or more than 1024 characters");
  }

  @Test
  void givenEmptyNameOnUpdateNodeThenExactlyOneValidationError() {
    // Given
    String bodyPayload =
        GraphqlCommandBuilder.aMutationBuilder("updateNode")
            .withString("node_id", VALID_NODE_ID)
            .withString("name", "")
            .withWantedResultFormat("{ id }")
            .build();

    // When
    Response response = execute(bodyPayload);

    // Then
    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    List<String> errors = TestUtils.jsonResponseToErrors(response.getBody().asString());
    Assertions.assertThat(errors)
        .hasSize(1)
        .containsExactly(
            "Invalid node name. The name cannot be empty, longer than 1024 characters, nor be"
                + " composed only by blank spaces.");
  }

  @Test
  void givenWrongLengthNodeIdOnUpdateNodeThenExactlyOneValidationError() {
    // Given
    String bodyPayload =
        GraphqlCommandBuilder.aMutationBuilder("updateNode")
            .withString("node_id", "short-id")
            .withWantedResultFormat("{ id }")
            .build();

    // When
    Response response = execute(bodyPayload);

    // Then
    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    List<String> errors = TestUtils.jsonResponseToErrors(response.getBody().asString());
    Assertions.assertThat(errors)
        .hasSize(1)
        .containsExactly("Invalid node ID: \"short-id\". Length must be 36 characters");
  }

  @Test
  void givenWrongLengthLinkIdOnDeleteLinksThenExactlyOneValidationError() {
    // Given
    String bodyPayload =
        GraphqlCommandBuilder.aMutationBuilder("deleteLinks")
            .withListOfStrings("link_ids", new String[] {"short-link-id"})
            .withWantedResultFormat("")
            .build();

    // When
    Response response = execute(bodyPayload);

    // Then
    assertValidationError(
        response,
        "Invalid link ID: \"short-link-id\". Length must be 36 characters",
        "deleteLinks");
  }

  @Test
  void givenTooLongDescriptionOnCreateLinkThenExactlyOneValidationError() {
    // Given
    String tooLongDescription = "b".repeat(301);
    String bodyPayload =
        GraphqlCommandBuilder.aMutationBuilder("createLink")
            .withString("node_id", VALID_NODE_ID)
            .withString("description", tooLongDescription)
            .withWantedResultFormat("{ id }")
            .build();

    // When
    Response response = execute(bodyPayload);

    // Then
    assertValidationError(
        response,
        "Invalid link description. The description cannot be longer than 300 characters",
        "createLink");
  }

  /**
   * Closes {@code GenericControllerEvaluator#checkNodeName}'s missing branch: the sibling test
   * above ({@code givenEmptyNameOnUpdateNodeThenExactlyOneValidationError}) only exercises the
   * {@code trim().isEmpty()} direction; every happy-path test elsewhere in the suite exercises
   * {@code length <= 1024}. The {@code length > 1024} direction (non-blank but too long) was never
   * exercised.
   */
  @Test
  void givenTooLongNameOnUpdateNodeThenExactlyOneValidationError() {
    // Given
    String tooLongName = "a".repeat(1025);
    String bodyPayload =
        GraphqlCommandBuilder.aMutationBuilder("updateNode")
            .withString("node_id", VALID_NODE_ID)
            .withString("name", tooLongName)
            .withWantedResultFormat("{ id }")
            .build();

    // When
    Response response = execute(bodyPayload);

    // Then
    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    List<String> errors = TestUtils.jsonResponseToErrors(response.getBody().asString());
    Assertions.assertThat(errors)
        .hasSize(1)
        .containsExactly(
            "Invalid node name. The name cannot be empty, longer than 1024 characters, nor be"
                + " composed only by blank spaces.");
  }

  /**
   * Closes {@code GenericControllerEvaluator#checkLimitPagination}'s missing branch: every other
   * test in this suite either omits {@code limit} (default) or passes a negative value (covering
   * the {@code limit >= 0} false direction); the {@code limit > LIMIT_ELEMENTS_FOR_PAGE} direction
   * (a value that's non-negative but over the page-size cap) was never exercised.
   */
  @Test
  void givenOverTheCapLimitOnGetNodeChildrenThenExactlyOneValidationError() {
    // Given
    String bodyPayload =
        GraphqlCommandBuilder.aQueryBuilder("getNode")
            .withString("node_id", "LOCAL_ROOT")
            .withWantedResultFormat(
                "{ ... on Folder { children(limit: 999, sort: NAME_ASC) { nodes { id } } } }")
            .build();

    // When
    Response response = execute(bodyPayload);

    // Then
    assertValidationError(
        response,
        "Invalid limit value. The allowed range is between 0 and 50.",
        "getNode",
        "children");
  }

  @Test
  void givenEmptyShareTargetIdOnGetShareThenExactlyOneValidationError() {
    // Given
    String bodyPayload =
        GraphqlCommandBuilder.aQueryBuilder("getShare")
            .withString("node_id", "LOCAL_ROOT")
            .withString("share_target_id", "")
            .withWantedResultFormat("{ permission }")
            .build();

    // When
    Response response = execute(bodyPayload);

    // Then
    assertValidationError(response, "Invalid user ID. Length cannot be empty", "getShare");
  }

  @Test
  void givenEmptyShareTargetIdOnCreateShareThenValidationError() {
    // Given
    String bodyPayload =
        GraphqlCommandBuilder.aMutationBuilder("createShare")
            .withString("node_id", VALID_NODE_ID)
            .withString("share_target_id", "")
            .withEnumLiteral("permission", "READ_ONLY")
            .withWantedResultFormat("{ permission }")
            .build();

    // When
    Response response = execute(bodyPayload);

    // Then
    assertValidationError(response, "Invalid user ID. Length cannot be empty", "createShare");
  }

  @Test
  void givenWrongLengthNodeIdOnCreateShareThenValidationError() {
    // Given
    String bodyPayload =
        GraphqlCommandBuilder.aMutationBuilder("createShare")
            .withString("node_id", "short-id")
            .withString("share_target_id", "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb")
            .withEnumLiteral("permission", "READ_ONLY")
            .withWantedResultFormat("{ permission }")
            .build();

    // When
    Response response = execute(bodyPayload);

    // Then
    assertValidationError(
        response, "Invalid node ID: \"short-id\". Length must be 36 characters", "createShare");
  }

  @Test
  void givenEmptyShareTargetIdOnUpdateSharesThenValidationError() {
    // Given
    String bodyPayload =
        GraphqlCommandBuilder.aMutationBuilder("updateShares")
            .withString("node_id", VALID_NODE_ID)
            .withListOfStrings("share_target_ids", new String[] {""})
            .withEnumLiteral("permission", "READ_ONLY")
            .withWantedResultFormat("{ permission }")
            .build();

    // When
    Response response = execute(bodyPayload);

    // Then
    assertValidationError(response, "Invalid user ID. Length cannot be empty", "updateShares");
  }

  @Test
  void givenEmptyShareTargetIdOnDeleteSharesThenValidationError() {
    // Given
    String bodyPayload =
        GraphqlCommandBuilder.aMutationBuilder("deleteShares")
            .withString("node_id", VALID_NODE_ID)
            .withListOfStrings("share_target_ids", new String[] {""})
            .withWantedResultFormat("")
            .build();

    // When
    Response response = execute(bodyPayload);

    // Then
    assertValidationError(response, "Invalid user ID. Length cannot be empty", "deleteShares");
  }

  @Test
  void givenBlankNameOnCreateFolderThenValidationError() {
    // Given
    String bodyPayload =
        GraphqlCommandBuilder.aMutationBuilder("createFolder")
            .withString("destination_id", "LOCAL_ROOT")
            .withString("name", "   ")
            .withWantedResultFormat("{ id name }")
            .build();

    // When
    Response response = execute(bodyPayload);

    // Then
    assertValidationError(response, INVALID_NODE_NAME, "createFolder");
  }

  @Test
  void givenTooLongNameOnCreateFolderThenValidationError() {
    // Given
    String bodyPayload =
        GraphqlCommandBuilder.aMutationBuilder("createFolder")
            .withString("destination_id", "LOCAL_ROOT")
            .withString("name", "a".repeat(1025))
            .withWantedResultFormat("{ id name }")
            .build();

    // When
    Response response = execute(bodyPayload);

    // Then
    assertValidationError(response, INVALID_NODE_NAME, "createFolder");
  }

  @Test
  void givenWrongLengthDestinationIdOnCreateFolderThenValidationError() {
    // Given
    String bodyPayload =
        GraphqlCommandBuilder.aMutationBuilder("createFolder")
            .withString("destination_id", "short-id")
            .withString("name", "folder")
            .withWantedResultFormat("{ id name }")
            .build();

    // When
    Response response = execute(bodyPayload);

    // Then
    assertValidationError(
        response, "Invalid node ID: \"short-id\". Length must be 36 characters", "createFolder");
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "x@example..com",
        ".leading@example.com",
        "trailing.@example.com",
        "user@host.local",
        "user@host.internal",
        "not-an-email"
      })
  void givenEmailRejectedByCommonsValidatorOnGetAccountByEmailThenValidationError(String email) {
    // Given
    String bodyPayload =
        GraphqlCommandBuilder.aQueryBuilder("getAccountByEmail")
            .withString("email", email)
            .withWantedResultFormat("{ ... on User { id } }")
            .build();

    // When
    Response response = execute(bodyPayload);

    // Then
    assertValidationError(response, "Invalid Email", "getAccountByEmail");
  }

  @ParameterizedTest
  @ValueSource(strings = {"o'brien@example.com", "first.last+tag@sub.example.co"})
  void givenEmailAcceptedByCommonsValidatorOnGetAccountByEmailThenPassesValidation(String email) {
    // Given
    String bodyPayload =
        GraphqlCommandBuilder.aQueryBuilder("getAccountByEmail")
            .withString("email", email)
            .withWantedResultFormat("{ ... on User { id } }")
            .build();

    // When
    Response response = execute(bodyPayload);

    // Then
    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    Assertions.assertThat(TestUtils.jsonResponseToErrors(response.getBody().asString()))
        .noneMatch(m -> m.contains("Invalid Email"));
  }
}
