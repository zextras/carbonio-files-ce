// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.dal.dao;

import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Represents the authenticated user (myself). This is a local domain type that replaces the old
 * {@code com.zextras.carbonio.usermanagement.entities.UserMyself} from the HTTP SDK.
 *
 * <p>It retains the same API surface as the old SDK entity so that consumers only need to change
 * their import statements.
 */
public class UserMyself {

  private UserId id;
  private String email;
  private String fullName;
  private String domain;
  private UserStatus status;
  private Locale locale;
  private UserType type;
  private Map<String, String> carbonioAttributes;
  private List<String> features;

  public UserMyself() {}

  /**
   * Constructor matching the old SDK's {@code UserMyself} constructor signature.
   *
   * @param id                 the user identifier
   * @param email              the user email address
   * @param fullName           the user full name
   * @param domain             the user domain
   * @param status             the user account status
   * @param locale             the user locale
   * @param type               the user account type
   * @param carbonioAttributes a map of Carbonio attributes (feature flags and other settings)
   */
  public UserMyself(
      UserId id,
      String email,
      String fullName,
      String domain,
      UserStatus status,
      Locale locale,
      UserType type,
      Map<String, String> carbonioAttributes) {
    this.id = id;
    this.email = email;
    this.fullName = fullName;
    this.domain = domain;
    this.status = status;
    this.locale = locale;
    this.type = type;
    this.carbonioAttributes = carbonioAttributes;
    this.features = Collections.emptyList();
  }

  /**
   * Constructor for gRPC-based creation where features come as a list of enabled feature keys
   * rather than a map of attributes.
   *
   * @param id       the user identifier
   * @param email    the user email address
   * @param fullName the user full name
   * @param domain   the user domain
   * @param status   the user account status
   * @param locale   the user locale
   * @param type     the user account type
   * @param features a list of enabled feature keys (e.g. "carbonioFeatureFilesEnabled")
   */
  public UserMyself(
      UserId id,
      String email,
      String fullName,
      String domain,
      UserStatus status,
      Locale locale,
      UserType type,
      List<String> features) {
    this.id = id;
    this.email = email;
    this.fullName = fullName;
    this.domain = domain;
    this.status = status;
    this.locale = locale;
    this.type = type;
    this.features = features;
    // Build a compatibility map: each enabled feature maps to "TRUE"
    this.carbonioAttributes = new java.util.HashMap<>();
    for (String feature : features) {
      this.carbonioAttributes.put(feature, "TRUE");
    }
  }

  public UserId getId() {
    return id;
  }

  public void setId(UserId id) {
    this.id = id;
  }

  public String getEmail() {
    return email;
  }

  public void setEmail(String email) {
    this.email = email;
  }

  public String getFullName() {
    return fullName;
  }

  public void setFullName(String fullName) {
    this.fullName = fullName;
  }

  public String getDomain() {
    return domain;
  }

  public void setDomain(String domain) {
    this.domain = domain;
  }

  public Locale getLocale() {
    return locale;
  }

  public void setLocale(Locale locale) {
    this.locale = locale;
  }

  public UserType getType() {
    return type;
  }

  public void setType(UserType type) {
    this.type = type;
  }

  public UserStatus getStatus() {
    return status;
  }

  public void setStatus(UserStatus status) {
    this.status = status;
  }

  /**
   * Returns the Carbonio attributes map. For backward compatibility with code that checks feature
   * flags via {@code getCarbonioAttributes().getOrDefault("featureKey", "FALSE").equals("TRUE")}.
   *
   * <p>When constructed from gRPC response (features list), each enabled feature key is mapped to
   * "TRUE" in this map.
   *
   * @return a map of attribute keys to values
   */
  public Map<String, String> getCarbonioAttributes() {
    return carbonioAttributes;
  }

  public void setCarbonioAttributes(Map<String, String> carbonioAttributes) {
    this.carbonioAttributes = carbonioAttributes;
  }

  /**
   * Returns the list of enabled feature keys. This corresponds to the gRPC proto's
   * {@code repeated string features} field.
   *
   * @return a list of enabled feature key strings
   */
  public List<String> getFeatures() {
    return features;
  }

  public void setFeatures(List<String> features) {
    this.features = features;
  }
}
