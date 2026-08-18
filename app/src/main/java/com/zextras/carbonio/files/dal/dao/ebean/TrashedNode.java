// SPDX-FileCopyrightText: 2022 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.dal.dao.ebean;

import com.zextras.carbonio.files.Constants;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;

/**
 * Represents an Ebean {@link TrashedNode} entity that matches a record of the {@link
 * Constants.Db.Tables#TRASHED_NODE} table.
 *
 * <p>The implementation of constructors and setters should not care to check if the values in input
 * are valid or not because, when these methods are called, these controls <strong>must</strong> be
 * already done.
 */
@Entity
@Table(name = Constants.Db.Tables.TRASHED_NODE)
public class TrashedNode {

  /** Protected no-arg constructor required by Hibernate/JPA. */
  protected TrashedNode() {}

  @Id
  @Column(name = Constants.Db.Trashed.NODE_ID, nullable = false, length = 36)
  private String mNodeId;

  @Column(name = Constants.Db.Trashed.PARENT_ID, nullable = false, length = 36)
  private String mOldParentId;

  @OneToOne
  @JoinColumn(
      name = Constants.Db.Trashed.NODE_ID,
      referencedColumnName = Constants.Db.Node.ID,
      insertable = false,
      updatable = false)
  private Node node;

  public TrashedNode(String nodeId, String parentId) {
    mNodeId = nodeId;
    mOldParentId = parentId;
  }

  public String getId() {
    return mNodeId.trim();
  }

  public String getParentId() {
    return mOldParentId;
  }
}
