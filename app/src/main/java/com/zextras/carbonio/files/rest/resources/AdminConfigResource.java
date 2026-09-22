// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.rest.resources;

import com.zextras.carbonio.files.Constants.API.Headers;
import com.zextras.carbonio.files.config.HierarchicalConfigKeys;
import com.zextras.carbonio.quarkus.extensions.confighierarchical.ConfigAdminService;
import com.zextras.carbonio.quarkus.extensions.confighierarchical.ConfigResolver;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.CookieParam;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.jboss.resteasy.reactive.RestResponse;

/**
 * Admin-only, per-scope hierarchical-config management for the admin panel. Global-admin gated
 * ({@code ZM_ADMIN_AUTH_TOKEN}, {@link AdminAuthenticator}), scope = {@code account|cos|domain}.
 *
 * <ul>
 *   <li>{@code GET /admin/config/{scope}/{scopeId}} — the RAW overrides set at exactly this scope,
 *       WITHOUT hierarchy resolution: only keys with an explicit row here are returned (a key the
 *       scope does not override is absent from the response, not null). {@code {}} means no
 *       overrides at this scope; a key present with {@code ""} is an explicit empty override. This
 *       lets the panel show, per scope, exactly what that account/cos/domain overrides.
 *   <li>{@code GET /admin/config/default} — the base defaults (application.properties) for every
 *       key, resolved with no scope. Read-only, complete (all keys, {@code null} when the default
 *       is empty); the default has no id so it is a distinct path, not a {@code {scope}/{scopeId}}.
 *   <li>{@code PUT /admin/config/{scope}/{scopeId}} — set a single override ({@code {key, value}}).
 *       {@code default} is not writable (it lives in application.properties, not the DB).
 * </ul>
 */
@ApplicationScoped
@Path("/admin/config")
@Produces(MediaType.APPLICATION_JSON)
public class AdminConfigResource {

  private final AdminAuthenticator adminAuthenticator;
  private final ConfigAdminService configAdminService;
  private final ConfigResolver configResolver;

  @Inject
  public AdminConfigResource(
      AdminAuthenticator adminAuthenticator,
      ConfigAdminService configAdminService,
      ConfigResolver configResolver) {
    this.adminAuthenticator = adminAuthenticator;
    this.configAdminService = configAdminService;
    this.configResolver = configResolver;
  }

  /** Body for a per-scope config write: the key and its value. */
  public record SetConfigRequest(String key, String value) {}

  private enum Scope {
    ACCOUNT,
    COS,
    DOMAIN
  }

  @GET
  @Path("/{scope}/{scopeId}")
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

  @GET
  @Path("/default")
  public RestResponse<Map<String, String>> getDefaultConfig(
      @CookieParam(Headers.COOKIE_ZM_ADMIN_AUTH_TOKEN) String adminToken) {
    adminAuthenticator.requireGlobalAdmin(adminToken);

    // The base default is what the resolver returns with no scope at all. Complete (every key
    // always present); null when the base default is empty/unset.
    Map<String, String> defaults = new LinkedHashMap<>();
    for (String key : HierarchicalConfigKeys.ALL_KEYS) {
      defaults.put(
          key,
          configResolver
              .get(Optional.empty(), Optional.empty(), Optional.empty(), key)
              .orElse(null));
    }
    return RestResponse.ok(defaults);
  }

  @PUT
  @Path("/{scope}/{scopeId}")
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
}
