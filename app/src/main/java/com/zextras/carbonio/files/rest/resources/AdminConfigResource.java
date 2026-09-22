// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.rest.resources;

import com.zextras.carbonio.files.Constants.API.Headers;
import com.zextras.carbonio.files.config.HierarchicalConfigKeys;
import com.zextras.carbonio.files.dal.dao.UserInfo;
import com.zextras.carbonio.files.dal.repositories.interfaces.UserRepository;
import com.zextras.carbonio.quarkus.extensions.confighierarchical.ConfigAdminService;
import com.zextras.carbonio.quarkus.extensions.confighierarchical.ConfigResolver;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.CookieParam;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.jboss.resteasy.reactive.RestResponse;

/**
 * Admin-only hierarchical-config management for the admin panel. Global-admin gated ({@code
 * ZM_ADMIN_AUTH_TOKEN}, {@link AdminAuthenticator}).
 *
 * <p>Two families:
 *
 * <ul>
 *   <li><b>Resolved view (mirrors the user {@code GET /config}, plus the source, for a target
 *       user):</b>
 *       <ul>
 *         <li>{@code GET /admin/config?userId=<id>} — every declared key RESOLVED for that user
 *             (account &gt; cos &gt; domain &gt; global &gt; default), each with the {@code source}
 *             tier that produced it.
 *         <li>{@code GET /admin/config?userId=<id>&key=<key>} — a single resolved key + source; an
 *             undeclared key is a real absence and answers {@code 404}.
 *       </ul>
 *       {@code userId} is mandatory. Resolution reuses the extension's {@link ConfigResolver}
 *       precedence (no re-implementation here); the user's cos/domain come from {@link
 *       UserRepository#getUserById}.
 *   <li><b>Raw override management (under {@code /raw}, distinct from the resolved view):</b>
 *       <ul>
 *         <li>{@code GET /admin/config/raw/default} — the DEFAULT tier only (namespaced
 *             application.properties, NOT the global override); read-only, complete.
 *         <li>{@code GET /admin/config/raw/global} — the RAW overrides at the GLOBAL tier
 *             (singleton, no scope id); keys not overridden are absent.
 *         <li>{@code PUT /admin/config/raw/global} — set one global override ({@code {key,value}}).
 *         <li>{@code DELETE /admin/config/raw/global/{key}} — clear one global override,
 *             idempotent.
 *         <li>{@code GET /admin/config/raw/{scope}/{scopeId}} — the RAW overrides set at exactly
 *             that scope (scope = account|cos|domain); keys not overridden are absent.
 *         <li>{@code PUT /admin/config/raw/{scope}/{scopeId}} — set one override ({@code
 *             {key,value}}).
 *         <li>{@code DELETE /admin/config/raw/{scope}/{scopeId}/{key}} — clear one override
 *             (revert-to-inherited), idempotent.
 *       </ul>
 * </ul>
 */
@ApplicationScoped
@Path("/admin/config")
@Produces(MediaType.APPLICATION_JSON)
public class AdminConfigResource {

  private final AdminAuthenticator adminAuthenticator;
  private final ConfigAdminService configAdminService;
  private final ConfigResolver configResolver;
  private final UserRepository userRepository;

  @Inject
  public AdminConfigResource(
      AdminAuthenticator adminAuthenticator,
      ConfigAdminService configAdminService,
      ConfigResolver configResolver,
      UserRepository userRepository) {
    this.adminAuthenticator = adminAuthenticator;
    this.configAdminService = configAdminService;
    this.configResolver = configResolver;
    this.userRepository = userRepository;
  }

  /** Body for a per-scope config write: the key and its value. */
  public record SetConfigRequest(String key, String value) {}

  /**
   * A resolved value together with the scope tier that produced it
   * (account|cos|domain|global|default). The value may be {@code null} when the winning tier's
   * value is empty (an empty value is itself the base default, not an absence); the source is
   * always one of those five tiers.
   */
  public record ResolvedEntry(String value, String source) {}

  private enum Scope {
    ACCOUNT,
    COS,
    DOMAIN
  }

