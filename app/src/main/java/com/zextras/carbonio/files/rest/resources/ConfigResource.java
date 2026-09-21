// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.rest.resources;

import com.zextras.carbonio.files.Constants.API.Headers;
import com.zextras.carbonio.files.config.HierarchicalConfigKeys;
import com.zextras.carbonio.files.dal.dao.UserMyself;
import com.zextras.carbonio.quarkus.extensions.confighierarchical.ConfigAdminService;
import com.zextras.carbonio.quarkus.extensions.confighierarchical.ConfigResolver;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.CookieParam;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
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
 * Hierarchical-config endpoints.
 *
 * <ul>
 *   <li>{@code GET /config} — the effective config of the authenticated caller: every declared
 *       files key resolved against the caller's own account &gt; cos &gt; domain (cos/domain now
 *       come from user-management). Normal user auth ({@code ZM_AUTH_TOKEN}).
 *   <li>{@code PUT /config/admin/{scope}/{scopeId}} — set a per-scope override (scope =
 *       account|cos|domain). Global-admin only ({@code ZM_ADMIN_AUTH_TOKEN}); generic in the key so
 *       the client passes any declared key + value.
 * </ul>
 */
@ApplicationScoped
@Path("/config")
@Produces(MediaType.APPLICATION_JSON)
public class ConfigResource {

  private final BlobAuthenticator authenticator;
  private final AdminAuthenticator adminAuthenticator;
  private final ConfigResolver configResolver;
  private final ConfigAdminService configAdminService;

  @Inject
  public ConfigResource(
      BlobAuthenticator authenticator,
      AdminAuthenticator adminAuthenticator,
      ConfigResolver configResolver,
      ConfigAdminService configAdminService) {
    this.authenticator = authenticator;
    this.adminAuthenticator = adminAuthenticator;
    this.configResolver = configResolver;
    this.configAdminService = configAdminService;
  }

  /** Body for a per-scope config write: the key and its value. */
  public record SetConfigRequest(String key, String value) {}

  @GET
  public RestResponse<Map<String, String>> getMyConfig(
      @HeaderParam("Cookie") String cookieHeader,
      @CookieParam(Headers.COOKIE_ZM_AUTH_TOKEN) String zmToken) {
    UserMyself requester = authenticator.requireUser(cookieHeader, zmToken);

    Optional<String> accountId = Optional.ofNullable(requester.getId()).map(id -> id.getUserId());
    Optional<String> cosId = Optional.ofNullable(requester.getCosId());
    Optional<String> domainId = Optional.ofNullable(requester.getDomainId());

    Map<String, String> effective = new LinkedHashMap<>();
    for (String key : HierarchicalConfigKeys.ALL_KEYS) {
      effective.put(key, configResolver.get(accountId, cosId, domainId, key).orElse(null));
    }
    return RestResponse.ok(effective);
  }

  @PUT
  @Path("/admin/{scope}/{scopeId}")
  @Consumes(MediaType.APPLICATION_JSON)
  public RestResponse<Void> setConfig(
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

    switch (scope == null ? "" : scope.toLowerCase(Locale.ROOT)) {
      case "account" -> configAdminService.setForAccount(scopeId, body.key(), body.value());
      case "cos" -> configAdminService.setForCos(scopeId, body.key(), body.value());
      case "domain" -> configAdminService.setForDomain(scopeId, body.key(), body.value());
      default -> throw badRequest("Unknown scope '" + scope + "' (expected account|cos|domain)");
    }
    return RestResponse.status(Response.Status.NO_CONTENT);
  }

  private WebApplicationException badRequest(String reason) {
    return new WebApplicationException(
        Response.status(Response.Status.BAD_REQUEST).entity(reason).type("text/plain").build());
  }
}
