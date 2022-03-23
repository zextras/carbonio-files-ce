// SPDX-FileCopyrightText: 2022 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.dal.dao.ebean;

import com.zextras.carbonio.files.Files;
import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.JoinColumn;
import javax.persistence.OneToOne;
import javax.persistence.Table;

/**
 * <p>Represents an Ebean {@link TrashedNode} entity that matches a record of the {@link
 * Files.Db.Tables#TRASHED_NODE} table.</p>
 * <p>The implementation of constructors and setters should not care to check if the values in
 * input are valid or not because, when these methods are called, these controls
 * <strong>must</strong> be already done.</p>
 */
@Entity
@Table(name = Files.Db.Tables.TRASHED_NODE)
public
class TrashedNode {

  @Id
  @Column(name = Files.Db.Trashed.NODE_ID, nullable = false, length = 36)
  private String mNodeId;

  @Column(name = Files.Db.Trashed.PARENT_ID, nullable = false, length = 36)
  private String mOldParentId;

  @OneToOne
  @JoinColumn(name = Files.Db.Trashed.NODE_ID, referencedColumnName = Files.Db.Node.ID, insertable = false, updatable = false)
  private Node node;

  public TrashedNode(
    String nodeId,
    String parentId
  ) {
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
