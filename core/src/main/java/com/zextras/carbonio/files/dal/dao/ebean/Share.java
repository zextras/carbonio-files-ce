// SPDX-FileCopyrightText: 2022 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.dal.dao.ebean;

import com.zextras.carbonio.files.Files;
import io.ebean.annotation.Cache;
import java.util.Optional;
import javax.annotation.Nullable;
import javax.persistence.Column;
import javax.persistence.EmbeddedId;
import javax.persistence.Entity;
import javax.persistence.JoinColumn;
import javax.persistence.ManyToOne;
import javax.persistence.Table;

/**
 * <p>Represents an Ebean {@link Share} entity that matches a record of the {@link
 * Files.Db.Tables#SHARE} table.</p>
 * <p>The implementation of the constructor and setters should not care to check if the values in
 * input are valid or not because, when these methods are called, these controls
 * <strong>must</strong> be already done.</p>
 */
@Cache
@Entity
@Table(name = Files.Db.Tables.SHARE)
public class Share {

  @EmbeddedId
  private final SharePK composedPrimaryKey;

  @Column(name = Files.Db.Share.PERMISSIONS)
  private Short permissions;

  @Column(name = Files.Db.Share.CREATED_AT, nullable = false)
  private final Long createdAt;

  @Column(name = Files.Db.Share.EXPIRED_AT)
  private Long expiredAt;

  @ManyToOne
  @JoinColumn(name = Files.Db.Share.NODE_ID, referencedColumnName = Files.Db.Node.ID, insertable = false, updatable = false)
  private Node node;

  @Column(name = Files.Db.Share.DIRECT, nullable = false)
  private Boolean direct;

  @Column(name = Files.Db.Share.CREATED_VIA_LINK, nullable = false)
  private Boolean createdViaLink;

  /**
   * <p>Creates a new {@link Share} entity. </p>
   *
   * @param nodeId is a {@link String} of the node id.
   * @param targetUserId is a {@link String} of the target user id which the node will be shared
   * to.
   * @param permissions is an {@link ACL} representing the permissions of the share.
   * @param createdAt is a {@link Long} of the creation timestamp.
   * @param direct is a {@link Boolean} used to set if the share is direct or inherited.
   * @param expiredAt is a {@link Long} of the expiration timestamp. It could be nullable.
   */
  public Share(
    String nodeId,
    String targetUserId,
    ACL permissions,
    Long createdAt,
    Boolean direct,
    Boolean createdViaLink,
    @Nullable Long expiredAt
  ) {
    this.composedPrimaryKey = new SharePK(nodeId, targetUserId);
    this.permissions = permissions.encode();
    this.createdAt = createdAt;
    this.direct = direct;
    this.createdViaLink = createdViaLink;
    this.expiredAt = expiredAt;
  }

  public String getNodeId() {
    return composedPrimaryKey.getNodeId();
  }

  public String getTargetUserId() {
    return composedPrimaryKey.getTargetUserId();
  }

  public ACL getPermissions() {
    return ACL.decode(permissions);
  }

  public Share setPermissions(ACL permissions) {
    this.permissions = permissions.encode();
    return this;
  }

  public long getCreatedAt() {
    return createdAt;
  }

  public Optional<Long> getExpiredAt() {
    return Optional.ofNullable(expiredAt);
  }

  public Share setExpiredAt(long expiredAt) {
    this.expiredAt = expiredAt;
    return this;
  }

  public Boolean isDirect() {
    return direct;
  }

  public Share setDirect(Boolean mDirect) {
    this.direct = mDirect;
    return this;
  }

  public Boolean isCreatedViaLink() {
    return createdViaLink;
  }

  public Share setCreatedViaLink(Boolean createdViaLink) {
    this.createdViaLink = createdViaLink;
    return this;
  }
}
