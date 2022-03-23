// SPDX-FileCopyrightText: 2022 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.dal.dao.ebean;

import com.zextras.carbonio.files.Files;
import java.io.Serializable;
import java.util.Objects;
import javax.persistence.Column;
import javax.persistence.Embeddable;

/**
 * <p>This class represents the primary key of the {@link Files.Db.Tables#NODE_CUSTOM_ATTRIBUTES}.
 * It is composed by two fields:
 *  <ul>
 *    <li>{@link Files.Db.NodeCustomAttributes#NODE_ID}: the foreign key of the node identifier</li>
 *    <li>{@link Files.Db.NodeCustomAttributes#USER_ID}: an {@link String} representing the user identifier</li>
 *  </ul>
 * </p>
 * <p>
 *   This class is necessary to specify the primary key for the {@link NodeCustomAttributes} and it is useful to performs
 *   queries containing joins between {@link Files.Db.Tables#NODE_CUSTOM_ATTRIBUTES} and {@link Files.Db.Tables#NODE}
 *   tables.
 * </p>
 */
@Embeddable
public class NodeCustomAttributesPK implements Serializable {

  @Column(name = Files.Db.NodeCustomAttributes.NODE_ID, nullable = false)
  private String mNodeId;

  @Column(name = Files.Db.NodeCustomAttributes.USER_ID, nullable = false)
  private String mUserId;

  public NodeCustomAttributesPK(
    String nodeId,
    String userId
  ) {
    mNodeId = nodeId;
    mUserId = userId;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }

    if (o == null || getClass() != o.getClass()) {
      return false;
    }

    NodeCustomAttributesPK that = (NodeCustomAttributesPK) o;
    return Objects.equals(mNodeId, that.mNodeId) && Objects.equals(mUserId, that.mUserId);
  }

  @Override
  public int hashCode() {
    return Objects.hash(mNodeId, mUserId);
  }

  String getNodeId() {
    return mNodeId;
  }

  String getUserId() {
    return mUserId;
  }
}