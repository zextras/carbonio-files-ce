// SPDX-FileCopyrightText: 2022 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.graphql.datafetchers;

import com.google.inject.Inject;
import com.zextras.carbonio.files.Files;
import com.zextras.carbonio.files.Files.GraphQL.InputParameters.GetAccountsByEmail;
import com.zextras.carbonio.files.Files.GraphQL.InputParameters.GetUser;
import com.zextras.carbonio.files.dal.dao.User;
import com.zextras.carbonio.files.dal.repositories.interfaces.UserRepository;
import com.zextras.carbonio.files.graphql.GraphQLProvider;
import com.zextras.carbonio.files.graphql.errors.GraphQLResultErrors;
import graphql.execution.AbortExecutionException;
import graphql.execution.DataFetcherResult;
import graphql.execution.ResultPath;
import graphql.schema.DataFetcher;
import graphql.schema.GraphQLObjectType;
import graphql.schema.TypeResolver;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * Contains all the implementations of the {@link DataFetcher} for all the queries and mutations
 * related to the type User defined in the GraphQL schema.
 * <p>
 * The implementation of each {@link DataFetcher} is asynchronous and returns an {@link HashMap}
 * containing the data fetched from the database. Each key of the resulting map must match the
 * attribute of the type User defined in the GraphQL schema.
 * <p>
 * These {@link DataFetcher}s will be used in the {@link GraphQLProvider} where they are bound with
 * the related queries, mutations and the composed attributes.
 * <p>
 * <strong>GraphQL behaviour:</strong> When a {@link DataFetcher} returns an empty {@link Map} the
 * GraphQL library:
 * <ul>
 *   <li>
 *     returns an error, if the related attribute was defined not <code>null</code> in the schema,
 *     because it cannot find the mandatory attributes related to the User inside the {@link Map}
 *   </li>
 *   <li>
 *     associates the <code>null</code> to the attribute specified if it was defined that can be
 *     <code>null</code> in the schema
 *   </li>
 * </ul>
 */
public class UserDataFetcher {

  private UserRepository userRepository;


  @Inject
  public UserDataFetcher(UserRepository userRepository) {
    this.userRepository = userRepository;
  }

  private DataFetcherResult<Map<String, Object>> fetchUserByIdAndConvertToDataFetcherResult(
    String cookies,
    String userId,
    ResultPath path
  ) {
    return userRepository
      .getUserById(cookies, UUID.fromString(userId))
      .map(this::convertUserToDataFetcherResult)
      .orElse(
        new DataFetcherResult.Builder<Map<String, Object>>()
          .error(GraphQLResultErrors.accountNotFound(userId, path))
          .build());
  }

  private DataFetcherResult<Map<String, Object>> fetchUserByEmailAndConvertToDataFetcherResult(
    String cookies,
    String email,
    ResultPath path
  ) {
    return userRepository
      .getUserByEmail(cookies, email)
      .map(this::convertUserToDataFetcherResult)
      .orElse(new DataFetcherResult
        .Builder<Map<String, Object>>()
        .error(GraphQLResultErrors.accountNotFound(email, path))
        .build()
      );
  }

  private DataFetcherResult<Map<String, Object>> convertUserToDataFetcherResult(User user) {
    Map<String, Object> result = new HashMap<>();
    result.put(Files.GraphQL.User.ID, user.getUuid());
    result.put(Files.GraphQL.User.EMAIL, user.getEmail());
    result.put(Files.GraphQL.User.FULL_NAME, user.getFullName());
    result.put(Files.GraphQL.ENTITY_TYPE, Files.GraphQL.Types.USER);

    return new DataFetcherResult
      .Builder<Map<String, Object>>()
      .data(result)
      .build();
  }

