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
@Table(name = Tables.INVITATION_LINK)
public class InvitationLink {

  @Id
  @Column(name = Db.InvitationLink.ID, nullable = false, length = 36)
  private UUID id;

  @Column(name = Db.InvitationLink.NODE_ID, nullable = false, length = 36)
  private String nodeId;

  @Column(name = Db.InvitationLink.INVITATION_ID, nullable = false, length = 8)
  private String invitationId;

  @WhenCreated
  @Column(name = Db.InvitationLink.CREATED_AT, nullable = false)
  private Instant createdAt;

  @Column(name = Db.InvitationLink.PERMISSIONS, nullable = false)
  private short permissions;

  public InvitationLink(
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
