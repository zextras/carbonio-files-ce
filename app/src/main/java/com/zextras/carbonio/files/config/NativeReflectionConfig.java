// SPDX-FileCopyrightText: 2022 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.config;

import com.zextras.carbonio.files.graphql.types.Permissions;
import com.zextras.carbonio.files.graphql.types.PublicNode;
import com.zextras.carbonio.files.rest.types.BlobResponse;
import com.zextras.carbonio.files.rest.types.PreviewQueryParameters;
import com.zextras.carbonio.files.rest.types.UploadAttachmentResponse;
import com.zextras.carbonio.files.rest.types.UploadToRequest;
import com.zextras.carbonio.files.rest.types.UploadVersionResponse;
import com.zextras.carbonio.files.rest.types.health.DependencyType;
import com.zextras.carbonio.files.rest.types.health.HealthResponse;
import com.zextras.carbonio.files.rest.types.health.ServiceHealth;
import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * Registers, for the GraalVM native image, the POJOs that are (de)serialized by Jackson or read
 * reflectively by graphql-java at runtime but that Quarkus does not auto-register at build time.
 *
 * <p>Two categories:
 *
 * <ul>
 *   <li><b>graphql-java property data fetching</b> — the public schema DataFetchers return {@link
 *       PublicNode} / {@link Permissions} POJOs (rather than {@code Map<String,Object>}); graphql's
 *       default {@code PropertyDataFetcher} reads their fields reflectively. (Most other fetchers
 *       already return Maps, which need no reflection.)</li>
 *   <li><b>Jackson JSON DTOs serialized/deserialized manually (not through a JAX-RS body)</b> — most
 *       notably {@link HealthResponse}/{@link ServiceHealth}/{@link DependencyType}, which {@code
 *       HealthResource#health} writes with {@code ObjectMapper.writeValueAsString(...)} and returns
 *       through an opaque {@code jakarta.ws.rs.core.Response}, so Quarkus REST cannot infer the type.
 *       The upload/blob/preview REST DTOs are registered defensively for the same reason.</li>
 * </ul>
 */
@RegisterForReflection(
    targets = {
      // graphql-java property data fetching (public schema)
      PublicNode.class,
      Permissions.class,
      // Jackson JSON DTOs returned/consumed via an opaque Response or serialized manually
      HealthResponse.class,
      ServiceHealth.class,
      DependencyType.class,
      BlobResponse.class,
      UploadAttachmentResponse.class,
      UploadVersionResponse.class,
      UploadToRequest.class,
      PreviewQueryParameters.class
    })
public final class NativeReflectionConfig {
  private NativeReflectionConfig() {}
}
