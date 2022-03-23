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
 * <p>This class represents the primary key of the {@link Files.Db.Tables#SHARE}. It is composed by
 * two fields:
 *  <ul>
 *    <li>{@link Files.Db.Share#NODE_ID}: the foreign key of the node identifier</li>
 *    <li>{@link Files.Db.Share#SHARE_TARGET_UUID}: a {@link String} of user uuid with whom the share was created.</li>
 *  </ul>
 * </p>
 * <p>This class is necessary to specify the primary key for the {@link Share}.</p>
 */
@Embeddable
public class SharePK implements Serializable {

  @Column(name = Files.Db.Share.NODE_ID, length = 36, nullable = false)
  private String mNodeId;

  @Column(name = Files.Db.Share.SHARE_TARGET_UUID, length = 256, nullable = false)
  private String mTargetUserId;

  /**
   * Creates a primary key for the {@link Share} entity. This is composed by:
   *
   * @param nodeId is a {@link String} of the node id.
   * @param targetUserId is a {@link String} of the user id which the node is shared with.
   */
  public SharePK(
    String nodeId,
    String targetUserId
  ) {
    mNodeId = nodeId;
    mTargetUserId = targetUserId;
  }

  public String getNodeId() {
    return mNodeId;
  }

  public String getTargetUserId() {
    return mTargetUserId;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }

    if (o == null || getClass() != o.getClass()) {
      return false;
    }

    SharePK that = (SharePK) o;
    return Objects.equals(mNodeId, that.mNodeId)
      && Objects.equals(mTargetUserId, that.mTargetUserId);
  }

  @Override
  public int hashCode() {
    return Objects.hash(mNodeId, mTargetUserId);
  }


}
