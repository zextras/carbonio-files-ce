// SPDX-FileCopyrightText: 2022 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.dal.dao.ebean;

import com.zextras.carbonio.files.Files.Db;
import com.zextras.carbonio.files.Files.Db.Tables;
import com.zextras.carbonio.files.dal.dao.ebean.ACL.SharePermission;
import io.ebean.annotation.WhenCreated;
import java.time.Instant;
import java.util.UUID;
import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Table;

@Entity
@Table(name = Tables.COLLABORATION_LINK)
public class CollaborationLink {

  @Id
  @Column(name = Db.CollaborationLink.ID, nullable = false, length = 36)
  private UUID id;

  @Column(name = Db.CollaborationLink.NODE_ID, nullable = false, length = 36)
  private String nodeId;

  @Column(name = Db.CollaborationLink.INVITATION_ID, nullable = false, length = 8)
  private String invitationId;

  @WhenCreated
  @Column(name = Db.CollaborationLink.CREATED_AT, nullable = false)
  private Instant createdAt;

  @Column(name = Db.CollaborationLink.PERMISSIONS, nullable = false)
  private short permissions;

  public CollaborationLink(
    UUID linkId,
    String nodeId,
    String invitationId,
    short permissions
  ) {
    this.id = linkId;
    this.nodeId = nodeId;
    this.invitationId = invitationId;
    this.permissions = permissions;
  }

  public UUID getId() {
    return id;
  }

  public String getNodeId() {
    return nodeId;
  }

  public String getInvitationId() {
    return invitationId;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public SharePermission getPermissions() {
    return ACL.decode(permissions).getSharePermission();
  }
}
