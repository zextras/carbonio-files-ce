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
 * <p>The share has properties mapped to the corresponding table columns:</p>
 * <ul>
 *   <li>{@code composedPrimaryKey}: the unique key composed by the {@code nodeId} and the {@code userId}.
 *   <li>{@code nodeId}: The identifier of the associated node.</li>
 *   <li>{@code targetUserId}: The identifier of the associated user shared to.</li>
 *   <li>{@code permissions}: The rights of the share.</li>
 *   <li>{@code createdAt}: The timestamp indicating when the share was created.</li>
 *   <li>{@code expiresAt}: The timestamp indicating when the share should expire.</li>
 *   <li>{@code direct}: The boolean indicating if a share is created directly or it is inherited from a parent node directly shared.</li>
 *   <li>{@code createdViaLink}: The boolean indicating if the share is created by a {@link CollaborationLink}.</li>
 * </ul>
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
   * <p>Creates a new {@link Share} entity that can be saved in the database. </p>
   *
   * @param nodeId is a {@link String} of the node id.
   * @param targetUserId is a {@link String} of the target user id which the node will be shared
   * to.
   * @param permissions is an {@link ACL} representing the permissions of the share.
   * @param createdAt is a {@link Long} of the creation timestamp.
   * @param direct is a {@link Boolean} used to set if the share is direct or inherited.
   * @param createdViaLink is a {@link Boolean} used to set if the share is created by a {@link CollaborationLink}.
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

  /**
   * @return a {@link String} representing the identifier of the associated node.
   */
  public String getNodeId() {
    return composedPrimaryKey.getNodeId();
  }

  /**
   * @return a {@link String} representing the identifier of the user which the node is shared to.
   */
  public String getTargetUserId() {
    return composedPrimaryKey.getTargetUserId();
  }

  /**
   * @return an {@link ACL} representing which rights the user has on the associated node.
   */
  public ACL getPermissions() {
    return ACL.decode(permissions);
  }

  /**
   * Allows to change the rights the user has on the associated node.
   *
   * @return an {@link ACL} representing the newpermissions.
   * @return the current {@link Share}.
   */
  public Share setPermissions(ACL permissions) {
    this.permissions = permissions.encode();
    return this;
  }

  /**
   * @return a <code>long</code> representing the creation timestamp of the public link.
   */
  public long getCreatedAt() {
    return createdAt;
  }

  /**
   * @return an {@link Optional} containing a {@link Long} representing the expiration timestamp of
   * the share, if exists.
   */
  public Optional<Long> getExpiredAt() {
    return Optional.ofNullable(expiredAt);
  }

  /**
   * Allows to change the expiration timestamp of the existing share.
   *
   * @param expiresAt is a <code>long</code> representing the expiration timestamp.
   * @return the current {@link Share}.
   */
  public Share setExpiredAt(long expiredAt) {
    this.expiredAt = expiredAt;
    return this;
  }

  /**
   * @return a {@link Boolean} indicating if the share was created directly or it is an indirect share.
   */
  public Boolean isDirect() {
    return direct;
  }

  /**
   * Allows to change a share from direct to indirect and viceversa.
   *
   * @param direct is a {@link Boolean} representing the direct or indirect flag.
   * @return the current {@link Share}.
   */
  public Share setDirect(Boolean direct) {
    this.direct = direct;
    return this;
  }

  /**
   * @return a {@link Boolean} indicating if the share was created via a {@link CollaborationLink} or not.
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
