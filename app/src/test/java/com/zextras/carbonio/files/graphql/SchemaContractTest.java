// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.graphql;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * Executable contract gate for the schema-first -> code-first GraphQL migration. It renders the
 * SmallRye code-first schema from the compiled {@code model} + {@code api} classes and asserts it
 * is SEMANTICALLY equal to the frozen {@code docs/schema.graphql}.
 *
 * <p>A plain JUnit 5 test (no {@code @QuarkusTest}, no container boot) so it runs under {@code mvn
 * test}. It is RED until phases 2-5 add the code-first classes: with no such classes the rendered
 * schema is empty and differs from the full frozen contract. The comparison mechanism itself is
 * proven independently by {@link SchemaComparisonTest}.
 */
class SchemaContractTest {

  private static final List<Path> CODE_FIRST_CLASS_ROOTS =
      List.of(
          Path.of("target", "classes", "com", "zextras", "carbonio", "files", "graphql", "model"),
          Path.of("target", "classes", "com", "zextras", "carbonio", "files", "graphql", "api"));

  private static final Path FROZEN_SCHEMA = Path.of("..", "docs", "schema.graphql");

  // Enum values carried in the shared NotificationType enum solely so the Advanced edition's union
  // types can reference them across the jar boundary (a Java enum can't be extended by another
  // module). They are edition-reserved and deliberately NOT part of CE's frontend contract
  // (docs/schema.graphql), so they are stripped from the rendered CE schema before the comparison.
  private static final Set<String> EDITION_RESERVED_ENUM_VALUES =
      Set.of("TRANSFERRED_OWNERSHIP", "SUCCEEDED_RECORDING");

  @Test
  void codeFirstSchemaMatchesFrozenContract() throws IOException {
    String frozenSdl = Files.readString(FROZEN_SCHEMA, StandardCharsets.UTF_8);
    String frozen = GraphqlSchemaRendering.renderFromSdl(frozenSdl);
    String codeFirst =
        GraphqlSchemaRendering.renderFromClassRoots(CODE_FIRST_CLASS_ROOTS)
            .lines()
            .filter(line -> !EDITION_RESERVED_ENUM_VALUES.contains(line.strip()))
            .collect(Collectors.joining("\n"));

    assertThat(frozen)
        .as("frozen contract docs/schema.graphql must render to a non-empty canonical schema")
        .isNotBlank();
    assertThat(codeFirst)
        .as(
            "code-first schema (rendered from %s) must equal the frozen contract",
            CODE_FIRST_CLASS_ROOTS)
        .isEqualTo(frozen);
  }
}
