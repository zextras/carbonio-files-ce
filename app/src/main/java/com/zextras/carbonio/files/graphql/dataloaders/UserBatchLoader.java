// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.graphql.dataloaders;

import com.zextras.carbonio.files.dal.dao.UserInfo;
import com.zextras.carbonio.files.dal.repositories.interfaces.UserRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.dataloader.MappedBatchLoader;

/**
 * Coalesces the per-field user lookups (a node's {@code owner}/{@code creator}/{@code last_editor}
 * and a share's target user) into a SINGLE batched user-management call per GraphQL request level,
 * instead of one REST round-trip per field. graphql-java collects the {@code owner}/{@code
 * creator}/… ids requested across all list items, de-duplicates them, and dispatches them here once
 * per level; {@link UserRepository#getUsers(List)} resolves them in one {@code POST
 * /internal/users} (deduplicating and serving from the user-management cache server-side too).
 *
 * <p>Runs SYNCHRONOUSLY on the request-scoped Vert.x worker thread (like {@link NodeBatchLoader}).
 * Unlike the DB batch loaders this touches NO {@code EntityManager} — user lookups are pure REST —
 * so there is no thread-affinity constraint here; keeping it inline is simply the least surprising
 * option and matches the other loaders.
 *
 * <p>A {@link org.dataloader.MappedBatchLoader} (returning a map keyed by user id) is used rather
 * than an index-aligned {@link org.dataloader.BatchLoader}: unknown ids are just absent from the
 * map, so DataLoader completes their {@code load()} with {@code null} and the data-fetcher renders
 * the account-not-found error — no fragile positional alignment. The user-management {@code
 * /internal/users} endpoint rejects lists longer than 100, matched by {@code
 * DataLoaderOptions.setMaxBatchSize(100)} where this loader is registered (see {@code
 * FilesGraphQLRoutes}); larger requests are split into 100-id sub-batches by DataLoader.
 */
@ApplicationScoped
public class UserBatchLoader implements MappedBatchLoader<String, UserInfo> {

  private final UserRepository userRepository;

  @Inject
  public UserBatchLoader(UserRepository userRepository) {
    this.userRepository = userRepository;
  }

  @Override
  public CompletionStage<Map<String, UserInfo>> load(Set<String> userIds) {
    List<UserInfo> users = userRepository.getUsers(new ArrayList<>(userIds));
    Map<String, UserInfo> usersById =
        users.stream()
            .collect(
                Collectors.toMap(
                    user -> user.getId().getUserId(),
                    Function.identity(),
                    (first, second) -> first));
    return CompletableFuture.completedFuture(usersById);
  }
}
