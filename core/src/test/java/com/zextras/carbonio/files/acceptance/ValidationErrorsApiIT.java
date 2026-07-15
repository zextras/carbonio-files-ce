// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.acceptance;

import com.zextras.carbonio.files.TestUtils;
import com.zextras.carbonio.files.acceptance.seam.FilesTestApp;
import com.zextras.carbonio.files.acceptance.seam.impl.GuiceNettyFilesTestAppBuilder;
import com.zextras.carbonio.files.api.utilities.GraphqlCommandBuilder;
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
 * Covers {@code GenericControllerEvaluator}'s field-validation error paths (Task 1.2 of the
 * acceptance coverage-expansion plan). Each test sends a GraphQL operation with exactly one
 * invalid field and asserts exactly one validation error with the exact message text produced by
 * {@code GenericControllerEvaluator}.
 *
 * <p>Validation runs in a {@code FieldValidationInstrumentation} that fires BEFORE any resolver
 * executes (it aborts the whole operation via {@code AbortExecutionException} as soon as any
 * bound rule fails), so none of these scenarios need any node/share/link to actually exist in the
 * database — only argument shape matters.
 */
class ValidationErrorsApiIT {

  static FilesTestApp app;

  private static final String REQUESTER_ID = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
  private static final String VALID_NODE_ID = "00000000-0000-0000-0000-000000000042";

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
    HttpRequest httpRequest =
        HttpRequest.of("POST", "/graphql/", "ZM_AUTH_TOKEN=fake-token", bodyPayload);
    return app.send(httpRequest);
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
    HttpResponse httpResponse = execute(bodyPayload);

    // Then
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(200);
    List<String> errors = TestUtils.jsonResponseToErrors(httpResponse.getBodyPayload());
    Assertions.assertThat(errors).hasSize(1).containsExactly("Invalid Email");
  }

  /**
   * FINDING: {@code GenericControllerEvaluator#checkLinkPassword} is dead code — it is never
   * wired into any {@code InputFieldsController} rule, AND the {@code createLink} schema field has
   * no {@code password} argument at all (confirmed against {@code schema.graphql}). The plan's
   * table row ("link password &lt; 8 chars on createLink password" -&gt; "Invalid link password...")
   * describes a scenario that cannot happen through the real API. The closest honest behaviour to
   * pin down is: passing an unrecognised {@code password} argument to {@code createLink} fails
   * standard GraphQL document validation (unknown argument), NOT the advertised custom message.
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
    HttpResponse httpResponse = execute(bodyPayload);

    // Then — a standard GraphQL "unknown argument" validation error, not our custom validator.
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(200);
    List<String> errors = TestUtils.jsonResponseToErrors(httpResponse.getBodyPayload());
    Assertions.assertThat(errors).hasSize(1);
    Assertions.assertThat(errors.get(0))
        .as("createLink has no password argument in schema.graphql; checkLinkPassword is dead code")
        .contains("Validation error")
        .contains("password");
  }

  /**
   * FINDING: the plan's table describes this as "on findNodes", but {@code findNodes}' top-level
   * {@code limit} argument has NO bound {@code FieldValidationInstrumentation} rule at all — only
   * {@code getNode.children}'s {@code limit} is validated (see {@code
   * GraphQLProvider#buildValidationInstrumentation}, which binds {@code childrenArgumentValidation}
   * to {@code "/getNode/children"}, and has no rule for {@code "/findNodes"}). The reachable path
   * is exercised here instead.
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
    HttpResponse httpResponse = execute(bodyPayload);

    // Then
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(200);
    List<String> errors = TestUtils.jsonResponseToErrors(httpResponse.getBodyPayload());
    Assertions.assertThat(errors)
        .hasSize(1)
        .containsExactly("Invalid limit value. The allowed range is between 0 and 50.");
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
    HttpResponse httpResponse = execute(bodyPayload);

    // Then
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(200);
    List<String> errors = TestUtils.jsonResponseToErrors(httpResponse.getBodyPayload());
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
    HttpResponse httpResponse = execute(bodyPayload);

    // Then
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(200);
    List<String> errors = TestUtils.jsonResponseToErrors(httpResponse.getBodyPayload());
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
    HttpResponse httpResponse = execute(bodyPayload);

    // Then
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(200);
    List<String> errors = TestUtils.jsonResponseToErrors(httpResponse.getBodyPayload());
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
    HttpResponse httpResponse = execute(bodyPayload);

    // Then
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(200);
    List<String> errors = TestUtils.jsonResponseToErrors(httpResponse.getBodyPayload());
    Assertions.assertThat(errors)
        .hasSize(1)
        .containsExactly("Invalid link ID: \"short-link-id\". Length must be 36 characters");
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
    HttpResponse httpResponse = execute(bodyPayload);

    // Then
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(200);
    List<String> errors = TestUtils.jsonResponseToErrors(httpResponse.getBodyPayload());
    Assertions.assertThat(errors)
        .hasSize(1)
        .containsExactly(
            "Invalid link description. The description cannot be longer than 300 characters");
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
    HttpResponse httpResponse = execute(bodyPayload);

    // Then
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(200);
    List<String> errors = TestUtils.jsonResponseToErrors(httpResponse.getBodyPayload());
    Assertions.assertThat(errors)
        .hasSize(1)
        .containsExactly("Invalid user ID. Length cannot be empty");
  }
}
