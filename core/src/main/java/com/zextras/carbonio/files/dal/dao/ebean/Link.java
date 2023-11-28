// SPDX-FileCopyrightText: 2022 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.dal.dao.ebean;

import com.zextras.carbonio.files.Files;
import com.zextras.carbonio.files.Files.Db;
import java.util.Optional;
import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Table;

/**
 * <p>Represents an Ebean {@link Link} entity that matches a record of the {@link Files.Db.Link}
 * table.</p>
 * <p>The implementation of constructors and setters should not care to check if the values in
 * input are valid or not because, when these methods are called, these controls
 * <strong>must</strong> be already done.</p>
 */
@Entity
@Table(name = Files.Db.Tables.LINK)
public class Link {

  @Id
  @Column(name = Db.Link.ID, nullable = false, length = 36)
  private String mId;

  @Column(name = Db.Link.NODE_ID, nullable = false, length = 36)
  private String mNodeId;

  @Column(name = Db.Link.PUBLIC_ID, nullable = false)
  private String mPublicId;

  @Column(name = Files.Db.Link.CREATED_AT, nullable = false)
  private Long mCreatedAt;

  @Column(name = Files.Db.Link.EXPIRES_AT)
  private Long mExpiresAt;

  @Column(name = Db.Link.DESCRIPTION, length = 300)
  private String mDescription;


  /**
   * <p>Creates a new {@link Link} entity that can be saved in the database.</p>
   * <p>This constructor does not set the expiration timestamp and the description of the link.</p>
   *
   * @param linkId is a {@link String} of the unique identifier of the link.
   * @param nodeId is a {@link String} of the related node id.
   * @param publicId is a {@link String} of the public id used to build the complete url.
   * @param createdAt is a {@link Long} of the creation timestamp.
   */
  public Link(
    String linkId,
    String nodeId,
    String publicId,
    Long createdAt
  ) {
    mId = linkId;
    mNodeId = nodeId;
    mPublicId = publicId;
    mCreatedAt = createdAt;
  }

  /**
   * Creates a new {@link Link} entity that can be saved in the database.
   *
   * @param linkId is a {@link String} of the unique identifier of the link.
   * @param nodeId is a {@link String} of the related node id.
   * @param publicId is a {@link String} of the public id used to build the complete url.
   * @param createdAt is a {@link Long} of the creation timestamp.
   * @param expiresAt is a {@link Long} of the expiration timestamp.
   * @param description is a {@link String} of the link description.
   */
  public Link(
    String linkId,
    String nodeId,
    String publicId,
    Long createdAt,
    Long expiresAt,
    String description
  ) {
    mId = linkId;
    mNodeId = nodeId;
    mPublicId = publicId;
    mCreatedAt = createdAt;
    mExpiresAt = expiresAt;
    mDescription = description;
  }

  /**
   * @return a {@link String} representing the link id.
   */
  public String getLinkId() {
    return mId;
  }

  /**
   * @return a {@link String} representing the id of the related node.
   */
  public String getNodeId() {
    return mNodeId;
  }

  /**
   * @return a {@link String} of the public id used to build the complete url.
   */
  public String getPublicId() {
    return mPublicId;
  }

  /**
   * @return a {@link Long} representing the creation timestamp of the link.
   */
  public Long getCreatedAt() {
    return mCreatedAt;
  }

  /**
   * @return an {@link Optional} containing a {@link Long} representing the expiration timestamp of
   * the link, if exists.
   */
  public Optional<Long> getExpiresAt() {
    return Optional.ofNullable(mExpiresAt);
  }

  public void setExpiresAt(Long expiresAt) {
    if (expiresAt == 0) {
      mExpiresAt = null;
    } else {
      mExpiresAt = expiresAt;
    }
  }

  /**
   * @return an {@link Optional} containing a {@link String} representing the link description, if
   * exists.
   */
  public Optional<String> getDescription() {
    return Optional.ofNullable(mDescription);
  }

  public void setDescription(String description) {
    mDescription = description;
  }
}
