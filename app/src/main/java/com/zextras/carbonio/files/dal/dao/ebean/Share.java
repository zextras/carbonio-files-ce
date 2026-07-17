// SPDX-FileCopyrightText: 2022 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.dal.dao.ebean;

import com.zextras.carbonio.files.Constants;
import java.util.Optional;
import jakarta.annotation.Nullable;
import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * Represents an Ebean {@link Share} entity that matches a record of the {@link
 * Constants.Db.Tables#SHARE} table.
 *
 * <p>The share has properties mapped to the corresponding table columns:
 *
 * <ul>
 *   <li>{@code compositeId}: The unique identifier is represented by the {@link SharePK} class.
 *   <li>{@code permissions}: The permission of the share needed to apply which rights a user has on
 *       the related node.
 *   <li>{@code createdAt}: The timestamp indicating when the share was created.
 *   <li>{@code expiresAt}: The timestamp indicating when the share should expire.
 *   <li>{@code direct}: A boolean indicating if the share is created directly or it is indirect.
 *   <li>{@code createdViaLink}: A boolean indicating if the share is created via a {@link
 *       CollaborationLink} or not.
 * </ul>
 *
 * <p>The implementation of the constructor and setters should not care to check if the values in
 * input are valid or not because, when these methods are called, these controls
 * <strong>must</strong> be already done.
 */
@Entity
@Table(name = Constants.Db.Tables.SHARE)
public class Share {

  /** Protected no-arg constructor required by Hibernate/JPA. */
  protected Share() {}

  @EmbeddedId private SharePK composedPrimaryKey;

  @Column(name = Constants.Db.Share.PERMISSIONS)
  private Short permissions;

  @Column(name = Constants.Db.Share.CREATED_AT, nullable = false)
  private Long createdAt;

  @Column(name = Constants.Db.Share.EXPIRED_AT)
  private Long expiredAt;

  @ManyToOne
  @JoinColumn(
      name = Constants.Db.Share.NODE_ID,
      referencedColumnName = Constants.Db.Node.ID,
      insertable = false,
      updatable = false)
  private Node node;

  @Column(name = Constants.Db.Share.DIRECT, nullable = false)
  private Boolean direct;

  @Column(name = Constants.Db.Share.CREATED_VIA_LINK, nullable = false)
  private Boolean createdViaLink;

  /**
   * Creates a new {@link Share} entity that can be saved in the database.
   *
   * @param nodeId is a {@link String} representing the {@link Node} identifier associated to the
   *     share.
   * @param targetUserId is a {@link String} of the target user id which the node will be shared to.
   * @param permissions is an {@link ACL} representing the permissions of the share.
   * @param createdAt is a {@link Long} of the creation timestamp.
   * @param direct is a {@link Boolean} used to set if the share is direct or inherited.
   * @param createdViaLink is a {@link Boolean} used to indicate if the share is created via a
   *     {@link CollaborationLink} or not.
   * @param expiredAt is a {@link Long} of the expiration timestamp. It could be nullable.
   */
  public Share(
      String nodeId,
      String targetUserId,
      ACL permissions,
      Long createdAt,
      Boolean direct,
      Boolean createdViaLink,
      @Nullable Long expiredAt) {
    this.composedPrimaryKey = new SharePK(nodeId, targetUserId);
    this.permissions = permissions.encode();
    this.createdAt = createdAt;
    this.direct = direct;
    this.createdViaLink = createdViaLink;
    this.expiredAt = expiredAt;
  }

  /**
   * @return a {@link String} representing the identifier of the associated node.
   */
  public String getNodeId() {
    return composedPrimaryKey.getNodeId();
  }

  /**
   * @return a {@link String} of the target user id which the node will be shared to.
   */
  public String getTargetUserId() {
    return composedPrimaryKey.getTargetUserId();
  }

  /**
   * @return an {@link ACL} representing the permissions of the share.
   */
  public ACL getPermissions() {
    return ACL.decode(permissions);
  }

  /**
   * Allows to change the permission of the existing share.
   *
   * @param permissions is an {@link ACL} representing the permissions of the share.
   * @return the current {@link Share}.
   */
  public Share setPermissions(ACL permissions) {
    this.permissions = permissions.encode();
    return this;
  }

  /**
   * @return a <code>long</code> representing the creation timestamp of the share.
   */
  public long getCreatedAt() {
    return createdAt;
  }

  /**
   * @return an {@link Optional} containing a {@link Long} representing the expiration timestamp of
   *     the share, if exists.
   */
  public Optional<Long> getExpiredAt() {
    return Optional.ofNullable(expiredAt);
  }

  /**
   * Allows to set/unset the expiration timestamp of the existing share. If the timestamp is equal
   * to zero than the expiration is disabled and the share will not expire.
   *
   * @param expiredAt is a {@link Long} representing the expiration timestamp.
   * @return the current {@link Share}.
   */
  public Share setExpiredAt(long expiredAt) {
    this.expiredAt = expiredAt;
    return this;
  }

  /**
   * @return a {@link Boolean} used to indicate if the share is direct or inherited.
   */
  public Boolean isDirect() {
    return direct;
  }

  /**
   * Allows to change if the share is become direct or indirect.
   *
   * @param direct is a {@link Boolean} to indicate if the share is direct or inherited.
   * @return the current {@link Share}.
   */
  public Share setDirect(Boolean direct) {
    this.direct = direct;
    return this;
  }

  /**
   * @return a {@link Boolean} to indicate if the share is created via a {@link CollaborationLink}
   *     or not.
   */
  public Boolean isCreatedViaLink() {
    return createdViaLink;
  }

  /**
   * Allows to indicate that a share is created via a {@link CollaborationLink}.
   *
   * @param createdViaLink is a {@link Boolean} representing a creation via link flag.
   * @return the current {@link Share}.
   */
  public Share setCreatedViaLink(Boolean createdViaLink) {
    this.createdViaLink = createdViaLink;
    return this;
  }
}