  /**
   * This {@link DataFetcher} must be used to fetch a user.
   * <p>
   * In particular:
   * <ul>
   *  <li>
   *    It fetches the user by the id specified in the GraphQL request.
   *  </li>
   *  <li>
   *    If not present it tries to extrapolates the node id from the GraphQL localContext
   *    created by the previous {@link DataFetcher}, retrieving the specific key from the schema field requested.
   *  </li>
   *  <li>It fetches the {@link User}</li>
   *  <li>
   *    It converts the {@link User} to a {@link HashMap} containing all the GraphQL attributes of the
   *    User defined in the schema. If the user does not exist the {@link HashMap} reference will be <code>null</code>
   *  </li>
   * </ul>
   *
   * @return an asynchronous {@link DataFetcher} containing a {@link Map} of all the attributes
   * values of the user.
   */
  public DataFetcher<CompletableFuture<DataFetcherResult<Map<String, Object>>>> getUserFetcher() {
    return (environment) -> CompletableFuture.supplyAsync(
      () -> {
        String userId = Optional
          .ofNullable((String) environment.getArgument(GetUser.USER_ID))
          .orElseGet(() -> Optional
            .ofNullable(environment.getLocalContext())
            .map(context -> ((Map<String, String>) context).get(environment.getField().getName()))
            .orElse(null));
        return Optional
          .ofNullable(userId).map(uId -> fetchUserByIdAndConvertToDataFetcherResult(
            environment.getGraphQlContext().get(Files.GraphQL.Context.COOKIES), userId,
            environment.getExecutionStepInfo().getPath()))
          .orElseGet(() -> new DataFetcherResult.Builder<Map<String, Object>>().build());
      });
  }

  /**
   * <p>This {@link DataFetcher} must be used to fetch the Users of a specific distribution list.
   * It works only if it is used to resolve attributes that accepts the following parameters in
   * input:
   * <ul>
   *   <li>
   *     {@link Files.GraphQL.InputParameters#LIMIT}: an {@link Integer} of how many elements you want to fetch
   *   </li>
   *   <li>
   *     {@link Files.GraphQL.InputParameters#CURSOR}: a {@link String} of the last element fetched (this is optional,
   *     and it is useful for pagination)
   *   </li>
   * </ul>
   * </p>
   * In particular:
   * <ul>
   *  <li>It fetches all the user ids of the distribution list from zimbra</li>
   *  <li>
   *    It filters only the interested users applying the {@link Files.GraphQL.InputParameters#LIMIT} and the
   *    {@link Files.GraphQL.InputParameters#CURSOR} parameters
   *  </li>
   *  <li>
   *    It fetches all the interested users, and it converts each of them into a {@link HashMap} containing all the
   *    GraphQL attributes of the User defined in the schema.
   *  </li>
   *  <li>
   *    It adds all the users into a {@link List} that can be empty if the distribution list doesn't have any users.
   *    It will also add all the errors on retrieving the users of the distribution list in the relative field to be
   *    returned on the response to allow to manage it by who called it.
   *  </li>
   * </ul>
   *
   * @return an asynchronous {@link DataFetcher} containing a {@link List} of all users of the
   * distribution list.
   */
  public DataFetcher<CompletableFuture<DataFetcherResult<List<Map<String, Object>>>>> getDLUsersFetcher() {
    return environment -> CompletableFuture.supplyAsync(() -> {
        Map<String, Object> partialResult = environment.getSource();
        String distributionListId = (String) partialResult.get(Files.GraphQL.Share.SHARE_TARGET);

        /*
         * If the execution is arrived in this data fetcher then the partialResult contains the distributionListId
         * otherwise the execution would have stopped in the previous data fetcher.
         * This is only a double check if something goes wrong or if this data fetcher is
         * used improperly.
         */
        if (distributionListId == null) {
          return new DataFetcherResult.Builder<List<Map<String, Object>>>()
            .error(GraphQLResultErrors.missingField(environment.getExecutionStepInfo().getPath()))
            .build();
        }

        // For now the DistributionList are not supported
        return new DataFetcherResult
          .Builder<List<Map<String, Object>>>()
          .data(new ArrayList<>())
          .build();
      }
    );
  }

