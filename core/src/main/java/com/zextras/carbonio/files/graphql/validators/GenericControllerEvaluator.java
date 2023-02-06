// SPDX-FileCopyrightText: 2022 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.graphql.validators;

import com.google.inject.Inject;
import com.google.inject.assistedinject.Assisted;
import com.zextras.carbonio.files.Files;
import com.zextras.carbonio.files.Files.Db.RootId;
import graphql.GraphQLError;
import graphql.execution.instrumentation.fieldvalidation.FieldAndArguments;
import graphql.execution.instrumentation.fieldvalidation.FieldValidationEnvironment;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.apache.commons.validator.routines.EmailValidator;

/**
 * <p>Represents a generic input controller that can be built by selecting which inputs needs to be
 * validated before the execution of the related {@link GraphQLError} request.</p>
 * <p>It is used to build a specific input controller for a specific {@link graphql.GraphQL} API so
 * when the {@link #evaluate()} method is called all the selected validation function are performed
 * transparently and in the specified selection order.</p>
 */
public class GenericControllerEvaluator {

  private static final int sLengthNodeId = 36;
  private static final int sLengthLinkId = 36;

  private final FieldAndArguments          fieldAndArguments;
  private final FieldValidationEnvironment environment;
  private       List<Parameter>            inputsToCheckWithRelativeFunctions;
  private       List<Parameter>            objectsToCheckWithRelativeFunctions;

  @Inject
  GenericControllerEvaluator(
    @Assisted FieldAndArguments fieldAndArguments,
    @Assisted FieldValidationEnvironment environment
  ) {
    this.fieldAndArguments = fieldAndArguments;
    this.environment = environment;
    inputsToCheckWithRelativeFunctions = new ArrayList<>();
    objectsToCheckWithRelativeFunctions = new ArrayList<>();
  }

  /**
   * Checks if the given email is valid. It builds a {@link Parameter} containing the implementation
   * of the related {@link Function} necessary to check the value.
   *
   * @param emailKey is a {@link String} representing the input key mapping the value of the email
   *
   * @return the {@link GenericControllerEvaluator} to allow the possibility to chain multiple
   * checks to select.
   */
  public GenericControllerEvaluator checkEmail(String emailKey) {
    inputsToCheckWithRelativeFunctions.add(Parameter.build(
      emailKey,
      (key) -> validateEmail(fieldAndArguments.getArgumentValue(key))
    ));
    return this;
  }

  /**
   * Checks if the list of given emails are valid. It builds a {@link Parameter} containing the
   * implementation of the related {@link Function} necessary to check the value.
   *
   * @param emailsKey is a {@link String} representing the input key mapping the value of the email
   * list
   *
   * @return the {@link GenericControllerEvaluator} to allow the possibility to chain multiple
   * checks to select.
   */
  public GenericControllerEvaluator checkEmails(String emailsKey) {
    inputsToCheckWithRelativeFunctions.add(Parameter.build(
      emailsKey,
      (key) -> {
        List<String> emails = fieldAndArguments.getArgumentValue(key);
        return Optional.of(emails
          .stream()
          .map(this::validateEmail)
          .filter(Optional::isPresent)
          .map(Optional::get)
          .collect(Collectors.joining("\n"))
        );
      }
    ));
    return this;
  }

  /**
   * Checks if the node id is valid and creates the related error message if it is invalid. It
   * builds a {@link Parameter} containing the implementation of the related {@link Function}
   * necessary to check the value.
   *
   * @param nodeIdKey is a {@link String} representing the input key mapping the value of a node
   * id.
   *
   * @return the {@link GenericControllerEvaluator} to allow the possibility to chain multiple
   * checks to select.
   */
  public GenericControllerEvaluator checkNodeId(String nodeIdKey) {
    inputsToCheckWithRelativeFunctions.add(Parameter.build(
      nodeIdKey,
      (key) -> validateNodeId(fieldAndArguments.getArgumentValue(key))
    ));
    return this;
  }

  /**
   * Checks if all the nodes ids are valid and creates the related error messages if they aren't. It
   * builds a {@link Parameter} containing the implementation of the related {@link Function}
   * necessary to check the value.
   *
   * @param nodesIdsKey is a {@link String} representing the input key mapping the value of a list
   * of nodes ids.
   *
   * @return the {@link GenericControllerEvaluator} to allow the possibility to chain multiple
   * checks to select.
   */
  public GenericControllerEvaluator checkNodesIds(String nodesIdsKey) {
    inputsToCheckWithRelativeFunctions.add(Parameter.build(
      nodesIdsKey,
      (key) ->
      {
        List<String> nodesIds = fieldAndArguments.getArgumentValue(key);
        return Optional.of(nodesIds.stream()
          .map(this::validateNodeId)
          .filter(Optional::isPresent)
          .map(Optional::get)
          .collect(Collectors.joining("\n"))
        );
      }
    ));
    return this;
  }

