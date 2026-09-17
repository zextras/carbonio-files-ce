// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.graphql;

import graphql.language.InterfaceTypeDefinition;
import graphql.language.UnionTypeDefinition;
import graphql.schema.Coercing;
import graphql.schema.GraphQLScalarType;
import graphql.schema.GraphQLSchema;
import graphql.schema.GraphqlTypeComparatorRegistry;
import graphql.schema.TypeResolver;
import graphql.schema.idl.RuntimeWiring;
import graphql.schema.idl.SchemaGenerator;
import graphql.schema.idl.SchemaParser;
import graphql.schema.idl.SchemaPrinter;
import graphql.schema.idl.TypeDefinitionRegistry;
import io.smallrye.graphql.bootstrap.Bootstrap;
import io.smallrye.graphql.schema.SchemaBuilder;
import io.smallrye.graphql.schema.model.Schema;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.jboss.jandex.Index;
import org.jboss.jandex.IndexView;
import org.jboss.jandex.Indexer;

/**
 * Renders a GraphQL schema to a canonical, description-free, directive-free SDL string so two
 * schemas can be compared for SEMANTIC equality by plain string equality. Both the code-first
 * schema (built from compiled classes via SmallRye) and the frozen SDL contract (parsed with
 * graphql-java) are routed through {@link #canonicalPrint} with the SAME printer, so element order,
 * whitespace, trailing commas, argument order and descriptions/comments are normalised away while
 * every real difference (type, nullability, list-wrapping, argument, enum value, union member)
 * survives.
 */
final class GraphqlSchemaRendering {

  private static final Set<String> BUILT_IN_SCALARS =
      Set.of("Int", "Float", "String", "Boolean", "ID");

  private GraphqlSchemaRendering() {}

  /**
   * Renders the code-first schema from the given compiled class-dir roots (each the package dir of
   * the {@code model} / {@code api} classes). Non-existent roots are skipped. When no class is
   * found the schema is empty and this returns the empty string, so the caller compares a real
   * (non-empty) contract against an empty render and gets a clean assertion mismatch rather than a
   * crash.
   */
  static String renderFromClassRoots(List<Path> roots) {
    List<Path> classFiles = collectClassFiles(roots);
    if (classFiles.isEmpty()) {
      return "";
    }
    Indexer indexer = new Indexer();
    for (Path classFile : classFiles) {
      try (InputStream in = Files.newInputStream(classFile)) {
        indexer.index(in);
      } catch (IOException e) {
        throw new UncheckedIOException("Failed to index " + classFile, e);
      }
    }
    return renderFromIndex(indexer.complete());
  }

  /** Renders the code-first schema from an already-built Jandex index. */
  static String renderFromIndex(IndexView index) {
    Schema schemaModel = SchemaBuilder.build(index);
    GraphQLSchema schema = Bootstrap.bootstrap(schemaModel, true);
    return canonicalPrint(schema);
  }

  /** Renders the code-first schema from the given classes (used by the render-pipeline proof). */
  static String renderFromClasses(Class<?>... classes) throws IOException {
    return renderFromIndex(Index.of(classes));
  }

  /**
   * Parses a frozen SDL string and renders it to the SAME canonical form. The wiring required by
   * {@code makeExecutableSchema} (a {@link TypeResolver} per interface/union, a no-op {@link
   * Coercing} per custom scalar) is derived from the parsed registry, so this works for the frozen
   * contract (interfaces Node/PublicNode, unions SharedTarget/Account/Notification, scalar
   * BigInteger) and for any small property-test fixture alike.
   */
  static String renderFromSdl(String sdl) {
    TypeDefinitionRegistry registry = new SchemaParser().parse(sdl);
    RuntimeWiring wiring = wiringFor(registry);
    GraphQLSchema schema = new SchemaGenerator().makeExecutableSchema(registry, wiring);
    return canonicalPrint(schema);
  }

  private static RuntimeWiring wiringFor(TypeDefinitionRegistry registry) {
    TypeResolver dummyResolver = env -> null;
    RuntimeWiring.Builder builder = RuntimeWiring.newRuntimeWiring();

    for (InterfaceTypeDefinition iface : registry.getTypes(InterfaceTypeDefinition.class)) {
      builder.type(iface.getName(), typeWiring -> typeWiring.typeResolver(dummyResolver));
    }
    for (UnionTypeDefinition union : registry.getTypes(UnionTypeDefinition.class)) {
      builder.type(union.getName(), typeWiring -> typeWiring.typeResolver(dummyResolver));
    }
    registry.scalars().keySet().stream()
        .filter(name -> !BUILT_IN_SCALARS.contains(name))
        .forEach(
            name ->
                builder.scalar(
                    GraphQLScalarType.newScalar()
                        .name(name)
                        .coercing(new Coercing<Object, Object>() {})
                        .build()));

    return builder.build();
  }

  /**
   * Prints a schema to a canonical SDL string: scalars included, schema definition / directives /
   * descriptions excluded, and every element sorted by name so order is irrelevant. Descriptions
   * are forced to {@code #} comments and then stripped together with blank lines, so no description
   * can leak into the comparison.
   */
  static String canonicalPrint(GraphQLSchema schema) {
    SchemaPrinter.Options options =
        SchemaPrinter.Options.defaultOptions()
            .includeScalarTypes(true)
            .includeSchemaDefinition(false)
            .includeIntrospectionTypes(false)
            .includeDirectiveDefinitions(false)
            .includeDirectives(false)
            .descriptionsAsHashComments(true)
            .setComparators(GraphqlTypeComparatorRegistry.BY_NAME_REGISTRY);
    String printed = new SchemaPrinter(options).print(schema);
    return stripNoise(printed);
  }

  private static String stripNoise(String sdl) {
    return sdl.lines()
        .map(String::stripTrailing)
        .filter(
            line -> {
              String trimmed = line.strip();
              return !trimmed.isEmpty() && !trimmed.startsWith("#");
            })
        .collect(Collectors.joining("\n"));
  }

  private static List<Path> collectClassFiles(List<Path> roots) {
    return roots.stream()
        .filter(root -> root != null && Files.isDirectory(root))
        .flatMap(GraphqlSchemaRendering::walkClassFiles)
        .toList();
  }

  private static Stream<Path> walkClassFiles(Path root) {
    try (Stream<Path> walk = Files.walk(root)) {
      return walk
          .filter(Files::isRegularFile)
          .filter(path -> path.toString().endsWith(".class"))
          .toList()
          .stream();
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to walk " + root, e);
    }
  }
}