  // -------------------------------------------------------------------------
  // Resolved view for a target user (mirrors GET /config, + source)
  // -------------------------------------------------------------------------

  @GET
  public RestResponse<Map<String, ResolvedEntry>> getResolvedConfig(
      @CookieParam(Headers.COOKIE_ZM_ADMIN_AUTH_TOKEN) String adminToken,
      @QueryParam("userId") String userId,
      @QueryParam("key") String key) {
    adminAuthenticator.requireGlobalAdmin(adminToken);
    if (userId == null || userId.isBlank()) {
      throw badRequest("userId is required");
    }
    if (key != null && !key.isBlank() && !HierarchicalConfigKeys.isDeclared(key)) {
      throw notFound("Unknown config key: " + key);
    }

    UserInfo user =
        userRepository
            .getUserById(null, userId)
            .orElseThrow(() -> notFound("Unknown user: " + userId));

    Optional<String> accountId = Optional.of(userId);
    Optional<String> cosId = Optional.ofNullable(user.getCosId());
    Optional<String> domainId = Optional.ofNullable(user.getDomainId());

    List<String> keys =
        (key != null && !key.isBlank()) ? List.of(key) : HierarchicalConfigKeys.ALL_KEYS;

    Map<String, ResolvedEntry> resolved = new LinkedHashMap<>();
    for (String k : keys) {
      ConfigResolver.Resolution res = configResolver.resolve(accountId, cosId, domainId, k);
      resolved.put(
          k,
          new ResolvedEntry(
              res.value().orElse(null), res.source().name().toLowerCase(Locale.ROOT)));
    }
    return RestResponse.ok(resolved);
  }

  // -------------------------------------------------------------------------
  // Raw override management (/raw)
  // -------------------------------------------------------------------------

  @GET
  @Path("/raw/default")
  public RestResponse<Map<String, String>> getDefaultConfig(
      @CookieParam(Headers.COOKIE_ZM_ADMIN_AUTH_TOKEN) String adminToken) {
    adminAuthenticator.requireGlobalAdmin(adminToken);

    // The DEFAULT tier ONLY (namespaced application.properties) — never the GLOBAL override above
    // it, so /raw/default stays distinct from /raw/global. Complete (every key present); null when
    // the base default is empty/unset.
    Map<String, String> defaults = new LinkedHashMap<>();
    for (String key : HierarchicalConfigKeys.ALL_KEYS) {
      defaults.put(key, configResolver.getBaseDefault(key).orElse(null));
    }
    return RestResponse.ok(defaults);
  }

  @GET
  @Path("/raw/{scope}/{scopeId}")
  public RestResponse<Map<String, String>> getScopeConfig(
      @CookieParam(Headers.COOKIE_ZM_ADMIN_AUTH_TOKEN) String adminToken,
      @PathParam("scope") String scope,
      @PathParam("scopeId") String scopeId) {
    adminAuthenticator.requireGlobalAdmin(adminToken);
    Scope resolved = parseScope(scope);

    Map<String, String> overrides = new LinkedHashMap<>();
    for (String key : HierarchicalConfigKeys.ALL_KEYS) {
      rawGet(resolved, scopeId, key).ifPresent(value -> overrides.put(key, value));
    }
    return RestResponse.ok(overrides);
  }

  @PUT
  @Path("/raw/{scope}/{scopeId}")
  @Consumes(MediaType.APPLICATION_JSON)
  public RestResponse<Void> setScopeConfig(
      @CookieParam(Headers.COOKIE_ZM_ADMIN_AUTH_TOKEN) String adminToken,
      @PathParam("scope") String scope,
      @PathParam("scopeId") String scopeId,
      SetConfigRequest body) {
    adminAuthenticator.requireGlobalAdmin(adminToken);

    if (body == null || body.key() == null || body.key().isBlank()) {
      throw badRequest("Missing config key");
    }
    if (body.value() == null) {
      throw badRequest("Missing config value");
    }
    Scope resolved = parseScope(scope);

    switch (resolved) {
      case ACCOUNT -> configAdminService.setForAccount(scopeId, body.key(), body.value());
      case COS -> configAdminService.setForCos(scopeId, body.key(), body.value());
      case DOMAIN -> configAdminService.setForDomain(scopeId, body.key(), body.value());
    }
    return RestResponse.status(Response.Status.NO_CONTENT);
  }

