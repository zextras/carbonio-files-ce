// SPDX-FileCopyrightText: 2022 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.config;

import com.zextras.carbonio.files.dal.repositories.impl.NodeRepositoryImpl;
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
import com.zextras.carbonio.user_management.sdk.rest.model.MyselfDto;
import com.zextras.carbonio.user_management.sdk.rest.model.UserInfoDto;
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
 *       already return Maps, which need no reflection.)
 *   <li><b>Jackson JSON DTOs serialized/deserialized manually (not through a JAX-RS body)</b> — the
 *       upload/blob/preview REST DTOs are returned through an opaque {@code
 *       jakarta.ws.rs.core.Response} or serialized manually, so Quarkus REST cannot infer the type
 *       and they are registered defensively. This category also covers {@link
 *       NodeRepositoryImpl.PageToken}, the keyset pagination cursor that {@code NodeRepositoryImpl}
 *       (de)serialises with a raw {@link com.fasterxml.jackson.databind.ObjectMapper} to Base64
 *       JSON; it never appears in any JAX-RS/GraphQL type signature Quarkus scans, so without this
 *       entry the native image cannot introspect it and every paginated {@code children}/{@code
 *       findNodes} response throws "Unable to serialize page token".
 * </ul>
 */
@RegisterForReflection(
    targets = {
      // graphql-java property data fetching (public schema)
      PublicNode.class,
      Permissions.class,
      // Jackson JSON DTOs returned/consumed via an opaque Response or serialized manually
      BlobResponse.class,
      UploadAttachmentResponse.class,
      UploadVersionResponse.class,
      UploadToRequest.class,
      PreviewQueryParameters.class,
      // Keyset pagination cursor (Base64 JSON via a raw ObjectMapper in NodeRepositoryImpl)
      NodeRepositoryImpl.PageToken.class,
      // carbonio-user-management REST SDK response DTOs deserialized by UserRepositoryImpl via an
      // opaque generated client (getUserMyselfByCookie -> authentication; getUserById -> owner /
      // account resolution). Without these the native image throws on every authenticated request.
      MyselfDto.class,
      UserInfoDto.class,
      // /health response graph, serialized manually through an opaque Response by HealthResource
      HealthResponse.class,
      ServiceHealth.class,
      DependencyType.class
    })
public final class NativeReflectionConfig {
  private NativeReflectionConfig() {}
}
