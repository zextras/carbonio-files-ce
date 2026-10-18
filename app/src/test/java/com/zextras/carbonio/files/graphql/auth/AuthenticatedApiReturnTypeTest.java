// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.graphql.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.zextras.carbonio.files.graphql.api.NodeApi;
import io.quarkus.security.Authenticated;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import jakarta.annotation.security.PermitAll;
import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.stream.Stream;
import org.eclipse.microprofile.graphql.GraphQLApi;
import org.junit.jupiter.api.Test;

class AuthenticatedApiReturnTypeTest {

  private static final List<Class<?>> ASYNC_TYPES =
      List.of(Uni.class, Multi.class, CompletionStage.class);

  static List<Class<?>> graphQLApiClasses() throws IOException, URISyntaxException {
    Path apiDir =
        Path.of(NodeApi.class.getProtectionDomain().getCodeSource().getLocation().toURI())
            .resolve(NodeApi.class.getPackageName().replace('.', '/'));
    List<Class<?>> classes = new ArrayList<>();
    try (Stream<Path> files = Files.list(apiDir)) {
      for (Path file : files.filter(f -> f.toString().endsWith(".class")).toList()) {
        String name = file.getFileName().toString().replaceAll("\\.class$", "");
        try {
          Class<?> clazz = Class.forName(NodeApi.class.getPackageName() + "." + name);
          if (clazz.isAnnotationPresent(GraphQLApi.class)) {
            classes.add(clazz);
          }
        } catch (ClassNotFoundException e) {
          throw new IllegalStateException(e);
        }
      }
    }
    return classes;
  }

  private static boolean isAuthenticated(Class<?> clazz, Method method) {
    if (method.isAnnotationPresent(PermitAll.class)) {
      return false;
    }
    return method.isAnnotationPresent(Authenticated.class)
        || clazz.isAnnotationPresent(Authenticated.class);
  }

  @Test
  void noAuthenticatedGraphQLMethodReturnsAnAsyncType() throws Exception {
    List<Class<?>> apis = graphQLApiClasses();
    List<String> offenders = new ArrayList<>();
    int inspected = 0;

    for (Class<?> api : apis) {
      for (Method method : api.getDeclaredMethods()) {
        if (!Modifier.isPublic(method.getModifiers()) || !isAuthenticated(api, method)) {
          continue;
        }
        inspected++;
        if (ASYNC_TYPES.stream().anyMatch(type -> type.isAssignableFrom(method.getReturnType()))) {
          offenders.add(api.getSimpleName() + "#" + method.getName());
        }
      }
    }

    assertThat(apis).as("@GraphQLApi classes found").isNotEmpty();
    assertThat(inspected).as("authenticated methods inspected").isPositive();
    assertThat(offenders)
        .as("UnauthenticatedMessageInterceptor cannot cover methods that return before running")
        .isEmpty();
  }
}
