// SPDX-FileCopyrightText: 2022 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.dal.dao.ebean;

import com.zextras.carbonio.files.Files;
import java.util.Optional;
import javax.persistence.Column;
import javax.persistence.EmbeddedId;
import javax.persistence.Entity;
import javax.persistence.JoinColumn;
import javax.persistence.ManyToOne;
import javax.persistence.Table;

/**
 * <p>Represents an Ebean {@link Share} entity that matches a record of the {@link
 * Files.Db.Tables#SHARE} table.</p>
 * <p>The implementation of constructors and setters should not care to check if the values in
 * input are valid or not because, when these methods are called, these controls
 * <strong>must</strong> be already done.</p>
 */
@Entity
@Table(name = Files.Db.Tables.SHARE)
public class Share {

  @EmbeddedId
  private SharePK mComposedPrimaryKey;

  @Column(name = Files.Db.NodeCustomAttributes.NODE_ID, nullable = false)
  private String mNodeId;

  @Column(name = Files.Db.Share.PERMISSIONS)
  private Short mPermissions;

  @Column(name = Files.Db.Share.CREATED_AT, nullable = false)
  private long mCreatedAt;

  @Column(name = Files.Db.Share.EXPIRED_AT)
  private Long mExpiredAt;

  @ManyToOne
  @JoinColumn(name = Files.Db.Share.NODE_ID, referencedColumnName = Files.Db.Node.ID, insertable = false, updatable = false)
  private Node node;

  @Column(name = Files.Db.Share.DIRECT, nullable = false)
  private Boolean mDirect;

  /**
   * <p>Creates a new {@link Share} entity.</p>
   * <p>This constructor does not set the expiration timestamp of the share.</p>
   *
   * @param nodeId is a {@link String} of the node id.
   * @param targetUserId is a {@link String} of the target user id which the node will be shared
   * to.
   * @param permissions is a {@link ACL} representing the permissions of this share.
   * @param createdAt is a <code>long</code> of the creation timestamp.
   * @param direct is a {@link Boolean} used for setting if the share is direct or inherited.
   */
  public Share(
    String nodeId,
    String targetUserId,
    ACL permissions,
    long createdAt,
    Boolean direct
  ) {
    this(nodeId, targetUserId, permissions, createdAt, direct, null);
  }

  /**
   * <p>Creates a new {@link Share} entity. </p>
   *
   * @param nodeId is a {@link String} of the node id.
   * @param targetUserId is a {@link String} of the target user id which the node will be shared
   * to.
   * @param permissions is a {@link ACL} representing the permissions of this share.
   * @param createdAt is a <code>long</code> of the creation timestamp.
   * @param direct is a {@link Boolean} used for setting if the share is direct or inherited.
   * @param expiredAt is a {@link Long} of the expiration timestamp.
   */
  public Share(
    String nodeId,
    String targetUserId,
    ACL permissions,
    long createdAt,
    Boolean direct,
    Long expiredAt
  ) {
    mComposedPrimaryKey = new SharePK(nodeId, targetUserId);
    mPermissions = permissions.encode();
    mCreatedAt = createdAt;
    mDirect = direct;
    mExpiredAt = expiredAt;
  }

  public String getNodeId() {
    return mComposedPrimaryKey.getNodeId();
  }

  public String getTargetUserId() {
    return mComposedPrimaryKey.getTargetUserId();
  }

  public ACL getPermissions() {
    return ACL.decode(mPermissions);
  }

  public Share setPermissions(ACL permissions) {
    mPermissions = permissions.encode();
    return this;
  }

  public long getCreationAt() {
    return mCreatedAt;
  }

  public Optional<Long> getExpiredAt() {
    return Optional.ofNullable(mExpiredAt);
  }

  public Share setExpiredAt(long expiredAt) {
    mExpiredAt = expiredAt;
    return this;
  }

  public Boolean isDirect() {
    return mDirect;
  }

  public void setDirect(Boolean mDirect) {
    this.mDirect = mDirect;
  }
}
