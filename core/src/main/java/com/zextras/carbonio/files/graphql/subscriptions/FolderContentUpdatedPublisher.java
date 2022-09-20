// SPDX-FileCopyrightText: 2022 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.graphql.subscriptions;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.zextras.carbonio.files.Files.GraphQL.FileVersion;
import com.zextras.carbonio.files.Files.GraphQL.Node;
import com.zextras.carbonio.files.dal.dao.ebean.NodeType;
import com.zextras.carbonio.files.graphql.types.EventType;
import com.zextras.carbonio.files.graphql.types.NodeEvent;
import com.zextras.carbonio.files.graphql.types.NodeEvent.NodeEventType;
import graphql.execution.DataFetcherResult;
import io.reactivex.rxjava3.core.Flowable;
import java.util.Map;

@Singleton
public class FolderContentUpdatedPublisher extends AbstractEventPublisher<NodeEvent> {

  @Inject
  public FolderContentUpdatedPublisher() {
    super(EventType.NODE_EVENT);
  }

  public void sendEvent(
    DataFetcherResult<Map<String, Object>> result,
    NodeEventType nodeEventType
  ) {

    if (!result.hasErrors() && canBeConverted(result.getData())) {
      sendEvent(new NodeEvent(nodeEventType, result.getData()));
    }
  }

  public Flowable<NodeEvent> getPublisher(
    String parentId,
    String requesterId
  ) {
    return parentId.equals("LOCAL_ROOT")
      ? getPublisher()
      .filter(nodeEvent -> parentId.equals(nodeEvent.getParentId()))
      .filter(nodeEvent -> nodeEvent.getOwner().equals(requesterId))
      : getPublisher().filter(nodeEvent -> parentId.equals(nodeEvent.getParentId()));
  }

  /**
   * This utility method is temporary since we need to study if it is doable to migrate from
   * Map<String, Object> to concrete java graphql pojo.
   *
   * @param mapToConvert
   * @return
   */
  private boolean canBeConverted(Map<String, Object> mapToConvert) {
    boolean canBeConverted = mapToConvert.containsKey(Node.ID)
      && mapToConvert.containsKey(Node.CREATED_AT)
      && mapToConvert.containsKey(Node.UPDATED_AT)
      && mapToConvert.containsKey(Node.NAME)
      && mapToConvert.containsKey(Node.DESCRIPTION)
      && mapToConvert.containsKey(Node.TYPE)
      && mapToConvert.containsKey(Node.FLAGGED)
      && mapToConvert.containsKey(Node.ROOT_ID)
      && mapToConvert.containsKey("parent_id")
      && mapToConvert.containsKey("owner_id");

    NodeType type = NodeType.valueOf((String) mapToConvert.get(Node.TYPE));

    if (!type.equals(NodeType.FOLDER) && !type.equals(NodeType.ROOT)) {
      canBeConverted = canBeConverted
        && mapToConvert.containsKey(Node.EXTENSION)
        && mapToConvert.containsKey(FileVersion.VERSION)
        && mapToConvert.containsKey(FileVersion.MIME_TYPE)
        && mapToConvert.containsKey(FileVersion.SIZE)
        && mapToConvert.containsKey(FileVersion.KEEP_FOREVER)
        && mapToConvert.containsKey(FileVersion.DIGEST);
    }

    return canBeConverted;
  }
}