  /**
   * This {@link TypeResolver} checks which type of Shared_entity was requested. The type can be a
   * User or a Distribution List.
   *
   * @return a {@link TypeResolver} that resolve the type of Shared_entity requested.
   */
  public TypeResolver getAccountTypeResolver() {
    return environment ->
    {
      Map<String, Object> result = environment.getObject();
      return (result.get(Files.GraphQL.ENTITY_TYPE).equals(Files.GraphQL.Types.DISTRIBUTION_LIST))
        ? (GraphQLObjectType) environment.getSchema().getType(Files.GraphQL.Types.DISTRIBUTION_LIST)
        : (GraphQLObjectType) environment.getSchema().getType(Files.GraphQL.Types.USER);
    };
  }


  /**
   * <p>This {@link DataFetcher} retrieves a target user of a sharing node and it creates the
   * related {@link Map}.</p>
   * <p>It <strong>must</strong> be bound to a Share query and used only to retrieve a target user
   * that represents the attribute {@link Files.GraphQL.Share#SHARE_TARGET} in a GraphQL Share
   * object. It works only if the previous data fetcher saves the
   * {@link Files.GraphQL.Share#SHARE_TARGET} in the context, otherwise the execution will be
   * aborted with an {@link AbortExecutionException}.</p>
   *
   * @return an asynchronous {@link DataFetcher} containing a {@link Map} of all the attributes
   * values of a target user.
   */
  public DataFetcher<CompletableFuture<DataFetcherResult<Map<String, Object>>>> shareTargetUserFetcher() {
    return environment -> CompletableFuture.supplyAsync(() ->
    {
      String userId = ((Map<String, String>) environment.getLocalContext()).get(
        Files.GraphQL.Share.SHARE_TARGET
      );

      return fetchUserByIdAndConvertToDataFetcherResult(
        environment.getGraphQlContext().get(Files.GraphQL.Context.COOKIES),
        userId,
        environment.getExecutionStepInfo().getPath()
      );
    });
  }

  /**
   * This {@link DataFetcher} must be used to fetch an account by email.
   * <p>
   * In particular:
   * <ul>
   *  <li>
   *    It fetches the account by the email specified in the GraphQL request.
   *  </li>
   *  <li>It fetches the {@link User} by email</li>
   *  <li>
   *    It converts the {@link User} to a {@link HashMap} containing all the GraphQL attributes of the
   *    Account defined in the schema. If the account does not exist the {@link HashMap} reference will be <code>null</code>
   *  </li>
   * </ul>
   *
   * @return an asynchronous {@link DataFetcher} containing a {@link Map} of all the attributes
   * values of the account.
   */
  public DataFetcher<CompletableFuture<DataFetcherResult<Map<String, Object>>>> getAccountByEmailFetcher() {
    return environment -> CompletableFuture.supplyAsync(() ->
    {
      String email = environment.getArgument(GetUser.EMAIL);
      return fetchUserByEmailAndConvertToDataFetcherResult(
        environment.getGraphQlContext().get(Files.GraphQL.Context.COOKIES),
        email,
        environment.getExecutionStepInfo().getPath()
      );
    });
  }

  /**
   * This {@link DataFetcher} fetches {@link User} accounts given their email and it converts each
   * {@link User} to a {@link HashMap} containing all the GraphQL attributes of the acccount defined
   * in the schema. If the account does not exist the {@link HashMap} reference will be
   * <code>null</code>
   *
   * @return an asynchronous {@link DataFetcher} containing a {@link List} of {@link Map}. Each
   * {@link Map} contains  all the attributes values of the requested account.
   */
  public DataFetcher<CompletableFuture<List<DataFetcherResult<Map<String, Object>>>>> getAccountsByEmailFetcher() {
    return environment -> CompletableFuture.supplyAsync(() -> {
      ResultPath path = environment.getExecutionStepInfo().getPath();
      List<String> accountEmails = environment.getArgument(GetAccountsByEmail.EMAILS);
      String requesterCookie = environment.getGraphQlContext().get(Files.GraphQL.Context.COOKIES);

      return accountEmails
        .stream()
        .map(email -> fetchUserByEmailAndConvertToDataFetcherResult(requesterCookie, email, path))
        .collect(Collectors.toList());
    });
  }
}
