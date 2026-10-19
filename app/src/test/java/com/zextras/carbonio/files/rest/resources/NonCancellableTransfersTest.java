// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.rest.resources;

import static org.assertj.core.api.Assertions.assertThat;

import com.zextras.carbonio.files.TestUtils;
import io.smallrye.mutiny.Uni;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.jboss.resteasy.reactive.server.Cancellable;
import org.junit.jupiter.api.Test;

/**
 * Every {@link Uni} endpoint hands the request context (uploads) or an already-open stream
 * (downloads, previews) to its Uni: RESTEasy Reactive must neither cancel it nor skip subscribing
 * it when the client disconnects. Resolves {@link Cancellable} like RESTEasy Reactive does (method
 * first, then class; cancellable when absent).
 */
class NonCancellableTransfersTest {

  @Test
  void everyUniEndpointIsNotCancellable() {
    List<String> uniEndpoints = new ArrayList<>();
    List<String> cancellable = new ArrayList<>();
    for (Class<?> resource : TestUtils.annotatedClassesBeside(BlobResource.class, Path.class)) {
      for (Method method : resource.getDeclaredMethods()) {
        if (!method.getReturnType().equals(Uni.class)
            || Stream.of(GET.class, POST.class, PUT.class, DELETE.class)
                .noneMatch(method::isAnnotationPresent)) {
          continue;
        }
        String endpoint = resource.getSimpleName() + "#" + method.getName();
        uniEndpoints.add(endpoint);
        if (Optional.ofNullable(method.getAnnotation(Cancellable.class))
            .or(() -> Optional.ofNullable(resource.getAnnotation(Cancellable.class)))
            .map(Cancellable::value)
            .orElse(true)) {
          cancellable.add(endpoint);
        }
      }
    }

    assertThat(uniEndpoints).contains("BlobResource#upload", "BlobResource#download");
    assertThat(cancellable).isEmpty();
  }
}
