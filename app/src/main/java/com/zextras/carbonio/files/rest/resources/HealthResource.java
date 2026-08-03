// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.rest.resources;

import com.zextras.carbonio.files.rest.services.HealthService;
import com.zextras.carbonio.files.rest.types.health.DependencyType;
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
import java.util.ArrayList;
import java.util.List;

@Path("/health")
@ApplicationScoped
public class HealthResource {

  private final HealthService healthService;

  @Inject
  public HealthResource(HealthService healthService) {
    this.healthService = healthService;
  }

  @GET
  @Path("/live")
  public Response healthLive() {
    return Response.noContent().build();
  }

  @GET
  @Path("/ready")
  @Blocking
  public Response healthReady() {
    boolean databaseIsUp = healthService.isDatabaseLive();
    boolean userManagementIsUp = healthService.isUserManagementLive();
    boolean fileStoreIsUp = healthService.isStoragesLive();

    if (databaseIsUp && userManagementIsUp && fileStoreIsUp) {
      return Response.noContent().build();
    }
    return Response.serverError().build();
  }

  @GET
  @Produces(MediaType.APPLICATION_JSON)
  @Blocking
  public Response health() {
    List<ServiceHealth> dependencies = new ArrayList<>();
    dependencies.add(healthService.getDatabaseHealth());
    dependencies.add(healthService.getUserManagementHealth());
    dependencies.add(healthService.getStoragesHealth());
    dependencies.add(healthService.getPreviewHealth());
    dependencies.add(healthService.getDocsConnectorHealth());
    dependencies.add(healthService.getMessageBrokerHealth());

    boolean filesIsReady = dependencies
        .stream()
        .filter(dep -> DependencyType.REQUIRED.equals(dep.getType()))
        .allMatch(ServiceHealth::isReady);

    HealthResponse healthResponse = new HealthResponse()
        .setDependencies(dependencies)
        .setReady(filesIsReady);

    Response.Status status = filesIsReady
        ? Response.Status.OK
        : Response.Status.INTERNAL_SERVER_ERROR;

    return Response.status(status).entity(healthResponse).build();
  }
}
