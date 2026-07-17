// SPDX-FileCopyrightText: 2022 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.dal.dao.ebean;

import com.zextras.carbonio.files.Constants;
import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * <p>Represents an Ebean {@link NodeCustomAttributes} entity that matches a record of the
 * {@link Constants.Db.Tables#NODE_CUSTOM_ATTRIBUTES} table.</p>
 * <p>The implementation of constructors and setters should not care to check if the values in
 * input
 * are valid or not because, when these methods are called, these controls <strong>must</strong> be
 * already done.</p>
 */
@Entity
@Table(name = Constants.Db.Tables.NODE_CUSTOM_ATTRIBUTES)
public class NodeCustomAttributes {

  /** Protected no-arg constructor required by Hibernate/JPA. */
  protected NodeCustomAttributes() {}

  @EmbeddedId
  private NodeCustomAttributesPK mCompositeId;

  @Column(name = Constants.Db.NodeCustomAttributes.USER_ID, nullable = false, insertable = false, updatable = false)
  private String mUserId;

  @Column(name = Constants.Db.NodeCustomAttributes.NODE_ID, nullable = false, insertable = false, updatable = false)
  private String mNodeId;

  @Column(name = Constants.Db.NodeCustomAttributes.FLAG, nullable = false)
  private Boolean mFlag;

  @Column(name = Constants.Db.NodeCustomAttributes.COLOR)
  private Short mColor;

  @Column(name = Constants.Db.NodeCustomAttributes.EXTRA, nullable = false)
  private String mExtra;

  @ManyToOne
  @JoinColumn(name = Constants.Db.NodeCustomAttributes.NODE_ID, referencedColumnName = Constants.Db.Node.ID, insertable = false, updatable = false)
  private Node node;

  public NodeCustomAttributes(
    String nodeId,
    String userId,
    boolean flag
  ) {
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
    // TODO(P2c): persistence handled by repository
    return this;
  }

  public Short getColor() {
    return mColor;
  }

  public String getExtra() {
    return mExtra;
  }
}
