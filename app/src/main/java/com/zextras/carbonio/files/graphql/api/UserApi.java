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
import com.zextras.carbonio.files.graphql.model.DistributionListModel;
import com.zextras.carbonio.files.graphql.model.NodeModel;
import com.zextras.carbonio.files.graphql.model.ShareModel;
import com.zextras.carbonio.files.graphql.model.SharedTarget;
import com.zextras.carbonio.files.graphql.model.UserModel;
import com.zextras.carbonio.files.graphql.validation.GraphQLInputValidator;
import io.quarkus.security.Authenticated;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.eclipse.microprofile.graphql.GraphQLApi;
import org.eclipse.microprofile.graphql.Id;
import org.eclipse.microprofile.graphql.Name;
import org.eclipse.microprofile.graphql.NonNull;
import org.eclipse.microprofile.graphql.Query;
import org.eclipse.microprofile.graphql.Source;

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

  // ─── Batch @Source resolvers — Node.creator / owner / last_editor ─────────────

  @Name("creator")
  @NonNull
  public List<UserModel> creators(@Source List<NodeModel> nodes) {
    return resolveUsers(nodes.stream().map(NodeModel::getCreatorId).toList());
  }

  @Name("owner")
  public List<UserModel> owners(@Source List<NodeModel> nodes) {
    return resolveUsers(nodes.stream().map(NodeModel::getOwnerId).toList());
  }

  @Name("last_editor")
  public List<UserModel> lastEditors(@Source List<NodeModel> nodes) {
    return resolveUsers(nodes.stream().map(NodeModel::getLastEditorId).toList());
  }

  private List<UserModel> resolveUsers(List<String> ids) {
    Map<String, UserInfo> byId = fetchUsersById(ids);
    return ids.stream()
        .map(id -> id == null ? null : byId.get(id))
        .map(u -> u == null ? null : toModel(u))
        .toList();
  }

  private Map<String, UserInfo> fetchUsersById(List<String> ids) {
    Map<String, UserInfo> byId = new HashMap<>();
    for (List<String> chunk :
        partition(ids.stream().filter(Objects::nonNull).distinct().toList(), 100)) {
      for (UserInfo u : userRepository.getUsers(chunk)) {
        byId.put(u.getId().getUserId(), u);
      }
    }
    return byId;
  }

  static <T> List<List<T>> partition(List<T> list, int size) {
    List<List<T>> result = new ArrayList<>();
    for (int i = 0; i < list.size(); i += size) {
      result.add(list.subList(i, Math.min(i + size, list.size())));
    }
    return result;
  }

  // ─── Batch @Source resolver — Share.share_target ──────────────────────────────

  // Batched to coalesce per-share target lookups into one user-management call per request,
  // restoring the pre-Quarkus USER_BATCH_LOADER coalescing (renders the same schema field).
  @Name("share_target")
  public List<SharedTarget> shareTargets(@Source List<ShareModel> shares) {
    Map<String, UserInfo> byId =
        fetchUsersById(shares.stream().map(ShareModel::getShareTargetId).toList());
    return shares.stream()
        .map(share -> byId.get(share.getShareTargetId()))
        .map(u -> u == null ? null : (SharedTarget) toModel(u))
        .toList();
  }

  @Name("users")
  @NonNull
  public List<UserModel> users(
      @Source DistributionListModel dl,
      @Name("limit") @NonNull int limit,
      @Name("cursor") String cursor) {
    return List.of();
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
