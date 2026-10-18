// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.graphql.support;

import com.zextras.carbonio.files.graphql.model.NodeModel;
import graphql.GraphQLContext;
import graphql.schema.DataFetchingEnvironment;
import io.smallrye.graphql.api.Context;
import io.smallrye.graphql.spi.EventingService;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class RequestedNodeUsers implements EventingService {

  public static final String CREATOR = "creator";
  public static final String OWNER = "owner";
  public static final String LAST_EDITOR = "last_editor";

  private static final String KEY = "files.requested.node.user.ids";

  @Override
  public String getConfigKey() {
    return null;
  }

  // Fired once per batch key before it is queued, so the ids are known before the batch runs.
  @Override
  public void beforeDataFetch(Context context) {
    DataFetchingEnvironment env = context.unwrap(DataFetchingEnvironment.class);
    if (env.getSource() instanceof NodeModel node) {
      String userId = userId(node, context.getFieldName());
      if (userId != null) {
        of(env.getGraphQlContext()).add(userId);
      }
    }
  }

  public static String userId(NodeModel node, String field) {
    return switch (field) {
      case CREATOR -> node.getCreatorId();
      case OWNER -> node.getOwnerId();
      case LAST_EDITOR -> node.getLastEditorId();
      default -> null;
    };
  }

  public static Set<String> of(GraphQLContext context) {
    return context.computeIfAbsent(KEY, key -> ConcurrentHashMap.newKeySet());
  }
}