  @DELETE
  @Path("/raw/{scope}/{scopeId}/{key}")
  public RestResponse<Void> deleteScopeConfig(
      @CookieParam(Headers.COOKIE_ZM_ADMIN_AUTH_TOKEN) String adminToken,
      @PathParam("scope") String scope,
      @PathParam("scopeId") String scopeId,
      @PathParam("key") String key) {
    adminAuthenticator.requireGlobalAdmin(adminToken);
    Scope resolved = parseScope(scope);

    switch (resolved) {
      case ACCOUNT -> configAdminService.deleteForAccount(scopeId, key);
      case COS -> configAdminService.deleteForCos(scopeId, key);
      case DOMAIN -> configAdminService.deleteForDomain(scopeId, key);
    }
    // Idempotent: clearing an override always yields "no override at this scope" (the key falls
    // back to the inherited/default value), whether or not a row existed — 204 regardless.
    return RestResponse.status(Response.Status.NO_CONTENT);
  }

  // -------------------------------------------------------------------------
  // Global scope (singleton, no scope id) — sits just above the base default
  // -------------------------------------------------------------------------

  @GET
  @Path("/raw/global")
  public RestResponse<Map<String, String>> getGlobalConfig(
      @CookieParam(Headers.COOKIE_ZM_ADMIN_AUTH_TOKEN) String adminToken) {
    adminAuthenticator.requireGlobalAdmin(adminToken);

    // Sparse, like the per-scope raw view: only keys overridden at the global tier are present.
    Map<String, String> overrides = new LinkedHashMap<>();
    for (String key : HierarchicalConfigKeys.ALL_KEYS) {
      configAdminService.getRawFromGlobal(key).ifPresent(value -> overrides.put(key, value));
    }
    return RestResponse.ok(overrides);
  }

  @PUT
  @Path("/raw/global")
  @Consumes(MediaType.APPLICATION_JSON)
  public RestResponse<Void> setGlobalConfig(
      @CookieParam(Headers.COOKIE_ZM_ADMIN_AUTH_TOKEN) String adminToken, SetConfigRequest body) {
    adminAuthenticator.requireGlobalAdmin(adminToken);
    if (body == null || body.key() == null || body.key().isBlank()) {
      throw badRequest("Missing config key");
    }
    if (body.value() == null) {
      throw badRequest("Missing config value");
    }
    configAdminService.setForGlobal(body.key(), body.value());
    return RestResponse.status(Response.Status.NO_CONTENT);
  }

  @DELETE
  @Path("/raw/global/{key}")
  public RestResponse<Void> deleteGlobalConfig(
      @CookieParam(Headers.COOKIE_ZM_ADMIN_AUTH_TOKEN) String adminToken,
      @PathParam("key") String key) {
    adminAuthenticator.requireGlobalAdmin(adminToken);
    configAdminService.deleteForGlobal(key);
    // Idempotent: 204 whether or not a row existed — clearing the global override lets the key fall
    // back to the base default.
    return RestResponse.status(Response.Status.NO_CONTENT);
  }

  private Optional<String> rawGet(Scope scope, String scopeId, String key) {
    return switch (scope) {
      case ACCOUNT -> configAdminService.getRawFromAccount(scopeId, key);
      case COS -> configAdminService.getRawFromCos(scopeId, key);
      case DOMAIN -> configAdminService.getRawFromDomain(scopeId, key);
    };
  }

  private Scope parseScope(String scope) {
    try {
      return Scope.valueOf(scope == null ? "" : scope.toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw badRequest("Unknown scope '" + scope + "' (expected account|cos|domain)");
    }
  }

  private WebApplicationException badRequest(String reason) {
    return new WebApplicationException(
        Response.status(Response.Status.BAD_REQUEST).entity(reason).type("text/plain").build());
  }

  private WebApplicationException notFound(String reason) {
    return new WebApplicationException(
        Response.status(Response.Status.NOT_FOUND).entity(reason).type("text/plain").build());
  }
}
