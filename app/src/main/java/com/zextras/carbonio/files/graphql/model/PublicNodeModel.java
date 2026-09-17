// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.graphql.model;

import org.eclipse.microprofile.graphql.Id;
import org.eclipse.microprofile.graphql.Interface;
import org.eclipse.microprofile.graphql.Name;
import org.eclipse.microprofile.graphql.NonNull;

@Interface("PublicNode")
public interface PublicNodeModel {

  @Id
  @NonNull
  String getId();

  @Name("created_at")
  long getCreatedAt();

  @Name("updated_at")
  long getUpdatedAt();

  @NonNull
  String getName();

  @NonNull
  NodeType getType();
}
