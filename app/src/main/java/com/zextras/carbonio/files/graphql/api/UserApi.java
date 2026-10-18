// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.graphql.api;

import com.zextras.carbonio.files.dal.dao.UserInfo;
import com.zextras.carbonio.files.dal.repositories.interfaces.UserRepository;
import com.zextras.carbonio.files.graphql.auth.AuthenticatedUserProducer;
import com.zextras.carbonio.files.graphql.errors.FilesGraphQLErrors;
import com.zextras.carbonio.files.graphql.errors.FilesGraphQLException;
import com.zextras.carbonio.files.graphql.model.Account;
import com.zextras.carbonio.files.graphql.model.DistributionListModel;
import com.zextras.carbonio.files.graphql.model.NodeModel;
import com.zextras.carbonio.files.graphql.model.ShareModel;
import com.zextras.carbonio.files.graphql.model.SharedTarget;
import com.zextras.carbonio.files.graphql.model.UserModel;
import com.zextras.carbonio.files.graphql.support.NodeUserFieldSelection;
import com.zextras.carbonio.files.graphql.support.RequestUserCache;
import com.zextras.carbonio.files.graphql.validation.GraphQLInputValidator;
import graphql.schema.DataFetchingEnvironment;
import io.quarkus.security.Authenticated;
import io.smallrye.graphql.api.Context;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
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
  @Inject RequestUserCache userCache;
  @Inject AuthenticatedUserProducer producer;
  @Inject GraphQLInputValidator validator;
  @Inject Context gqlContext;

  private static final List<String> NODE_USER_FIELDS =
      List.of(
          NodeUserFieldSelection.CREATOR,
          NodeUserFieldSelection.OWNER,
          NodeUserFieldSelection.LAST_EDITOR);

  private static UserModel toModel(UserInfo user) {
    return new UserModel(user.getId().getUserId(), user.getEmail(), user.getFullName());
  }

  // ─── Batch @Source resolvers — Node.creator / owner / last_editor ─────────────

  @Name("creator")
  @NonNull
  public List<UserModel> creators(@Source List<NodeModel> nodes) {
    return resolveNodeUsers(nodes, NodeUserFieldSelection.CREATOR);
  }

  @Name("owner")
  public List<UserModel> owners(@Source List<NodeModel> nodes) {
    return resolveNodeUsers(nodes, NodeUserFieldSelection.OWNER);
  }

  @Name("last_editor")
  public List<UserModel> lastEditors(@Source List<NodeModel> nodes) {
    return resolveNodeUsers(nodes, NodeUserFieldSelection.LAST_EDITOR);
  }

  private List<UserModel> resolveNodeUsers(List<NodeModel> nodes, String field) {
    Set<String> fields = new HashSet<>(selectedSiblingUserFields());
    fields.add(field);
    fetchUsersById(
        nodes.stream()
            .flatMap(
                n -> NODE_USER_FIELDS.stream().filter(fields::contains).map(f -> nodeUserId(n, f)))
            .toList());
    return resolveEach(nodes.stream().map(n -> nodeUserId(n, field)).toList(), UserApi::toModel);
  }

  private static String nodeUserId(NodeModel node, String field) {
    return switch (field) {
      case NodeUserFieldSelection.CREATOR -> node.getCreatorId();
      case NodeUserFieldSelection.OWNER -> node.getOwnerId();
      default -> node.getLastEditorId();
    };
  }

  Set<String> selectedSiblingUserFields() {
    try {
      DataFetchingEnvironment env = gqlContext.unwrap(DataFetchingEnvironment.class);
      return NodeUserFieldSelection.of(
          env.getExecutionStepInfo().getParent().getField().getFields(), env.getFragmentsByName());
    } catch (RuntimeException e) {
      return Set.of();
    }
  }

  @SuppressWarnings({"unchecked", "rawtypes"})
  private <T> List<T> resolveEach(List<String> ids, Function<UserInfo, T> mapper) {
    Map<String, UserInfo> byId = fetchUsersById(ids);
    List out = new ArrayList<>(ids.size());
    for (String id : ids) {
      if (id == null) {
        out.add(null);
      } else if (byId.containsKey(id)) {
        out.add(mapper.apply(byId.get(id)));
      } else {
        // Exception element: fails only this dataloader key (a throw fails the whole batch).
        out.add(FilesGraphQLErrors.accountNotFound(id));
      }
    }
    return out;
  }

  private Map<String, UserInfo> fetchUsersById(List<String> ids) {
    Map<String, Optional<UserInfo>> cache = userCache.entries();
    List<String> distinctIds = ids.stream().filter(Objects::nonNull).distinct().toList();
    List<String> uncachedIds = distinctIds.stream().filter(id -> !cache.containsKey(id)).toList();
    for (List<String> chunk : partition(uncachedIds, 100)) {
      List<UserInfo> found = userRepository.getUsers(chunk);
      found.forEach(u -> cache.put(u.getId().getUserId(), Optional.of(u)));
      chunk.forEach(id -> cache.putIfAbsent(id, Optional.empty()));
    }
    Map<String, UserInfo> byId = new HashMap<>();
    distinctIds.forEach(id -> cache.get(id).ifPresent(u -> byId.put(id, u)));
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
    return resolveEach(
        shares.stream().map(ShareModel::getShareTargetId).toList(), u -> (SharedTarget) toModel(u));
  }

  @Name("users")
  @NonNull
  public List<UserModel> users(
      @Source DistributionListModel dl,
      @Name("limit") @NonNull int limit,
      @Name("cursor") String cursor)
      throws FilesGraphQLException {
    validator.checkPositiveLimit(limit).validate();
    return List.of();
  }

  @Query("getUserById")
  public UserModel getUserById(@Name("user_id") @Id @NonNull String userId)
      throws FilesGraphQLException {
    return userRepository
        .getUserById(producer.cookies(), userId)
        .map(UserApi::toModel)
        .orElseThrow(() -> FilesGraphQLErrors.accountNotFound(userId));
  }

  @Query("getAccountByEmail")
  public Account getAccountByEmail(@Name("email") @NonNull String email)
      throws FilesGraphQLException {
    validator.checkEmail(email).validate();
    return userRepository
        .getUserByEmail(producer.cookies(), email)
        .map(user -> (Account) toModel(user))
        .orElseThrow(() -> FilesGraphQLErrors.accountNotFound(email));
  }

  @Query("getAccountsByEmail")
  public @NonNull List<Account> getAccountsByEmail(
      @Name("emails") @NonNull List<@NonNull String> emails) throws FilesGraphQLException {
    validator.checkEmails(emails).validate();
    String cookies = producer.cookies();
    List<Account> result = new ArrayList<>();
    String firstMissing = null;
    for (String email : emails) {
      Account account =
          userRepository.getUserByEmail(cookies, email).map(u -> (Account) toModel(u)).orElse(null);
      if (account == null && firstMissing == null) {
        firstMissing = email;
      }
      result.add(account);
    }
    if (firstMissing != null) {
      throw FilesGraphQLErrors.accountNotFound(firstMissing).withPartialResults(result);
    }
    return result;
  }
}
