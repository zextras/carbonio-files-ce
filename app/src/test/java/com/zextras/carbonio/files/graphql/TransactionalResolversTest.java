// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.graphql;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import jakarta.transaction.Transactional;
import jakarta.transaction.Transactional.TxType;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.eclipse.microprofile.graphql.GraphQLApi;
import org.junit.jupiter.api.Test;

/**
 * Resolvers are transactional by default (Quarkus adds {@link Transactional} to them): only the
 * resolvers calling the blob storage opt out.
 */
class TransactionalResolversTest {

  @Test
  void onlyBlobStorageResolversOptOutOfTheResolverTransaction() {
    List<String> apis = new ArrayList<>();
    Map<String, TxType> transactional = new TreeMap<>();
    for (JavaClass javaClass :
        new ClassFileImporter().importPackages("com.zextras.carbonio.files")) {
      if (!javaClass.isAnnotatedWith(GraphQLApi.class)) {
        continue;
      }
      apis.add(javaClass.getSimpleName());
      for (JavaMethod method : javaClass.getMethods()) {
        if (method.getModifiers().contains(JavaModifier.PUBLIC)
            && method.isAnnotatedWith(Transactional.class)) {
          transactional.put(
              javaClass.getSimpleName() + "#" + method.getName(),
              method.getAnnotationOfType(Transactional.class).value());
        }
      }
    }

    assertThat(apis).contains("NodeApi", "PublicApi", "ShareApi");
    assertThat(transactional)
        .containsExactlyInAnyOrderEntriesOf(
            Map.of(
                "NodeApi#copyNodes", TxType.NOT_SUPPORTED,
                "NodeApi#cloneVersion", TxType.NOT_SUPPORTED,
                "NodeApi#deleteNodes", TxType.NOT_SUPPORTED,
                "NodeApi#deleteVersions", TxType.NOT_SUPPORTED));
  }

  @Test
  void noGraphQLApiClassIsTransactional() {
    List<String> annotated = new ArrayList<>();
    for (JavaClass javaClass :
        new ClassFileImporter().importPackages("com.zextras.carbonio.files")) {
      if (javaClass.isAnnotatedWith(GraphQLApi.class)
          && javaClass.isAnnotatedWith(Transactional.class)) {
        annotated.add(javaClass.getSimpleName());
      }
    }

    assertThat(annotated).isEmpty();
  }
}
