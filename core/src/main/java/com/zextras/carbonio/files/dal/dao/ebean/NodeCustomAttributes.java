// SPDX-FileCopyrightText: 2022 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.dal.dao.ebean;

import com.zextras.carbonio.files.Constants;
import io.ebean.Model;
import javax.persistence.Column;
import javax.persistence.EmbeddedId;
import javax.persistence.Entity;
import javax.persistence.JoinColumn;
import javax.persistence.ManyToOne;
import javax.persistence.Table;

/**
 * Represents an Ebean {@link NodeCustomAttributes} entity that matches a record of the {@link
 * Constants.Db.Tables#NODE_CUSTOM_ATTRIBUTES} table.
 *
 * <p>The implementation of constructors and setters should not care to check if the values in input
 * are valid or not because, when these methods are called, these controls <strong>must</strong> be
 * already done.
 */
@Entity
@Table(name = Constants.Db.Tables.NODE_CUSTOM_ATTRIBUTES)
public class NodeCustomAttributes extends Model {

  @EmbeddedId private NodeCustomAttributesPK mCompositeId;

  @Column(name = Constants.Db.NodeCustomAttributes.USER_ID, nullable = false)
  private String mUserId;

  @Column(name = Constants.Db.NodeCustomAttributes.NODE_ID, nullable = false)
  private String mNodeId;

  @Column(name = Constants.Db.NodeCustomAttributes.FLAG, nullable = false)
  private Boolean mFlag;

  @Column(name = Constants.Db.NodeCustomAttributes.COLOR)
  private Short mColor;

  @Column(name = Constants.Db.NodeCustomAttributes.EXTRA, nullable = false)
  private String mExtra;

  @ManyToOne
  @JoinColumn(
      name = Constants.Db.NodeCustomAttributes.NODE_ID,
      referencedColumnName = Constants.Db.Node.ID,
      insertable = false,
      updatable = false)
  private Node node;

  public NodeCustomAttributes(String nodeId, String userId, boolean flag) {
    mCompositeId = new NodeCustomAttributesPK(nodeId, userId);
    mFlag = flag;
    mColor = null;
    mExtra = "";
  }

  public String getNodeId() {
    return mCompositeId.getNodeId();
  }

  public String getUserId() {
    return mCompositeId.getUserId();
  }

  public boolean getFlag() {
    return mFlag;
  }

  public NodeCustomAttributes setFlag(boolean flag) {
    mFlag = flag;
    update();
    return this;
  }

  public Short getColor() {
    return mColor;
  }

  public String getExtra() {
    return mExtra;
  }
}
