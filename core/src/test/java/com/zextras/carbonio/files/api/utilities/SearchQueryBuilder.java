// SPDX-FileCopyrightText: 2023 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.api.utilities;

import com.zextras.carbonio.files.dal.dao.ebean.NodeType;
import com.zextras.carbonio.files.dal.repositories.impl.ebean.utilities.NodeSort;

public class SearchQueryBuilder {
  private StringBuilder query;

  public SearchQueryBuilder() {
    query = new StringBuilder("query { findNodes(");
  }

  public static SearchQueryBuilder aSearchQueryBuilder(){
    return new SearchQueryBuilder();
  }

  public SearchQueryBuilder withFlagged(boolean flagged) {
    query.append("flagged: ").append(flagged).append(", ");
    return this;
  }

  public SearchQueryBuilder withSharedByMe(boolean sharedByMe) {
    query.append("shared_by_me: ").append(sharedByMe).append(", ");
    return this;
  }

  public SearchQueryBuilder withSharedWithMe(boolean sharedWithMe) {
    query.append("shared_with_me: ").append(sharedWithMe).append(", ");
    return this;
  }

  public SearchQueryBuilder withDirectShare(boolean directShare) {
    query.append("direct_share: ").append(directShare).append(", ");
    return this;
  }

  public SearchQueryBuilder withFolderId(String folderId) {
    query.append("folder_id: \\\"").append(folderId).append("\\\", ");
    return this;
  }

  public SearchQueryBuilder withCascade(boolean cascade) {
    query.append("cascade: ").append(cascade).append(", ");
    return this;
  }

  public SearchQueryBuilder withSkip(int skip) {
    query.append("skip: ").append(skip).append(", ");
    return this;
  }

  public SearchQueryBuilder withLimit(int limit) {
    query.append("limit: ").append(limit).append(", ");
    return this;
  }

  public SearchQueryBuilder withSort(NodeSort sort) {
    query.append("sort: ").append(sort.toString()).append(", ");
    return this;
  }

  public SearchQueryBuilder withPageToken(String pageToken) {
    query.append("page_token: \\\"").append(pageToken).append("\\\", ");
    return this;
  }

  public SearchQueryBuilder withKeywords(String[] keywords) {
    query.append("keywords: [");
    for (String keyword : keywords) {
      query.append("\\\"").append(keyword).append("\\\", ");
    }
    query.append("], ");
    return this;
  }

  public SearchQueryBuilder withNodeType(NodeType nodeType) {
    query.append("type: ").append(nodeType.toString()).append(", ");
    return this;
  }

  public SearchQueryBuilder withOwnerId(String ownerId) {
    query.append("owner_id: \\\"").append(ownerId).append("\\\", ");
    return this;
  }

  public String build() {
    query.setLength(query.length() - 2);
    query.append(") { nodes { id name }, page_token } }");
    return query.toString();
  }
}