  /**
   * Checks if the node name is valid and creates the related error message if it is not. It builds
   * a {@link Parameter} containing the implementation of the related {@link Function} necessary to
   * check the value.
   *
   * @param nodeNameKey is a {@link String} representing the input key containing the value of a
   * node name.
   *
   * @return the {@link GenericControllerEvaluator} to allow the possibility to chain multiple
   * checks to select.
   */
  public GenericControllerEvaluator checkNodeName(String nodeNameKey) {
    inputsToCheckWithRelativeFunctions.add(Parameter.build(
      nodeNameKey,
      (key) ->
      {
        String nodeName = fieldAndArguments.getArgumentValue(key);

        return (nodeName == null
          || (!nodeName.trim().isEmpty() && nodeName.trim().length() <= 1024)
        )
          ? Optional.empty()
          : Optional.of(
            "Invalid node name. The name cannot be empty, longer than 1024 characters, nor be composed only by blank spaces."
          );
      }
    ));
    return this;
  }

  /**
   * Checks if the node description is valid and creates the related error message if it is invalid.
   * It builds a {@link Parameter} containing the implementation of the related {@link Function}
   * necessary to check the value.
   *
   * @param nodeDescriptionKey is a {@link String} representing the input key mapping the value of a
   * node description.
   *
   * @return the {@link GenericControllerEvaluator} to allow the possibility to chain multiple
   * checks to select.
   */
  public GenericControllerEvaluator checkNodeDescription(String nodeDescriptionKey) {
    inputsToCheckWithRelativeFunctions.add(Parameter.build(
      nodeDescriptionKey,
      (key) ->
      {
        String nodeDescription = fieldAndArguments.getArgumentValue(key);

        return (nodeDescription == null || nodeDescription.length() <= 1024)
          ? Optional.empty()
          : Optional.of(
            "Invalid node description. Length cannot be empty or more than 1024 characters"
          );
      }
    ));
    return this;
  }

  /**
   * Checks if the user id is valid and creates the related error message if it is invalid. It
   * builds a {@link Parameter} containing the implementation of the related {@link Function}
   * necessary to check the value.
   *
   * @param userIdKey is a {@link String} representing the input key mapping the value of a user
   * id.
   *
   * @return the {@link GenericControllerEvaluator} to allow the possibility to chain multiple
   * checks to select.
   */
  public GenericControllerEvaluator checkUserId(String userIdKey) {
    inputsToCheckWithRelativeFunctions.add(Parameter.build(
      userIdKey,
      (key) ->
      {
        String userId = fieldAndArguments.getArgumentValue(key);

        return (userId == null || !userId.isEmpty())
          ? Optional.empty()
          : Optional.of("Invalid user ID. Length cannot be empty");
      }
    ));
    return this;
  }

  /**
   * Checks if the limit of the pagination is valid and creates the related error message if it is
   * invalid. It builds a {@link Parameter} containing the implementation of the related {@link
   * Function} necessary to check the value.
   *
   * @param limitKey is a {@link String} representing the input key mapping the value of a limit of
   * a pagination.
   *
   * @return the {@link GenericControllerEvaluator} to allow the possibility to chain multiple
   * checks to select.
   */
  public GenericControllerEvaluator checkLimitPagination(String limitKey) {
    inputsToCheckWithRelativeFunctions.add(Parameter.build(
      limitKey,
      (key) ->
      {
        int limit = fieldAndArguments.getArgumentValue(key);
        return (limit >= 0 && limit <= Files.GraphQL.LIMIT_ELEMENTS_FOR_PAGE)
          ? Optional.empty()
          : Optional.of(
            "Invalid limit value. The allowed range is between 0 and "
              + Files.GraphQL.LIMIT_ELEMENTS_FOR_PAGE + "."
          );
      }
    ));
    return this;
  }

  public GenericControllerEvaluator checkLinkPassword(String linkPasswordKey) {
    inputsToCheckWithRelativeFunctions.add(Parameter.build(
      linkPasswordKey,
      (key) ->
      {
        String linkPassword = fieldAndArguments.getArgumentValue(key);

        return (linkPassword == null || linkPassword.length() >= 8)
          ? Optional.empty()
          : Optional.of("Invalid link password. Length cannot be less than 8 characters");
      }
    ));
    return this;
  }

  public GenericControllerEvaluator checkLinkIds(String linkIdsKey) {
    inputsToCheckWithRelativeFunctions.add(Parameter.build(
      linkIdsKey,
      (key) ->
      {
        List<String> linkIds = fieldAndArguments.getArgumentValue(key);
        return Optional.of(
          linkIds
            .stream()
            .map(this::validateLinkId)
            .filter(Optional::isPresent)
            .map(Optional::get)
            .collect(Collectors.joining("\n"))
        );
      }
    ));
    return this;
  }

  public GenericControllerEvaluator checkLinkId(String linkIdKey) {
    inputsToCheckWithRelativeFunctions.add(Parameter.build(
      linkIdKey,
      (key) -> validateLinkId(fieldAndArguments.getArgumentValue(key))
    ));
    return this;
  }

