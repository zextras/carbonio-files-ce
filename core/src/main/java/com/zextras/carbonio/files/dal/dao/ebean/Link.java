// SPDX-FileCopyrightText: 2022 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.dal.dao.ebean;

import com.zextras.carbonio.files.Files;
import com.zextras.carbonio.files.Files.Db;
import java.util.Optional;
import javax.annotation.Nullable;
import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Table;

/**
 * <p>Represents an Ebean {@link Link} entity that matches a record of the {@link Files.Db.Link}
 * table.</p>
 * <p>The public link has properties mapped to the corresponding table columns:</p>
 * <ul>
 *   <li>{@code id}: The unique identifier of the link.</li>
 *   <li>{@code nodeId}: The identifier of the associated node.</li>
 *   <li>{@code publicId}: The public identifier of the URL link.</li>
 *   <li>{@code createdAt}: The timestamp indicating when the link was created.</li>
 *   <li>{@code expiresAt}: The timestamp indicating when the link should expire.</li>
 *   <li>{@code description}: A small description of the link (maximum 300 characters).</li>
 * </ul>
 * <p>The constructor and setters should not care to check if the values in input are valid or not
 * because, when these methods are called, these controls <strong>must</strong> be already done.</p>
 */
@Entity
@Table(name = Files.Db.Tables.LINK)
public class Link {

  @Id
  @Column(name = Db.Link.ID, nullable = false, length = 36)
  private String id;

  @Column(name = Db.Link.NODE_ID, nullable = false, length = 36)
  private String nodeId;

  @Column(name = Db.Link.PUBLIC_ID, nullable = false)
  private String publicId;

  @Column(name = Files.Db.Link.CREATED_AT, nullable = false)
  private Long createdAt;

  @Column(name = Files.Db.Link.EXPIRES_AT)
  private Long expiresAt;

  @Column(name = Db.Link.DESCRIPTION, length = 300)
  private String description;

  /**
   * Creates a new {@link Link} entity that can be saved in the database.
   *
   * @param linkId      is a {@link String} representing the unique identifier of the link.
   * @param nodeId      is a {@link String} representing the {@link Node} identifier associated to
   *                    the public link.
   * @param publicId    is a {@link String} representing the public identifier of the URL link.
   * @param createdAt   is a {@link Long} of the link creation timestamp.
   * @param expiresAt   is a {@link Long} of the link expiration timestamp. It can be nullable.
   * @param description is a {@link String} of the link description. It can be nullable.
   */
  public Link(
    String linkId,
    String nodeId,
    String publicId,
    Long createdAt,
    @Nullable Long expiresAt,
    @Nullable String description
  ) {
    id = linkId;
    this.nodeId = nodeId;
    this.publicId = publicId;
    this.createdAt = createdAt;
    this.expiresAt = expiresAt;
    this.description = description;
  }

  /**
   * @return a {@link String} representing the unique identifier of the public link.
   */
  public String getLinkId() {
    return id;
  }

  /**
   * @return a {@link String} representing the identifier of the associated node.
   */
  public String getNodeId() {
    return nodeId;
  }

  /**
   * @return a {@link String} of <code>8</code> alphanumeric characters representing the public
   * identifier used to build the complete URL.
   */
  public String getPublicId() {
    return publicId;
  }

  /**
   * @return a {@link Long} representing the creation timestamp of the public link.
   */
  public Long getCreatedAt() {
    return createdAt;
  }

  /**
   * @return an {@link Optional} containing a {@link Long} representing the expiration timestamp of
   * the link, if exists.
   */
  public Optional<Long> getExpiresAt() {
    return Optional.ofNullable(expiresAt);
  }

  /**
   * Allows to set/unset the expiration timestamp of the existing link. If the timestamp is equal to
   * zero than the expiration is disabled and the public link will not expire.
   *
   * @param expiresAt is a {@link Long} representing the expiration timestamp.
   * @return the current {@link Link}.
   */
  public Link setExpiresAt(Long expiresAt) {
    if (expiresAt == 0) {
      this.expiresAt = null;
    } else {
      this.expiresAt = expiresAt;
    }

    return this;
  }

  /**
   * @return an {@link Optional} containing a {@link String} representing the link description, if
   * exists.
   */
  public Optional<String> getDescription() {
    return Optional.ofNullable(description);
  }

  /**
   * Allows to add/change the description of the existing public link.
   *
   * @param description is a {@link String} of the link description.
   * @return the current {@link Link}.
   */
  public Link setDescription(String description) {
    this.description = description;
    return this;
  }
}
