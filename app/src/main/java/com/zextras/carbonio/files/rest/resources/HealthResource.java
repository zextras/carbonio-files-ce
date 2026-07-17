// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.rest.resources;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zextras.carbonio.files.rest.services.HealthService;
import com.zextras.carbonio.files.rest.types.health.HealthResponse;
import com.zextras.carbonio.files.rest.types.health.ServiceHealth;
import io.smallrye.common.annotation.Blocking;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.List;

/**
 * Legacy-path health endpoints ({@code /health}, {@code /health/live}, {@code /health/ready}),
 * backing the mesh liveness check declared in {@code package/carbonio-files.hcl} ({@code
 * http://127.78.0.2:10000/health/live/}). Quarkus/JAX-RS port of the legacy Netty {@code
 * HealthController}.
 *
 * <p><b>House pattern (see carbonio-knowledge "Carbonio health-check house pattern"):</b> {@code
 * /health/live} and {@code /health/ready} are gated SELF-ONLY on {@link
 * HealthService#isDatabaseLive()} — a downstream dependency being down (user-management, storages,
 * preview, message-broker) never fails these two endpoints. This is a deliberate change from the
 * legacy behaviour, which required database + user-management + storages to all be live. {@code
 * /health} keeps the legacy JSON SHAPE (a {@code dependencies} list including the downstream
 * services, for informational/observability purposes) but its {@code ready} flag and HTTP status
 * are computed with the same self-only rule, not by aggregating the list.
 */
@Path("/health")
@ApplicationScoped
public class HealthResource {

  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  private final HealthService healthService;

  @Inject
  public HealthResource(HealthService healthService) {
    this.healthService = healthService;
  }

  @GET
  @Path("/live")
  @Blocking
  public Response live() {
    return healthService.isDatabaseLive()
        ? Response.noContent().build()
        : Response.status(Response.Status.INTERNAL_SERVER_ERROR).build();
  }

  @GET
  @Path("/ready")
  @Blocking
  public Response ready() {
    return healthService.isDatabaseLive()
        ? Response.noContent().build()
        : Response.status(Response.Status.INTERNAL_SERVER_ERROR).build();
  }

  @GET
  @Blocking
  @Produces(MediaType.APPLICATION_JSON)
  public Response health() {
    List<ServiceHealth> dependencies =
        List.of(
            healthService.getDatabaseHealth(),
            healthService.getUserManagementHealth(),
            healthService.getStoragesHealth(),
            healthService.getPreviewHealth(),
            healthService.getDocsConnectorHealth(),
            healthService.getMessageBrokerHealth());

    // Self-only gate (see class javadoc): NOT an aggregation of the list above.
    boolean filesIsReady = healthService.isDatabaseLive();

    HealthResponse healthResponse =
        new HealthResponse().setDependencies(dependencies).setReady(filesIsReady);

    String responseBody;
    try {
      responseBody = OBJECT_MAPPER.writeValueAsString(healthResponse);
    } catch (JsonProcessingException e) {
      throw new RuntimeException(e);
    }

    Response.Status status =
        filesIsReady ? Response.Status.OK : Response.Status.INTERNAL_SERVER_ERROR;
    return Response.status(status).entity(responseBody).type(MediaType.APPLICATION_JSON).build();
  }
}
