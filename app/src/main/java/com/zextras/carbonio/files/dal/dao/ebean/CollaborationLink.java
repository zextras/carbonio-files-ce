// SPDX-FileCopyrightText: 2022 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.dal.dao.ebean;

import com.zextras.carbonio.files.Constants;
import com.zextras.carbonio.files.Constants.Db;
import com.zextras.carbonio.files.Constants.Db.Tables;
import com.zextras.carbonio.files.dal.dao.ebean.ACL.SharePermission;
import java.time.Instant;
import java.util.UUID;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Represents an Ebean {@link CollaborationLink} entity that matches a record of the {@link
 * Constants.Db.Tables#COLLABORATION_LINK} table.
 *
 * <p>The collaboration link has properties mapped to the corresponding table columns:
 *
 * <ul>
 *   <li>{@code id}: The unique identifier of the link.
 *   <li>{@code nodeId}: The identifier of the associated node.
 *   <li>{@code invitationId}: The public identifier of the url link.
 *   <li>{@code createdAt}: The timestamp indicating when the link was created.
 *   <li>{@code permissions}: The permissions assigned to the link necessary to create the share
 *       between the user that clicked on the link and the related node.
 * </ul>
 *
 * <p>The constructor should not care to check if the values in input are valid or not because these
 * controls <strong>must</strong> be already done before calling the constructor.
 */
@Entity
@Table(name = Tables.COLLABORATION_LINK)
public class CollaborationLink {

  /** Protected no-arg constructor required by Hibernate/JPA. */
  protected CollaborationLink() {}

  @Id
  @Column(name = Db.CollaborationLink.ID, nullable = false, length = 36)
  private UUID id;

  @Column(name = Db.CollaborationLink.NODE_ID, nullable = false, length = 36)
  private String nodeId;

  @Column(name = Db.CollaborationLink.INVITATION_ID, nullable = false, length = 8)
  private String invitationId;

  @Column(name = Db.CollaborationLink.CREATED_AT, nullable = false)
  private Instant createdAt;

  @Column(name = Db.CollaborationLink.PERMISSIONS, nullable = false)
  private short permissions;

  /**
   * Creates a new {@link CollaborationLink} entity that can be saved in the database.
   *
   * @param linkId is a {@link UUID} representing the collaboration link identifier.
   * @param nodeId is a {@link String} representing the {@link Node} identifier associated to the
   *     collaboration link.
   * @param invitationId is a {@link String} of <code>8</code> alphanumeric characters representing
   *     the public identifier of the URL link.
   * @param createdAt is an {@link Instant} of the link creation timestamp.
   * @param permissions is a <code>short</code> representing the permission necessary to create the
   *     {@link Share} between the user that used the link and the related node.
   */
  public CollaborationLink(
      UUID linkId, String nodeId, String invitationId, Instant createdAt, short permissions) {
    this.id = linkId;
    this.nodeId = nodeId;
    this.invitationId = invitationId;
    this.createdAt = createdAt;
    this.permissions = permissions;
  }

  /**
   * @return an {@link UUID} representing the unique identifier of the collaboration link.
   */
  public UUID getId() {
    return id;
  }

  /**
   * @return a {@link String} representing the identifier of the associated node.
   */
  public String getNodeId() {
    return nodeId;
  }

  /**
   * @return a {@link String} of <code>8</code> alphanumeric characters representing the invitation
   *     identifier used to build the complete URL.
   */
  public String getInvitationId() {
    return invitationId;
  }

  /**
   * @return an {@link Instant} representing the creation timestamp of the collaboration link.
   */
  public Instant getCreatedAt() {
    return createdAt;
  }

  /**
   * @return a {@link SharePermission} representing the permission necessary to create the share
   *     between the user that used the link and the related node.
   */
  public SharePermission getPermissions() {
    return ACL.decode(permissions).getSharePermission();
  }
}
