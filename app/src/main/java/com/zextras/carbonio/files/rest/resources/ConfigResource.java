// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.rest.resources;

import com.zextras.carbonio.files.Constants.API.Headers;
import com.zextras.carbonio.files.config.HierarchicalConfigKeys;
import com.zextras.carbonio.files.dal.dao.UserMyself;
import com.zextras.carbonio.quarkus.extensions.confighierarchical.ConfigResolver;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.CookieParam;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jboss.resteasy.reactive.RestResponse;

/**
 * The authenticated caller's effective hierarchical config.
 *
 * <p>{@code GET /config} returns a complete, stable snapshot: every declared key ({@link
 * HierarchicalConfigKeys#ALL_KEYS}) resolved against the caller's own account &gt; cos &gt; domain
 * (cos/domain now come from user-management), falling back to the base default. Every key is always
 * present; the value is the resolved string, or {@code null} when the effective value is
 * empty/unset. This is the resolved view — for the raw per-scope overrides (admin) see {@link
 * AdminConfigResource}.
 */
@ApplicationScoped
@Path("/config")
@Produces(MediaType.APPLICATION_JSON)
public class ConfigResource {

  private final BlobAuthenticator authenticator;
  private final ConfigResolver configResolver;

  @Inject
  public ConfigResource(BlobAuthenticator authenticator, ConfigResolver configResolver) {
    this.authenticator = authenticator;
    this.configResolver = configResolver;
  }

  @GET
  public RestResponse<Map<String, String>> getMyConfig(
      @HeaderParam("Cookie") String cookieHeader,
      @CookieParam(Headers.COOKIE_ZM_AUTH_TOKEN) String zmToken,
      @QueryParam("key") String key) {
    UserMyself requester = authenticator.requireUser(cookieHeader, zmToken);

    Optional<String> accountId = Optional.ofNullable(requester.getId()).map(id -> id.getUserId());
    Optional<String> cosId = Optional.ofNullable(requester.getCosId());
    Optional<String> domainId = Optional.ofNullable(requester.getDomainId());

    // ?key=<key> resolves that single key; otherwise the full set of declared keys.
    List<String> keys =
        (key != null && !key.isBlank()) ? List.of(key) : HierarchicalConfigKeys.ALL_KEYS;

    Map<String, String> effective = new LinkedHashMap<>();
    for (String k : keys) {
      effective.put(k, configResolver.get(accountId, cosId, domainId, k).orElse(null));
    }
    return RestResponse.ok(effective);
  }
}
