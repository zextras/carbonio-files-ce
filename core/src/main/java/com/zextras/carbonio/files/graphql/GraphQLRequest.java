// SPDX-FileCopyrightText: 2022 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.graphql;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * This class allows to create a correct GraphQL request. Its main purpose is to validate the
 * payload in input and extracts every necessary attributes (see {@link
 * GraphQLRequest#buildFromPayload(String)}). But it can be also used to build a GraphQL request
 * passing the attributes manually.
 */
public class GraphQLRequest {

  private static final String GRAPHQL_FIELD_REQUEST        = "query";
  private static final String GRAPHQL_FIELD_VARIABLES      = "variables";
  private static final String GRAPHQL_FIELD_OPERATION_NAME = "operationName";

  private final String              requestType;
  private final String              request;
  private final Optional<String>    operationName;
  private final Map<String, Object> variables;

  public GraphQLRequest(
    String requestType,
    String request
  ) {
    this(
      requestType,
      request,
      Optional.empty(),
      new HashMap<>()
    );
  }

  /**
   * Constructor of the {@link GraphQLRequest}.
   *
   * @param requestType a {@link String} representing the type of the request (see {@link
   * GraphQLRequest.RequestTypes}
   * @param request a {@link String} in a JSON format containing the request
   * @param operationName an {@link Optional<String>} of the operation name
   * @param variables an {@link Map<String,Object>} containing all the values of the input variables
   * specified
   */
  public GraphQLRequest(
    String requestType,
    String request,
    Optional<String> operationName,
    Map<String, Object> variables
  ) {
    this.requestType = requestType;
    this.request = request;
    this.operationName = operationName;
    this.variables = variables;
  }

  /**
   * This static method builds a {@link GraphQLRequest} from a payload.
   * <p>
   * This is an example of a complete payload:
   * <pre>
   *  {
   *    "query": "query node($id: String!) { name }",
   *    "operationName": "node",
   *    "variables": "{ \"id\": \"test-id\" }"
   *  }
   * </pre>
   * where the <code>query</code> must be always present, but the <code>operationName</code> and
   * the
   * <code>variables</code> can be both optional or only one of the two.
   *
   * <h2>Execution step by step</h2>
   * <ul>
   *   <li>
   *     It builds a {@link Map} from the payload string.
   *   </li>
   *   <li>
   *     It Extracts the type and the json of the request from the payload. If the payload doesn't contain a correct
   *     request type or has an empty body it throws an {@link InvalidPayloadRequestError}.
   *   </li>
   *   <li>
   *     It Checks if the operation name is specified into the body of the request, if there is an operation name
   *     it will be extracted.
   *   </li>
   *   <li>
   *     It Checks if the set of variables is specified into the body of the request, if there is a set of variables
   *     it will be extracted.
   *   </li>
   * </ul>
   *
   * @param payloadString string that contain the json request
   *
   * @return {@link GraphQLRequest}
   * @throws {@link InvalidPayloadRequestError} This exception will be thrown if the payload doesn't
   *                contain a correct request type or has an empty body
   */
  public static final GraphQLRequest buildFromPayload(String payloadString)
    throws InvalidPayloadRequestError {

    payloadString = payloadString.replace("\\n", "").replace("\\r", "");
    ObjectMapper mapper = new ObjectMapper();

    Map<String, Object> payloadMap = new HashMap<>();

    try {
      payloadMap = mapper.readValue(payloadString, Map.class);
    } catch (IOException exception) {
      throw new InvalidPayloadRequestError("Unable to encode a Graphql payload into a Map object");
    }

    if (payloadMap.get(GRAPHQL_FIELD_REQUEST) == null) {
      throw new InvalidPayloadRequestError("The GraphQL request cannot be empty");
    }

    // Extract the request json
    String request = (String) payloadMap.get(GRAPHQL_FIELD_REQUEST);

    // Pre-conditions: each request can have only one request type
    Optional<String> requestType = Arrays.stream(RequestTypes.values())
      .map((type) -> type.name().toLowerCase())
      .filter((type) -> request.matches("\\s*" + type + "(.*)"))
      .findFirst();

    if (!requestType.isPresent()) {
      throw new InvalidPayloadRequestError(
        "The GraphQL request has a wrong request type (query, mutation, subscription)");
    }

    // Extract the operationName if exists
    Optional<String> operationName =
      Optional.ofNullable((String) payloadMap.get(GRAPHQL_FIELD_OPERATION_NAME));

    Map<String, Object> variablesMap =
      (Map<String, Object>) payloadMap.getOrDefault(GRAPHQL_FIELD_VARIABLES, new HashMap<>());

    return new GraphQLRequest(requestType.get(), request, operationName, variablesMap);
  }

  public final String getRequestType() {
    return requestType;
  }

  public final String getRequest() {
    return request;
  }

  public final Optional<String> getOperationName() {
    return operationName;
  }

  public final Map<String, Object> getVariables() {
    return variables;
  }

  /**
   * This enum contains all the types that a GraphQL request can handle.
   * <ul>
   *   <li><strong>QUERY</strong>: represents a read-only request</li>
   *   <li>
   *     <strong>MUTATION</strong>: represents a write request but returns
   *     also the specified attributes of the object modified.
   *   </li>
   *   <li>
   *     <strong>SUBSCRIPTION</strong>: like the QUERY but the result is sent back to the client
   *     every time a particular event happens on the server
   *   </li>
   * </ul>
   */
  private enum RequestTypes {
    QUERY,
    MUTATION,
    SUBSCRIPTION
  }

  public static class InvalidPayloadRequestError extends RuntimeException {

    public InvalidPayloadRequestError(String errorMessage) {
      super(errorMessage);
    }
  }
}
