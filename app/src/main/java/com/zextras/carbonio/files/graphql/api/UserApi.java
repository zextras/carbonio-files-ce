// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.graphql.api;

import com.zextras.carbonio.files.dal.dao.UserInfo;
import com.zextras.carbonio.files.dal.dao.UserMyself;
import com.zextras.carbonio.files.dal.repositories.interfaces.UserRepository;
import com.zextras.carbonio.files.graphql.auth.AuthenticatedUser;
import com.zextras.carbonio.files.graphql.auth.AuthenticatedUserProducer;
import com.zextras.carbonio.files.graphql.errors.ErrorCodes;
import com.zextras.carbonio.files.graphql.errors.FilesGraphQLException;
import com.zextras.carbonio.files.graphql.model.Account;
import com.zextras.carbonio.files.graphql.model.UserModel;
import com.zextras.carbonio.files.graphql.validation.GraphQLInputValidator;
import io.quarkus.security.Authenticated;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.List;
import org.eclipse.microprofile.graphql.GraphQLApi;
import org.eclipse.microprofile.graphql.Id;
import org.eclipse.microprofile.graphql.Name;
import org.eclipse.microprofile.graphql.NonNull;
import org.eclipse.microprofile.graphql.Query;

@GraphQLApi
@Authenticated
public class UserApi {

  @Inject UserRepository userRepository;
  @Inject AuthenticatedUserProducer producer;
  @Inject GraphQLInputValidator validator;
  @Inject @AuthenticatedUser UserMyself requester;

  private static UserModel toModel(UserInfo user) {
    return new UserModel(user.getId().getUserId(), user.getEmail(), user.getFullName());
  }

  @Query("getUserById")
  public UserModel getUserById(@Name("user_id") @Id @NonNull String userId)
      throws FilesGraphQLException {
    return userRepository
        .getUserById(producer.cookies(), userId)
        .map(UserApi::toModel)
        .orElseThrow(
            () -> FilesGraphQLException.of(ErrorCodes.ACCOUNT_NOT_FOUND, "userId", userId));
  }

  @Query("getAccountByEmail")
  public Account getAccountByEmail(@Name("email") @NonNull String email)
      throws FilesGraphQLException {
    validator.checkEmail(email).validate();
    return userRepository
        .getUserByEmail(producer.cookies(), email)
        .map(user -> (Account) toModel(user))
        .orElseThrow(() -> FilesGraphQLException.of(ErrorCodes.ACCOUNT_NOT_FOUND, "email", email));
  }

  @Query("getAccountsByEmail")
  public @NonNull List<Account> getAccountsByEmail(
      @Name("emails") @NonNull List<@NonNull String> emails) throws FilesGraphQLException {
    String cookies = producer.cookies();
    List<Account> result = new ArrayList<>();
    for (String email : emails) {
      validator.checkEmail(email).validate();
      Account account =
          userRepository.getUserByEmail(cookies, email).map(u -> (Account) toModel(u)).orElse(null);
      result.add(account);
    }
    return result;
  }
}
