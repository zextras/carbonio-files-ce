// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.graphql.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.zextras.carbonio.files.graphql.model.FolderModel;
import com.zextras.carbonio.files.graphql.model.NodeType;
import com.zextras.carbonio.files.graphql.model.ShareModel;
import com.zextras.carbonio.files.graphql.model.SharePermission;
import graphql.GraphQLContext;
import graphql.schema.DataFetchingEnvironment;
import io.smallrye.graphql.api.Context;
import org.junit.jupiter.api.Test;

class RequestedNodeUsersTest {

  private final RequestedNodeUsers service = new RequestedNodeUsers();
  private final GraphQLContext graphQLContext = GraphQLContext.newContext().build();

  private static final FolderModel NODE =
      new FolderModel(
          "n1", "p1", "owner", "creator", null, 0L, 0L, "name", "", NodeType.FOLDER, false, "r1");

  private void fetch(Object source, String field) {
    DataFetchingEnvironment env = mock(DataFetchingEnvironment.class);
    when(env.getSource()).thenReturn(source);
    when(env.getGraphQlContext()).thenReturn(graphQLContext);
    Context context = mock(Context.class);
    when(context.unwrap(DataFetchingEnvironment.class)).thenReturn(env);
    when(context.getFieldName()).thenReturn(field);
    service.beforeDataFetch(context);
  }

  @Test
  void recordsTheUserOfEachFetchedNodeUserField() {
    fetch(NODE, "owner");
    fetch(NODE, "creator");

    assertThat(RequestedNodeUsers.of(graphQLContext)).containsExactlyInAnyOrder("owner", "creator");
  }

  @Test
  void ignoresUnsetUsersOtherFieldsAndOtherSources() {
    fetch(NODE, "last_editor");
    fetch(NODE, "name");
    fetch(new ShareModel(0L, SharePermission.READ_ONLY, null, "n1", "target"), "share_target");

    assertThat(RequestedNodeUsers.of(graphQLContext)).isEmpty();
  }
}