  public GenericControllerEvaluator checkLinkDescription(String linkDescriptionKey) {
    inputsToCheckWithRelativeFunctions.add(Parameter.build(
      linkDescriptionKey,
      (key) -> {
        String linkDescription = fieldAndArguments.getArgumentValue(linkDescriptionKey);

        return (linkDescription == null || linkDescription.trim()
          .length() <= 300)
          ? Optional.empty()
          : Optional.of(
            "Invalid link description. The description cannot be longer than 300 characters");
      }
    ));
    return this;
  }

  /**
   * <p>Evaluates all the {@link Parameter}s checking the validity of the values mapped by the
   * specified key.</p>
   * <p>There are two types of {@link List} of {@link Parameter}s. The first one contains functions
   * that check the format of the related input value. This {@link Parameter}s can be checked all
   * together and the evaluation continues even if the check of one of them fails. The second one
   * contains the functions that check the existence of the referred object or the permissions of a
   * user. Each {@link Parameter} is checked one at a time and if one of them fails the evaluation
   * stops and returns a {@link String} containing the error message. The {@link Parameter}s of this
   * list are checked only if all the {@link Parameter}s of the first list are valid.</p>
   *
   * @return an {@link Optional#empty()} if all the {@link Parameter}s checked are valid, otherwise
   * it returns an {@link Optional} containing a {@link GraphQLError}.
   */
  public Optional<GraphQLError> evaluate() {
    String errors = inputsToCheckWithRelativeFunctions.stream()
      .map(Parameter::check)
      .filter(Optional::isPresent)
      .map(Optional::get)
      .collect(Collectors.joining("\n"));

    if (errors.isEmpty()) {
      for (Parameter objectToCheck : objectsToCheckWithRelativeFunctions) {
        Optional<String> error = objectToCheck.check();
        if (error.isPresent()) {
          return Optional.of(environment.mkError(error.get(), fieldAndArguments));
        }
      }
      return Optional.empty();
    }
    return Optional.of(environment.mkError(errors, fieldAndArguments));
  }

  /**
   * Allows to validate a node id. It is necessary to respect the DRY principle.
   *
   * @param nodeId is a {@link String} of the node id.
   *
   * @return an {@link Optional#empty()} if the node id is valid, otherwise it returns an {@link
   * Optional} containing the error message.
   */
  private Optional<String> validateNodeId(String nodeId) {
    if (nodeId == null
      || nodeId.equals(RootId.LOCAL_ROOT)
      || nodeId.equals(RootId.TRASH_ROOT)
      || nodeId.length() == sLengthNodeId
    ) {
      return Optional.empty();
    }
    return Optional.of("Invalid node ID: \""
      + Optional.ofNullable(nodeId).orElse("")
      + "\". Length must be " + sLengthNodeId + " characters");
  }

  /**
   * Allows to validate an email.
   *
   * @param email is a {@link String} of the email to validate.
   *
   * @return an {@link Optional#empty()} if the email is valid, otherwise it returns an {@link
   * Optional} containing the error message.
   */
  private Optional<String> validateEmail(String email) {
    EmailValidator validator = EmailValidator.getInstance();
    return validator.isValid(email)
      ? Optional.empty()
      : Optional.of("Invalid Email");
  }

  private Optional<String> validateLinkId(String linkId) {
    return (linkId.trim().length() == sLengthLinkId)
      ? Optional.empty()
      : Optional.of(
        "Invalid link ID: \"" + linkId + "\". Length must be " + sLengthLinkId + " characters");
  }

  /**
   * <p>Represents a parameter that needs to be check with a specific function.</p>
   * <p>It is composed by a {@link String} representing the key mapping the input value of a {@link
   * graphql.GraphQL} request and by a specific {@link Function} that checks the validity of the
   * value or the existence of the object referred to.</p>
   */
  private static class Parameter {

    private final String                             mKey;
    private final Function<String, Optional<String>> mController;

    private Parameter(
      String key,
      Function<String, Optional<String>> controller
    ) {
      mKey = key;
      mController = controller;
    }

    /**
     * @param key is a {@link String} representing the key of the input value of a {@link
     * graphql.GraphQL} request. This key will be used to extrapolate the value from the related
     * GraphQL environment.
     * @param controller is a {@link Function} that checks the value and returns an {@link Optional}
     * containing a {@link String} error message if the value or the related object is invalid.
     *
     * @return a {@link Parameter} object that represents a value that needs to be checked.
     */
    public static Parameter build(
      String key,
      Function<String, Optional<String>> controller
    ) {
      return new Parameter(key, controller);
    }

    /**
     * Executes the function that checks the value mapped by the key.
     *
     * @return an {@link Optional#empty()} if value or the referred object is valid, otherwise it
     * returns an {@link Optional} containing the {@link String} error message.
     */
    public Optional<String> check() {
      return mController.apply(mKey);
    }
  }
}
