// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.rest.resources;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import io.smallrye.mutiny.Uni;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import java.util.ArrayList;
import java.util.List;
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
    for (JavaClass javaClass :
        new ClassFileImporter().importPackages("com.zextras.carbonio.files")) {
      if (!javaClass.isAnnotatedWith(Path.class)) {
        continue;
      }
      for (JavaMethod method : javaClass.getMethods()) {
        if (!method.getRawReturnType().isEquivalentTo(Uni.class)
            || Stream.of(GET.class, POST.class, PUT.class, DELETE.class)
                .noneMatch(method::isAnnotatedWith)) {
          continue;
        }
        String endpoint = javaClass.getSimpleName() + "#" + method.getName();
        uniEndpoints.add(endpoint);
        if (method
            .tryGetAnnotationOfType(Cancellable.class)
            .or(() -> javaClass.tryGetAnnotationOfType(Cancellable.class))
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
