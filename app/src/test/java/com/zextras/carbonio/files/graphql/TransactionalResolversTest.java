// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.graphql;

import static org.assertj.core.api.Assertions.assertThat;

import com.zextras.carbonio.files.TestUtils;
import com.zextras.carbonio.files.graphql.api.NodeApi;
import jakarta.transaction.Transactional;
import jakarta.transaction.Transactional.TxType;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.eclipse.microprofile.graphql.GraphQLApi;
import org.junit.jupiter.api.Test;

/**
 * Resolvers are transactional by default (Quarkus adds {@link Transactional} to them): only the
 * resolvers calling the blob storage opt out, and no class-level annotation overrides the default.
 */
class TransactionalResolversTest {

  @Test
  void onlyBlobStorageResolversOptOutOfTheResolverTransaction() {
    List<String> apis = new ArrayList<>();
    List<String> transactionalClasses = new ArrayList<>();
    Map<String, TxType> transactional = new TreeMap<>();
    for (Class<?> api : TestUtils.annotatedClassesBeside(NodeApi.class, GraphQLApi.class)) {
      apis.add(api.getSimpleName());
      if (api.isAnnotationPresent(Transactional.class)) {
        transactionalClasses.add(api.getSimpleName());
      }
      for (Method method : api.getDeclaredMethods()) {
        if (Modifier.isPublic(method.getModifiers())
            && method.isAnnotationPresent(Transactional.class)) {
          transactional.put(
              api.getSimpleName() + "#" + method.getName(),
              method.getAnnotation(Transactional.class).value());
        }
      }
    }

    assertThat(apis).contains("NodeApi", "PublicApi", "ShareApi");
    assertThat(transactionalClasses).isEmpty();
    assertThat(transactional)
        .containsExactlyInAnyOrderEntriesOf(
            Map.of(
                "NodeApi#copyNodes", TxType.NOT_SUPPORTED,
                "NodeApi#cloneVersion", TxType.NOT_SUPPORTED,
                "NodeApi#deleteNodes", TxType.NOT_SUPPORTED,
                "NodeApi#deleteVersions", TxType.NOT_SUPPORTED));
  }
}
