<!--
SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>

SPDX-License-Identifier: AGPL-3.0-only
-->

# carbonio-files-ce GraphQL: schema-first graphql-java → code-first SmallRye

> For agentic workers: **REQUIRED SUB-SKILL: `superpowers:subagent-driven-development`.** Execute one
> `### Task` at a time, in order. Each checkbox is one bite-sized step; check it off (`- [x]`) only
> after its command/edit is done and (for TDD steps) the stated expected output is observed. Do not
> batch tasks. Do not start a task whose predecessor is not fully green. `mvn` convention (verified
> module layout — `app` is a child module of the reactor root):
> `cd /home/mattogalvagni/IdeaProjects/carbonio-files-ce && ./mvnw -q -pl app test -Dtest=<Class>`.

## Goal

Replace the two schema-first graphql-java engines (`GraphQLProvider` + `PublicGraphQLProvider`,
their `datafetchers/`, `dataloaders/`, `validators/`, hand-written SDL files, custom Vert.x routes
and auth filter) with a single **code-first SmallRye GraphQL** surface driven by
`quarkus-smallrye-graphql` (via the in-house `carbonio-quarkus-extensions-graphql`). The GraphQL
wire contract (types, fields, enum values, argument names, error `errorCode` extension, the two HTTP
endpoints' externally observable behaviour) must not change; only the internal engine changes. A
frozen golden schema (`docs/schema.graphql`) plus one contract test guard every step.

## Architecture

- **Java model layer** (`com.zextras.carbonio.files.graphql.model`): plain POJOs annotated with
  MicroProfile/SmallRye GraphQL annotations (`@Type`, `@Interface`, `@Enum`, `@Union`, `@Name`).
  Scalar fields are POJO getters; relational fields are resolved by `@Source` methods only. Models
  carry non-exposed id-carrier fields (`parentId`, `ownerId`, `creatorId`, `lastEditorId`) that the
  `@Source` batch resolvers consume. Built from the existing Ebean/JPA `Node` DAO (a single entity
  discriminated by `NodeType`/`NodeCategory` — there is no `File`/`Folder` DAO subclass).
- **API layer** (`com.zextras.carbonio.files.graphql.api`): `@GraphQLApi` CDI beans with `@Query`,
  `@Mutation`, and `@Source` methods that delegate 1:1 to the existing repositories / services /
  `PermissionsChecker` (unchanged) already used by the legacy datafetchers.
- **Auth**: HTTP layer permits all on `/graphql`; a custom **OPTIONAL** `HttpAuthenticationMechanism`
  turns the `ZM_AUTH_TOKEN` cookie into a `SecurityIdentity` carrying the authenticated `UserMyself`;
  per-operation `@Authenticated` / `@PermitAll` enforce access; a `@RequestScoped` producer exposes
  `UserMyself` to the APIs.
- **Schema generation**: the extension's build step (`ExtensionsGraphqlProcessor`, config
  `quarkus.carbonio-graphql.store-schema-directory=docs`) renders the SmallRye schema model to SDL
  via `GraphqlSchemaRenderer` and writes `docs/schema.graphql`. The **same renderer** is invoked
  in-JVM by `SchemaContractTest` to gate every change against the frozen target.
- **Cutover**: additive. `quarkus-smallrye-graphql` and the new `/graphql` engine are stood up while
  the legacy graphql-java stack keeps compiling and running; the legacy stack is deleted only in the
  single final teardown task, once every internal and public op is migrated and green.

## Tech Stack

- Quarkus `3.37.3`, Java 21 (`--enable-preview`), Maven (`./mvnw`), uber-jar packaging, GraalVM
  native profile.
- SmallRye GraphQL `2.18.3` (`quarkus-smallrye-graphql`), MicroProfile GraphQL API `2.0`.
- In-house `com.zextras.carbonio.quarkus:carbonio-quarkus-extensions-graphql` (runtime + deployment;
  deployment holds `ExtensionsGraphqlProcessor` + `GraphqlSchemaRenderer` + `CarbonioGraphqlConfig`).
- Retained unchanged: Hibernate ORM Panache + Ebean-style DAOs, `NodeRepository` /
  `ShareRepository` / `LinkRepository` / `CollaborationLinkRepository` / `FileVersionRepository` /
  `NotificationRepository` / `TombstoneRepository` / `UserRepository`, `PermissionsChecker`,
  `FilesConfig`, `Filestore`, `RenameNodeUtils`, `CopyFailureClassifier`.
- Legacy (kept until teardown): `com.graphql-java:graphql-java` (BOM-managed) +
  `com.graphql-java:java-dataloader:6.0.0`, `commons-validator:1.9.0`.

---

## Locked Contract & Conventions

Authoritative. Every task conforms to this; when in doubt, re-read this section.

1. **Model package** `com.zextras.carbonio.files.graphql.model`. Internal `@Type`/`@Interface`
   classes use the suffix `Model`: `NodeModel` (interface), `FolderModel`, `FileModel`, `ShareModel`,
   `LinkModel`, `CollaborationLinkModel`, `UserModel`, `ConfigModel`, `DistributionListModel`,
   `PermissionsModel`, `RootModel`, `SnapshotNodeModel`, `SnapshotUserModel`, `NewShareModel`,
   `AddedNodeModel`, `RemovedNodeModel`, `NodePageModel`, `NotificationPageModel`. Every one carries a
   `@Type("<SdlName>")` / `@Interface("<SdlName>")` so the GraphQL type name is the SDL name (never
   `*Model`). **Unions** via a `@Union` marker interface (`io.smallrye.graphql.api.Union`, attribute
   `value()` = SDL name): `SharedTarget`, `Account`, `Notification` (no `Model` suffix). **Enums**:
   ONE `@Enum` class per enum, NO suffix, `@Enum("<SdlName>")`, exact SDL name and values (pinned
   below). **Public types**: plain `Public*` — `PublicNode` (interface), `PublicFolder`,
   `PublicFile`, `PublicNodePage`.
2. **`@GraphQLApi` beans** in `com.zextras.carbonio.files.graphql.api`: `NodeApi`, `ShareApi`,
   `LinkApi`, `CollaborationLinkApi`, `UserApi`, `ConfigApi`, `NotificationApi`, `PublicApi`. Each
   class is authored EXACTLY ONCE (no path collisions, no split partials).
3. **Field resolution**: scalar fields are POJO getters. RELATIONAL fields — `creator`, `owner`,
   `last_editor`, `permissions`, `parent`, `share`, `shares`, `links`, `collaboration_links`, and
   `Folder.children` — are resolved ONLY by `@Source` methods; NEVER both a POJO field and a
   `@Source` for the same field. Models carry non-exposed id-carrier fields (`parentId`, `ownerId`,
   `creatorId`, `lastEditorId`) — annotate them `@Ignore` so they never surface in the schema.
   `NodeModel` is the full 13-member interface; `FolderModel`/`FileModel` implement it and add extras
   (File: `extension`, `mime_type`, `size`, `version`, `digest`, `keep_forever`,
   `cloned_from_version`; Folder: `children`).
4. **Batching**: the `@Source` resolvers for `parent`/`shares`/`creator`/`owner`/`last_editor` are the
   SINGLE definition of those fields, implemented as SmallRye **batch `@Source`** (source parameter
   typed `List<T>`, return `List<R>` positionally aligned) where N+1 matters (Node / Share / User;
   users cap at 100 ids per user-management call). `permissions`, `links`, `collaboration_links`,
   `share(share_target_id)`, `DistributionList.users` are single-item `@Source` (they run a
   per-node permission check, matching the legacy per-node fetchers). NO single-item duplicate of any
   batched field.
5. **DateTime → Java primitive `long`** (epoch) → SDL scalar **`BigInteger`** (wire unchanged; nullable
   timestamps use `Long`). VERIFIED: SmallRye `Scalars` maps `long`/`Long`/`BigInteger` to the
   `BigInteger` scalar (`smallrye-graphql-schema-model` `Scalars` static init maps `Long.TYPE` →
   `"BigInteger"`). Every `@Name` matches the SDL EXACTLY (mostly snake_case; `rootId` stays
   `rootId`; `__typename` never customised).
6. **Golden gate**: exactly ONE test
   `app/src/test/java/com/zextras/carbonio/files/graphql/SchemaContractTest.java`, ONE baseline at
   `docs/schema.graphql` (reactor root). The baseline is the FROZEN TARGET, authored ONCE up front by
   hand-translating today's two SDLs into the unified target (DateTime→BigInteger; public types/ops
   renamed to `Public*`/`findPublicNodes`; merged into one schema; ONE `NodeType` incl `ROOT`),
   committed before implementation. The test regenerates the schema in-JVM through the SAME
   `GraphqlSchemaRenderer` the build step uses (one single rendering source — not the live endpoint),
   and asserts semantic equality (order-insensitive, description-stripped) with the frozen
   `docs/schema.graphql`. Every task moves the generated schema TOWARD the frozen target; the gate
   fails on any divergence. Do NOT re-seed the baseline from generated output to make a red test
   green — fix the code. (The build step also writes `docs/schema.graphql` on `package`; it is
   byte-identical when the code matches the target. Regenerating+committing the baseline happens ONLY
   when the target itself is intentionally changed.)
7. **Public surface** lives in the ONE unified schema: `PublicApi` `@Query`
   `getPublicNode(node_link_id: String!, access_code: String): PublicNode` and
   `findPublicNodes(folder_id: ID!, limit: Int, node_link_id: String, access_code: String, page_token: String): PublicNodePage`,
   both `@PermitAll`, returning ONLY `Public*` restricted types (fields: `id`, `created_at: long`,
   `updated_at: long`, `name`, `type`, `extension`, `mime_type`, `size`; `PublicNodePage`:
   `page_token`, `nodes: [PublicNode]`). Internal ops are `@Authenticated`. The internal `findNodes`
   KEEPS its name `findNodes`; only the PUBLIC one is `findPublicNodes`. Any assertion checks public
   query NAMES and RETURN TYPES, never `doesNotContain("findNodes(")`.
8. **Auth**: `/graphql` permit-all at HTTP; per-op `@Authenticated`
   (`io.quarkus.security.Authenticated`) / `@PermitAll` (`jakarta.annotation.security.PermitAll`); a
   single custom OPTIONAL `HttpAuthenticationMechanism`
   (`com.zextras.carbonio.files.graphql.auth.FilesGraphQLAuthMechanism`) turning the `ZM_AUTH_TOKEN`
   cookie into a `SecurityIdentity` carrying `UserMyself` (reuse `UserRepository.getUserMyselfByCookie`
   + the ACTIVE / not-GUEST / `carbonioFeatureFilesEnabled` checks ported from
   `FilesAuthenticationFilter`). ONE `@RequestScoped` producer exposes the authenticated `UserMyself`
   to resolvers; all APIs use it (no bypass). Introspection: `%prod` no-introspection only;
   schema-available in dev/test, off in prod; configured in ONE place (`application.properties`).
9. **Errors**: ONE hierarchy usable by internal AND public (base `FilesGraphQLException extends
   org.eclipse.microprofile.graphql.GraphQLException`), ONE `ErrorCodes` source, ONE wire key
   (`errorCode`) emitted via one `io.smallrye.graphql.api.ErrorExtensionProvider`. Merge the
   duplicated `NODE_NOT_FOUND` / `LINK_NOT_FOUND` / `ACCESS_CODE_REQUIRED` / `WRONG_ACCESS_CODE`.
   Preserve the 14 codes + partial-success for bulk mutations.
10. **Dependency**: groupId `com.zextras.carbonio.quarkus`, artifact
    `carbonio-quarkus-extensions-graphql`, added in ONE place (`app/pom.xml`). `graphql-java` +
    `java-dataloader` are KEPT until the final teardown task.
11. **Teardown = ONE final task**, after ALL internal AND public ops are migrated and green: delete
    `app/src/main/resources/api/schema.graphql`, `api/public-schema.graphql`, `GraphQLProvider`,
    `PublicGraphQLProvider`, `FilesGraphQLRoutes`, `FilesAuthenticationFilter`,
    `GraphQLSchemaContributor`, `GraphQLWiringContributor`, `GraphQLFieldValidationContributor`,
    `datafetchers/DateTimeScalar`, `SyncCompletableFuture`, `GraphQLRequest`, `datafetchers/`,
    `dataloaders/`, `errors/GraphQLResultErrors` (+ `CopyFailureClassifier`/`DefaultCopyFailureClassifier`
    only if unreferenced after migration — cloneVersion/copyNodes reuse them, so keep them), `validators/`;
    remove `com.graphql-java` (both artifacts) + `commons-validator` from `app/pom.xml`; update
    `NativeReflectionConfig` and native build args. NO earlier task deletes these.
12. **Extension prerequisite (Phase 0)**: a SEPARATE PR in `carbonio-quarkus-extensions/graphql`
    makes `GraphqlSchemaRenderer` emit the used non-spec scalar declarations (so `scalar BigInteger`
    appears) and exposes `render(...)` publicly; published as a NEW ext version; files-ce bumped to it
    BEFORE the baseline is frozen.
13. **Uncertain SmallRye SPI = VERIFY step** (read the 2.18.3 jars under `~/.m2`) with a stated
    fallback — never asserted as fact. Applies to: batch `@Source` ordering + how to cap batch size,
    `ErrorExtensionProvider` discovery, introspection toggle semantics.

### Pinned enums (exact `@Enum` classes, exact values)

- `SharePermission` { `READ_ONLY`, `READ_AND_WRITE`, `READ_AND_SHARE`, `READ_WRITE_AND_SHARE` }
- `NodeSort` { `LAST_EDITOR_ASC`, `LAST_EDITOR_DESC`, `NAME_ASC`, `NAME_DESC`, `OWNER_ASC`,
  `OWNER_DESC`, `TYPE_ASC`, `TYPE_DESC`, `UPDATED_AT_ASC`, `UPDATED_AT_DESC`, `SIZE_ASC`, `SIZE_DESC` }
- `ShareSort` { `CREATION_ASC`, `CREATION_DESC`, `TARGET_USER_ASC`, `TARGET_USER_DESC`,
  `SHARE_PERMISSIONS_ASC`, `SHARE_PERMISSIONS_DESC`, `EXPIRATION_ASC`, `EXPIRATION_DESC` }
- `NodeType` { `IMAGE`, `VIDEO`, `AUDIO`, `TEXT`, `SPREADSHEET`, `PRESENTATION`, `FOLDER`,
  `APPLICATION`, `MESSAGE`, `ROOT`, `OTHER` }  — ONE enum, incl `ROOT`, shared by internal AND public.
- `NotificationType` { `NEW_SHARE`, `ADDED_NODE`, `REMOVED_NODE` }
- `AddedNodeType` { `UPLOAD`, `CREATE`, `COPY`, `MOVE` }
- `RemovedNodeType` { `DELETE`, `MOVE` }

### `Node` interface — 13 members (exact `@Name`s)

`id: ID!`, `creator: User!`, `owner: User`, `last_editor: User`, `created_at: DateTime!`,
`updated_at: DateTime!`, `permissions: Permissions!`, `name: String!`, `description: String!`,
`type: NodeType!`, `flagged: Boolean!` (field is `flagged`, NOT `favorite`), `parent: Node`,
`rootId: ID` (camelCase — kept as-is). Plus resolver fields `share(share_target_id: ID!): Share`,
`shares(limit: Int!, cursor: String, sorts: [ShareSort!]): [Share]!`, `links: [Link]!`,
`collaboration_links: [CollaborationLink]!`.

### Domain-mapping facts (read from the real code)

- `Node` DAO getters: `getId()`, `getName()` (strips extension for files), `getFullName()`,
  `getExtension(): Optional<String>`, `getDescription(): Optional<String>`, `getNodeType()`,
  `getNodeCategory()` (ROOT/FOLDER/FILE), `getCreatedAt(): long`, `getUpdatedAt(): long`,
  `getOwnerId()`, `getCreatorId()`, `getParentId(): Optional<String>`,
  `getLastEditorId(): Optional<String>`, `getAncestorsList()`, `getCurrentVersion()`, `getSize()`,
  `getFileVersions(): List<FileVersion>`, `getCustomAttributes()` (flagged is per-requester, from
  `NodeCustomAttributes`). `rootId` = `getAncestorsList().get(0)` (or `getId()` when ROOT), matching
  the legacy `convertNodeToDataFetcherResult`.
- `FileVersion` getters used for File extras: `getVersion()`, `getMimeType()`, `getSize()`,
  `getDigest()`, `isKeptForever()`, `getClonedFromVersion(): Optional<Integer>`, `getLastEditorId()`,
  `getUpdatedAt()`.
- `PermissionsChecker.getPermissions(String nodeId, String requesterId): ACL`; `ACL` →
  `Permissions.build(ACL)` (existing mapping of the 10 booleans). `ACL.SharePermission` is the
  DOMAIN enum; the GraphQL `SharePermission` model enum maps to/from it.
- Existing batch data sources: `NodeRepository.getNodes(List<String>, Optional.empty()): Stream<Node>`
  (NodeBatchLoader), `ShareRepository.getShares(List<String> nodeIds): List<Share>` (ShareBatchLoader),
  `UserRepository.getUsers(List<String> userIds): List<UserInfo>` (UserBatchLoader, ≤100 ids).

---

## File Structure

One responsibility per file. Paths are absolute-from-repo-root.

**Extension repo** (`/home/mattogalvagni/IdeaProjects/carbonio-quarkus-extensions`) — Phase 0 PR:
- `graphql/deployment/src/main/java/com/zextras/carbonio/quarkus/extensions/deployment/GraphqlSchemaRenderer.java`
  — MODIFY: make class + `render(Schema)` public; emit `scalar <Name>` declarations for used non-spec
  scalars.
- `graphql/deployment/src/test/.../GraphqlSchemaRendererTest.java` — MODIFY: assert `scalar BigInteger`
  is emitted.

**files-ce** — created/modified:
- `docs/schema.graphql` — NEW golden baseline (frozen target).
- `app/pom.xml` — MODIFY: add `quarkus-smallrye-graphql` + `carbonio-quarkus-extensions-graphql`; bump
  `carbonio-quarkus-extensions.version`; add test-scope deps for the contract test; (teardown) drop
  `graphql-java`/`java-dataloader`/`commons-validator`.
- `app/src/main/resources/application.properties` — MODIFY: introspection/schema-available/permit-all.
- `app/src/main/java/com/zextras/carbonio/files/graphql/model/*.java` — NEW models/enums/unions (§1).
- `app/src/main/java/com/zextras/carbonio/files/graphql/api/*.java` — NEW 8 `@GraphQLApi` beans (§2).
- `app/src/main/java/com/zextras/carbonio/files/graphql/auth/FilesGraphQLAuthMechanism.java` — NEW.
- `app/src/main/java/com/zextras/carbonio/files/graphql/auth/AuthenticatedUserProducer.java` — NEW
  `@RequestScoped` `UserMyself` producer.
- `app/src/main/java/com/zextras/carbonio/files/graphql/errors/FilesGraphQLException.java` — NEW base.
- `app/src/main/java/com/zextras/carbonio/files/graphql/errors/FilesErrorCodeExtension.java` — NEW
  `ErrorExtensionProvider` (wire key `errorCode`).
- `app/src/main/resources/META-INF/services/io.smallrye.graphql.api.ErrorExtensionProvider` — NEW.
- `app/src/main/java/com/zextras/carbonio/files/graphql/validation/GraphQLInputValidator.java` — NEW
  (ports `GenericControllerEvaluator` primitives).
- `app/src/main/java/com/zextras/carbonio/files/graphql/model/support/NodeModelFactory.java` — NEW
  (`Node` DAO → `FileModel`/`FolderModel`/`RootModel`; the code-first analogue of
  `convertNodeToDataFetcherResult` / `PublicNode.createFromNode`).
- `app/src/test/java/com/zextras/carbonio/files/graphql/SchemaContractTest.java` — NEW golden gate.
- `app/src/main/java/com/zextras/carbonio/files/config/NativeReflectionConfig.java` — MODIFY (Phase 6).
- Deletions per §11 (Phase 6).

---

## Phase 0 — Extension prerequisite + frozen baseline

### Task P0.1: extension emits `scalar BigInteger` and exposes the renderer

**Files:**
`/home/mattogalvagni/IdeaProjects/carbonio-quarkus-extensions/graphql/deployment/src/main/java/com/zextras/carbonio/quarkus/extensions/deployment/GraphqlSchemaRenderer.java`
(modify); `.../deployment/GraphqlSchemaRendererTest.java` (test).

Context: the renderer walks types/interfaces/inputs/enums/unions but never emits a `ScalarTypeDefinition`
for a used non-spec scalar. SmallRye maps `long` → the `BigInteger` scalar, so the generated SDL
references `BigInteger` in field types but never declares `scalar BigInteger`, and a strict parse of
the SDL fails. Fix: collect every scalar `Reference` used by fields/operations/arguments and emit
`scalar X` for each name that is not one of the 5 GraphQL built-in spec scalars.

- [ ] VERIFY the API shape (already read from `~/.m2/.../smallrye-graphql-schema-model-2.18.3.jar`):
      `Reference.getType(): ReferenceType` (enum has `SCALAR`), `Reference.getName(): String`,
      `Field.getReference()`, `Operation extends Field` (has `getReference()` + `getArguments()`),
      `Argument extends Field`, `Schema.getCustomScalarTypes(): List<CustomScalarType>`. Fallback if
      any signature differs: derive the scalar set from `Scalars.isScalar(name)` while building each
      field type ref. Re-run: `javap -p io.smallrye.graphql.schema.model.Reference` on that jar.
- [ ] TDD (extension repo): add to `GraphqlSchemaRendererTest` a case that builds a `Schema` whose one
      `@Query` returns `long` (or a `@Type` with a `long` field) and asserts the rendered SDL contains
      the line `scalar BigInteger`. Run the extension module's tests; expect FAIL (no scalar emitted).
- [ ] Implement in `GraphqlSchemaRenderer`: change `class` → `public class` and `static String render`
      → `public static String render`. In `render`, after collecting the type/interface/enum/union
      definitions, walk all `schema.getQueries()`/`getMutations()`/`getSubscriptions()` (each `Operation`:
      its own `getReference()` + non-source `getArguments()` refs) and every `Type`/`Interface`/`Input`
      field `getReference()`, collect `ref.getName()` where `ref.getType() == ReferenceType.SCALAR`,
      plus every `schema.getCustomScalarTypes()` name; subtract the spec set
      `Set.of("Int","Float","String","Boolean","ID")`; for each remaining name (sorted) prepend a
      `graphql.language.ScalarTypeDefinition.newScalarTypeDefinition().name(name).build()` to
      `definitions`.
- [ ] Re-run the extension tests; expect PASS. Run the FULL extension `graphql` module build.
- [ ] Bump the extension version (current reactor `revision` `2.1.3`; extension release train is at
      `1.14.0-1`) to the next minor `1.15.0-1`, and publish it per carbonio-knowledge
      `operations/` (Jenkins release job for `carbonio-quarkus-extensions`). Commit on a feature
      branch + PR (extension repo). Record the published version string for P0.2.

### Task P0.2: bump files-ce to the new extension + add SmallRye + configure store dir

**Files:** `app/pom.xml`, `pom.xml` (property), `app/src/main/resources/application.properties`.

- [ ] In reactor `pom.xml`, set `<carbonio-quarkus-extensions.version>` to `1.15.0-1` (the P0.1 output).
- [ ] In `app/pom.xml` add (compile scope): `io.quarkus:quarkus-smallrye-graphql` (BOM-managed) and
      `com.zextras.carbonio.quarkus:carbonio-quarkus-extensions-graphql`
      (`${carbonio-quarkus-extensions.version}`). Do NOT touch `graphql-java`/`java-dataloader` yet (§10).
- [ ] Add (test scope) for the contract test: `carbonio-quarkus-extensions-graphql-deployment`
      (`${carbonio-quarkus-extensions.version}`), `io.smallrye:smallrye-graphql-schema-builder`
      (BOM/managed by the extension chain — pin `2.18.3` if unmanaged), `io.smallrye:jandex`.
- [ ] In `application.properties` add the schema store dir + introspection + HTTP permit-all
      (ONE place):
      ```properties
      # Code-first GraphQL: the build step renders the SmallRye schema to docs/schema.graphql.
      quarkus.carbonio-graphql.store-schema-directory=docs
      # Introspection & schema endpoint: available in dev/test, OFF in prod (single place).
      quarkus.smallrye-graphql.schema-include-introspection-types=true
      %prod.quarkus.smallrye-graphql.schema-include-introspection-types=false
      %prod.quarkus.smallrye-graphql.schema-available=false
      # /graphql is permit-all at HTTP; per-operation @Authenticated/@PermitAll enforce access.
      quarkus.http.auth.permission.graphql.paths=/graphql,/graphql/schema.graphql
      quarkus.http.auth.permission.graphql.policy=permit
      ```
- [ ] VERIFY the introspection semantics against `~/.m2` (contract §13): confirmed
      `io.quarkus.smallrye.graphql.runtime.SmallRyeGraphQLConfig.schemaAvailable()` exists →
      `quarkus.smallrye-graphql.schema-available`; and `SmallRyeGraphQLConfigMapping` references
      `QUARKUS_SCHEMA_INCLUDE_INTROSPECTION_TYPES` → `schema-include-introspection-types`, which drives
      SmallRye's `NoIntrospectionGraphqlFieldVisibility` (present in `smallrye-graphql-2.18.3.jar`).
      Since `schema-include-introspection-types` is BUILD-TIME, prod isolation works because the
      prod uber-jar is augmented under the `prod` profile. Fallback if `%prod` build-time profiling
      does not isolate: register a `graphql.execution.instrumentation` bean applying
      `NoIntrospectionGraphqlFieldVisibility` gated on `%prod`.
- [ ] `./mvnw -q -pl app -am compile` — expect BUILD SUCCESS (SmallRye on classpath, nothing wired yet).

### Task P0.3: author + commit the frozen `docs/schema.graphql`

**Files:** `docs/schema.graphql` (new).

Hand-translate `app/src/main/resources/api/schema.graphql` + `api/public-schema.graphql` into ONE
target schema: (a) every `DateTime` → `BigInteger`; (b) merge both into a single `type Query`/`type
Mutation` and ONE `enum NodeType` INCLUDING `ROOT`; (c) rename the public interface/types to
`PublicNode`/`PublicFolder`/`PublicFile`/`PublicNodePage`; (d) rename the public `getPublicNode` query
to keep its name and the public `findNodes` query to `findPublicNodes`; the internal `findNodes` keeps
its name. Author WITHOUT descriptions/comments (the code-first classes carry no `@Description`; the
gate strips descriptions anyway). Field/type ORDER is irrelevant (gate is order-insensitive), but keep
`scalar BigInteger` declared.

- [ ] Write `docs/schema.graphql` containing exactly, in the unified `Query`, the 13 internal queries
      (`getNode`, `findNodes`, `getVersions`, `getPath`, `getUserById`, `getAccountByEmail`,
      `getAccountsByEmail`, `getShare`, `getLinks`, `getCollaborationLinks`, `getRootsList`,
      `getConfigs`, `getNotifications`) + the 2 public queries (`getPublicNode`, `findPublicNodes`);
      the 19 mutations (`createFolder`, `updateNode`, `flagNodes`, `trashNodes`, `restoreNodes`,
      `moveNodes`, `deleteNodes`, `copyNodes`, `deleteVersions`, `keepVersions`, `cloneVersion`,
      `createShare`, `updateShares`, `deleteShares`, `createLink`, `updateLink`, `deleteLinks`,
      `createCollaborationLink`, `deleteCollaborationLinks`); the 7 enums; the 3 unions
      (`SharedTarget`, `Account`, `Notification`); the object/interface types (`User`, `Config`,
      `DistributionList`, `Permissions`, `Node`, `File`, `Folder`, `Root`, `Share`, `Link`,
      `CollaborationLink`, `SnapshotNode`, `SnapshotUser`, `NewShare`, `AddedNode`, `RemovedNode`,
      `NodePage`, `NotificationPage`, `PublicNode`, `PublicFile`, `PublicFolder`, `PublicNodePage`);
      `scalar BigInteger`. All timestamp fields typed `BigInteger`. `File.size`/`PublicFile.size` stay
      `Float` (legacy wire — see below). Public fields exactly: `id`, `created_at: BigInteger!`,
      `updated_at: BigInteger!`, `name`, `type` (+ File: `extension`, `mime_type`, `size: Float`);
      `PublicNodePage { nodes: [PublicNode]!, page_token: String }`.
- [ ] NOTE on `size`: internal `File.size` and public `size` are `Float!`/`Float` in today's SDL, but
      the DAO returns `Long`. SmallRye maps `Long` → `BigInteger`. To keep the wire `Float`, expose
      `size` as a Java `double`/`Double` in the model (cast from `Long`) so SmallRye emits `Float`.
      Record this in the model tasks (P2.3 File, P5.1 PublicFile). The frozen baseline keeps `Float`.
- [ ] Commit `docs/schema.graphql` on the feature branch (`feat/graphql-code-first`) — this is the
      one-time seed of the frozen target (allowed; every later task converges the CODE to it).

### Task P0.4: SchemaContractTest (golden gate, initially RED)

**Files:** `app/src/test/java/com/zextras/carbonio/files/graphql/SchemaContractTest.java` (new; test:
itself).

The test builds a Jandex index of the compiled `graphql.model` + `graphql.api` packages, runs
`io.smallrye.graphql.schema.SchemaBuilder.build(index)`, renders via the public
`GraphqlSchemaRenderer.render(schema)` (P0.1), and asserts semantic equality with `docs/schema.graphql`.
Dependency-light (JUnit + Jandex + SmallRye schema-builder + the extension deployment jar — all
test-scope), so it survives the Phase-6 removal of `graphql-java` from the compile classpath.

- [ ] Write the test with this exact structure (normalizer is self-contained — no graphql-java):
      ```java
      package com.zextras.carbonio.files.graphql;

      import static org.assertj.core.api.Assertions.assertThat;

      import com.zextras.carbonio.quarkus.extensions.deployment.GraphqlSchemaRenderer;
      import io.smallrye.graphql.schema.SchemaBuilder;
      import io.smallrye.graphql.schema.model.Schema;
      import java.io.InputStream;
      import java.nio.file.Files;
      import java.nio.file.Path;
      import java.util.Arrays;
      import java.util.List;
      import java.util.Set;
      import java.util.TreeSet;
      import java.util.stream.Collectors;
      import org.jboss.jandex.Index;
      import org.jboss.jandex.Indexer;
      import org.junit.jupiter.api.Test;

      class SchemaContractTest {

        private static final Path FROZEN = Path.of("..", "docs", "schema.graphql");
        private static final List<Path> CLASS_ROOTS =
            List.of(
                Path.of("target", "classes", "com", "zextras", "carbonio", "files", "graphql", "model"),
                Path.of("target", "classes", "com", "zextras", "carbonio", "files", "graphql", "api"));

        @Test
        void generatedSchemaMatchesFrozenTarget() throws Exception {
          Indexer indexer = new Indexer();
          for (Path root : CLASS_ROOTS) {
            if (!Files.isDirectory(root)) continue;
            try (var walk = Files.walk(root)) {
              for (Path p : walk.filter(f -> f.toString().endsWith(".class")).collect(Collectors.toList())) {
                try (InputStream in = Files.newInputStream(p)) {
                  indexer.index(in);
                }
              }
            }
          }
          Index index = indexer.complete();
          Schema schema = SchemaBuilder.build(index);
          String generated = GraphqlSchemaRenderer.render(schema);
          String frozen = Files.readString(FROZEN);
          assertThat(normalize(generated)).isEqualTo(normalize(frozen));
        }

        /** Order-insensitive, description/comment-insensitive canonical form. */
        private static Set<String> normalizeToSet(String sdl) {
          // strip block/line descriptions and comments, split into top-level definition blocks,
          // sort member lines within each block, return the set of canonical blocks.
          // (implementation walks brace depth; each `type/interface/enum/union/scalar/input X { ... }`
          // becomes "X\n<sorted-trimmed-member-lines>"; scalars/unions become their single line.)
          return SchemaCanonicalizer.blocks(sdl);
        }

        private static String normalize(String sdl) {
          return String.join("\n---\n", new TreeSet<>(normalizeToSet(sdl)));
        }
      }
      ```
- [ ] Add a small self-contained `SchemaCanonicalizer` helper (same test package) that: removes `#`
      comment lines and `"""..."""` / `"..."` description tokens, collapses whitespace, groups by
      top-level definition via brace depth, sorts member lines (fields/enum-values/union-members
      alphabetically; args are inline on the field line so they canonicalize with it), and returns a
      `Set<String>` of `"<header>\n<sorted members>"`. No external deps.
- [ ] VERIFY (§13) the in-test index covers every schema-referenced type. Fallback if `SchemaBuilder`
      complains about an unindexed referenced class: widen `CLASS_ROOTS` to the whole
      `target/classes` tree. Confirm `SchemaBuilder.build(IndexView)` signature via
      `javap -p io.smallrye.graphql.schema.SchemaBuilder` on the 2.18.3 schema-builder jar.
- [ ] Run `./mvnw -q -pl app test -Dtest=SchemaContractTest`; expect FAIL (no `@GraphQLApi`/`@Type`
      classes exist yet → generated schema empty ≠ frozen). This RED is the gate that Phases 2–5 drive
      to GREEN. Commit test + helper.

---

## Phase 1 — Foundation: auth mechanism + identity

### Task P1.1: custom OPTIONAL HttpAuthenticationMechanism

**Files:** `app/src/main/java/com/zextras/carbonio/files/graphql/auth/FilesGraphQLAuthMechanism.java`
(new); test: `app/src/test/java/com/zextras/carbonio/files/graphql/auth/FilesGraphQLAuthMechanismTest.java`.

Ports the checks from `FilesAuthenticationFilter.filter` (cookie → user; ACTIVE; not GUEST;
`carbonioFeatureFilesEnabled`) into a Quarkus HTTP mechanism.

- [ ] VERIFY (§13) the `HttpAuthenticationMechanism` SPI in Quarkus 3.37: methods `Uni<SecurityIdentity>
      authenticate(RoutingContext, IdentityProviderManager)`, `Uni<ChallengeData> getChallenge(...)`,
      `Set<Class<? extends AuthenticationRequest>> getCredentialTypes()`, optional
      `getCredentialTransport`. Run `javap -p io.quarkus.vertx.http.runtime.security.HttpAuthenticationMechanism`
      on the resolved `quarkus-vertx-http` jar. Fallback for the 401-vs-403 distinction (below): if
      throwing `io.quarkus.security.ForbiddenException` from `authenticate` does not surface as 403,
      build the identity with a synthetic role and enforce entitlement in the `@RequestScoped` producer,
      throwing `ForbiddenException` there.
- [ ] Write the mechanism:
      ```java
      package com.zextras.carbonio.files.graphql.auth;

      import com.zextras.carbonio.files.Constants.API.Headers;
      import com.zextras.carbonio.files.dal.dao.UserMyself;
      import com.zextras.carbonio.files.dal.dao.UserStatus;
      import com.zextras.carbonio.files.dal.dao.UserType;
      import com.zextras.carbonio.files.dal.repositories.interfaces.UserRepository;
      import io.quarkus.security.AuthenticationFailedException;
      import io.quarkus.security.ForbiddenException;
      import io.quarkus.security.identity.IdentityProviderManager;
      import io.quarkus.security.identity.SecurityIdentity;
      import io.quarkus.security.runtime.QuarkusSecurityIdentity;
      import io.quarkus.vertx.http.runtime.security.ChallengeData;
      import io.quarkus.vertx.http.runtime.security.HttpAuthenticationMechanism;
      import io.smallrye.mutiny.Uni;
      import io.vertx.core.http.Cookie;
      import io.vertx.ext.web.RoutingContext;
      import jakarta.enterprise.context.ApplicationScoped;
      import jakarta.inject.Inject;
      import java.security.Principal;
      import java.util.Optional;
      import java.util.Set;

      @ApplicationScoped
      public class FilesGraphQLAuthMechanism implements HttpAuthenticationMechanism {

        public static final String IDENTITY_ATTRIBUTE = "carbonio.files.userMyself";

        private final UserRepository userRepository;

        @Inject
        public FilesGraphQLAuthMechanism(UserRepository userRepository) {
          this.userRepository = userRepository;
        }

        @Override
        public Uni<SecurityIdentity> authenticate(RoutingContext ctx, IdentityProviderManager idm) {
          Cookie zmCookie = ctx.request().getCookie(Headers.COOKIE_ZM_AUTH_TOKEN);
          if (zmCookie == null) {
            return Uni.createFrom().nullItem(); // anonymous; @Authenticated ops will 401
          }
          String header = ctx.request().getHeader("Cookie");
          String cookies =
              (header != null && !header.isBlank())
                  ? header
                  : Headers.COOKIE_ZM_AUTH_TOKEN + "=" + zmCookie.getValue();

          Optional<UserMyself> optUser = userRepository.getUserMyselfByCookie(cookies);
          if (optUser.isEmpty()) {
            return Uni.createFrom().failure(new AuthenticationFailedException("Invalid token"));
          }
          UserMyself user = optUser.get();
          if (!UserStatus.ACTIVE.equals(user.getStatus())
              || UserType.GUEST.equals(user.getType())
              || !"TRUE".equals(user.getCarbonioAttributes()
                  .getOrDefault("carbonioFeatureFilesEnabled", "FALSE"))) {
            return Uni.createFrom().failure(new ForbiddenException()); // 403: authenticated, not entitled
          }
          QuarkusSecurityIdentity identity =
              QuarkusSecurityIdentity.builder()
                  .setPrincipal((Principal) user::getEmail)
                  .addRole("files-user")
                  .addAttribute(IDENTITY_ATTRIBUTE, user)
                  .addAttribute("cookies", cookies)
                  .build();
          return Uni.createFrom().item(identity);
        }

        @Override
        public Uni<ChallengeData> getChallenge(RoutingContext ctx) {
          return Uni.createFrom().item(new ChallengeData(401, null, "Unauthorized"));
        }

        @Override
        public Set<Class<? extends io.quarkus.security.identity.request.AuthenticationRequest>>
            getCredentialTypes() {
          return Set.of();
        }
      }
      ```
      (Legacy `FilesAuthenticationFilter` used `carbonioFeatureFilesEnabled == "FALSE"` → reject; the
      map maps enabled features to `"TRUE"`. The `!"TRUE".equals(...)` above is the exact ported check.)
- [ ] Confirm `Headers.COOKIE_ZM_AUTH_TOKEN == "ZM_AUTH_TOKEN"` (Constants), `UserStatus.ACTIVE`,
      `UserType.GUEST` exist (they do — used by `FilesAuthenticationFilter`).
- [ ] `./mvnw -q -pl app compile` — expect SUCCESS. (No behaviour test until an op exists; a
      `@QuarkusTest` covering 401/403 lands in P1.3.)

### Task P1.2: @RequestScoped authenticated-UserMyself producer

**Files:** `app/src/main/java/com/zextras/carbonio/files/graphql/auth/AuthenticatedUserProducer.java`
(new).

- [ ] Write the producer exposing the authenticated `UserMyself` (and the raw cookie string, which
      `UserApi` needs for `getAccountByEmail`) from the current `SecurityIdentity`:
      ```java
      package com.zextras.carbonio.files.graphql.auth;

      import com.zextras.carbonio.files.dal.dao.UserMyself;
      import io.quarkus.security.identity.SecurityIdentity;
      import jakarta.enterprise.context.RequestScoped;
      import jakarta.enterprise.inject.Produces;
      import jakarta.inject.Inject;

      @RequestScoped
      public class AuthenticatedUserProducer {

        @Inject SecurityIdentity identity;

        @Produces
        @RequestScoped
        @AuthenticatedUser
        public UserMyself authenticatedUser() {
          UserMyself user = identity.getAttribute(FilesGraphQLAuthMechanism.IDENTITY_ATTRIBUTE);
          if (user == null) {
            throw new io.quarkus.security.UnauthorizedException();
          }
          return user;
        }

        public String cookies() {
          return identity.getAttribute("cookies");
        }
      }
      ```
- [ ] Add a `@Qualifier @interface AuthenticatedUser` in the same package so APIs inject
      `@AuthenticatedUser UserMyself requester;` unambiguously.
- [ ] `./mvnw -q -pl app compile` — expect SUCCESS.

### Task P1.3: security smoke test (401/403/permit)

**Files:** test `app/src/test/java/com/zextras/carbonio/files/graphql/auth/GraphQLAuthIT.java` (or a
`@QuarkusTest`), plus a throwaway `@GraphQLApi PingApi { @Query @Authenticated String ping() }` that is
REMOVED at the end of this task (it exists only to exercise auth; keep the tree collision-free).

- [ ] Add a temporary `@GraphQLApi` `PingApi` with one `@Authenticated` `@Query String ping()` and one
      `@PermitAll @Query String pingPublic()`.
- [ ] TDD: `@QuarkusTest` asserting: POST `/graphql` `{ping}` with no cookie → GraphQL error /
      Unauthorized; with a WireMock-stubbed valid user-management cookie → `ping` returns; `{pingPublic}`
      with no cookie → returns. Wire the WireMock user-management stub as the existing acceptance tests
      do (`UserRepository` REST SDK). Run; iterate to green.
- [ ] Delete `PingApi` and keep the auth test only if it can target a real op later; otherwise delete
      the test too (it is scaffolding). `./mvnw -q -pl app compile` — SUCCESS, tree clean.

---

## Phase 2 — Type layer (models, enums, unions)

All classes in `com.zextras.carbonio.files.graphql.model`. After each task, run
`./mvnw -q -pl app test -Dtest=SchemaContractTest` and confirm the diff SHRINKS (the failing set of
missing/mismatched blocks gets smaller). The gate goes fully green only after Phase 5.

### Task P2.1: the 7 enums

**Files:** `model/SharePermission.java`, `model/NodeSort.java`, `model/ShareSort.java`,
`model/NodeType.java`, `model/NotificationType.java`, `model/AddedNodeType.java`,
`model/RemovedNodeType.java` (all new). Pattern (one example — `NodeType`):

```java
package com.zextras.carbonio.files.graphql.model;

import org.eclipse.microprofile.graphql.Enum;
import org.eclipse.microprofile.graphql.Name;

@Enum("NodeType")
public enum NodeType {
  IMAGE, VIDEO, AUDIO, TEXT, SPREADSHEET, PRESENTATION, FOLDER, APPLICATION, MESSAGE, ROOT, OTHER
}
```

- [ ] Create all 7 with `@Enum("<SdlName>")` and the pinned values (§ Pinned enums). No `@Name` on
      values (values are already the SDL spelling). These are GraphQL-facing enums distinct from the
      DAO enums (`dal.dao.ebean.NodeType`, `ACL.SharePermission`); the API layer maps between them.
- [ ] Run the gate; confirm the 7 enum blocks now match. Commit.

### Task P2.2: leaf object types (User, Config, DistributionList, Permissions, Root, SnapshotNode, SnapshotUser)

**Files:** `model/UserModel.java`, `model/ConfigModel.java`, `model/DistributionListModel.java`,
`model/PermissionsModel.java`, `model/RootModel.java`, `model/SnapshotNodeModel.java`,
`model/SnapshotUserModel.java` (all new). Pattern (one example — `UserModel`; and `PermissionsModel`
mapping the existing `Permissions.build(ACL)`):

```java
package com.zextras.carbonio.files.graphql.model;

import org.eclipse.microprofile.graphql.Id;
import org.eclipse.microprofile.graphql.Name;
import org.eclipse.microprofile.graphql.Type;

@Type("User")
public class UserModel {
  private final String id;
  private final String email;
  private final String fullName;

  public UserModel(String id, String email, String fullName) {
    this.id = id; this.email = email; this.fullName = fullName;
  }
  @Id public String getId() { return id; }
  public String getEmail() { return email; }
  @Name("full_name") public String getFullName() { return fullName; }
}
```

```java
@Type("Permissions")
public class PermissionsModel {
  private final com.zextras.carbonio.files.dal.dao.ebean.ACL acl;
  public PermissionsModel(com.zextras.carbonio.files.dal.dao.ebean.ACL acl) { this.acl = acl; }
  @Name("can_read")         public boolean isCanRead()        { return acl.canRead(); }
  @Name("can_write_file")   public boolean isCanWriteFile()   { return acl.canWrite(); }
  @Name("can_write_folder") public boolean isCanWriteFolder() { return acl.canWrite(); }
  @Name("can_delete")       public boolean isCanDelete()      { return acl.canDelete(); }
  @Name("can_add_version")  public boolean isCanAddVersion()  { return acl.canWrite(); }
  @Name("can_read_link")    public boolean isCanReadLink()    { return acl.canRead(); }
  @Name("can_change_link")  public boolean isCanChangeLink()  { return acl.canWrite(); }
  @Name("can_share")        public boolean isCanShare()       { return acl.canShare(); }
  @Name("can_read_share")   public boolean isCanReadShare()   { return acl.canRead(); }
  @Name("can_change_share") public boolean isCanChangeShare() { return acl.canWrite(); }
}
```

- [ ] Author each (exact `@Name` per SDL): `ConfigModel` { `name: String!`, `value: String` };
      `DistributionListModel` { `@Id id`, `name` } — `users(limit: Int!, cursor: String): [User]!` is a
      `@Source` added in P4.4; `RootModel` { `@Id id`, `name` }; `SnapshotNodeModel`
      { `@Name("snapshot_node_id") @Id`, `@Name("node_id")`, `@Name("owner_id")`, `name`, `type:
      NodeType`, `@Name("created_at") long` }; `SnapshotUserModel` { `@Name("snapshot_user_id") @Id`,
      `@Name("user_id")`, `@Name("full_name")`, `email` }.
- [ ] Run the gate; confirm these 7 blocks match. Commit.

### Task P2.3: NodeModel interface + FileModel + FolderModel (id-carriers)

**Files:** `model/NodeModel.java` (interface), `model/FileModel.java`, `model/FolderModel.java`,
`model/support/NodeModelFactory.java` (all new).

`NodeModel` declares ONLY the scalar members as getters (`id`, `created_at`, `updated_at`, `name`,
`description`, `type`, `flagged`, `rootId`). The relational members (`creator`, `owner`, `last_editor`,
`permissions`, `parent`, `share`, `shares`, `links`, `collaboration_links`) are NOT on the interface as
POJO getters — they are contributed by `@Source` methods in Phase 4 (SmallRye adds interface fields
from `@Source` methods whose `@Source` param type is the interface). The id-carrier fields
(`parentId`, `ownerId`, `creatorId`, `lastEditorId`) are `@Ignore`d.

```java
package com.zextras.carbonio.files.graphql.model;

import org.eclipse.microprofile.graphql.Id;
import org.eclipse.microprofile.graphql.Ignore;
import org.eclipse.microprofile.graphql.Interface;
import org.eclipse.microprofile.graphql.Name;

@Interface("Node")
public interface NodeModel {
  @Id String getId();
  @Name("created_at") long getCreatedAt();
  @Name("updated_at") long getUpdatedAt();
  String getName();
  String getDescription();
  NodeType getType();
  boolean isFlagged();
  @Name("rootId") String getRootId();

  @Ignore String getParentId();
  @Ignore String getOwnerId();
  @Ignore String getCreatorId();
  @Ignore String getLastEditorId();
}
```

```java
@Type("File")
public class FileModel implements NodeModel {
  // scalar Node members + id-carriers as fields, plus File extras:
  //   extension:String, mime_type:String!, size:Float!, digest:String!, version:Int!,
  //   keep_forever:Boolean!, cloned_from_version:Int
  // size exposed as double so SmallRye emits Float (see P0.3 NOTE).
  // ... constructor + getters with exact @Name (mime_type, keep_forever, cloned_from_version) ...
}
```

- [ ] Write `NodeModel` exactly as above.
- [ ] Write `FolderModel implements NodeModel` (scalars + id-carriers; `children` is a Phase-4 `@Source`,
      NOT a field here).
- [ ] Write `FileModel implements NodeModel` adding: `@Name("extension") String getExtension()`,
      `@Name("mime_type") String getMimeType()`, `@Name("size") double getSize()`,
      `@Name("digest") String getDigest()`, `@Name("version") int getVersion()`,
      `@Name("keep_forever") boolean isKeepForever()`,
      `@Name("cloned_from_version") Integer getClonedFromVersion()`.
- [ ] Write `NodeModelFactory` (in `model/support`, NOT a GraphQL type — no annotations): static
      `NodeModel from(Node node, Integer version, String requesterId)` reproducing
      `convertNodeToDataFetcherResult`: ROOT/FOLDER → `FolderModel`; else pick the `FileVersion`
      (`node.getCurrentVersion()` or the requested `version`) and build `FileModel`. Maps `flagged` from
      `node.getCustomAttributes()` filtered by `requesterId`; `rootId` from
      `node.getAncestorsList().get(0)` (or `getId()` when ROOT); id-carriers from
      `getParentId()/getOwnerId()/getCreatorId()` and (File) the version's `getLastEditorId()`.
- [ ] Run the gate; confirm `Node`/`File`/`Folder` blocks' SCALAR members match (relational members
      still missing until Phase 4 — expected). Commit.

### Task P2.4: ShareModel, LinkModel, CollaborationLinkModel

**Files:** `model/ShareModel.java`, `model/LinkModel.java`, `model/CollaborationLinkModel.java` (new).

- [ ] `ShareModel` `@Type("Share")`: scalar `@Name("created_at") long getCreatedAt()`,
      `@Name("permission") SharePermission getPermission()`, `@Name("expires_at") Long getExpiresAt()`;
      id-carriers `@Ignore nodeId`, `@Ignore shareTargetId` (for the `node` and `share_target` `@Source`
      resolvers). `node: Node!` and `share_target: SharedTarget` are Phase-4 `@Source`.
- [ ] `LinkModel` `@Type("Link")`: `@Id id`, `url: String`, `@Name("created_at") long`,
      `@Name("expires_at") Long`, `description: String`, `@Name("access_code") String`;
      `@Ignore nodeId`; `node: Node!` is a Phase-4 `@Source`.
- [ ] `CollaborationLinkModel` `@Type("CollaborationLink")`: `@Id id`, `url: String!`,
      `@Name("created_at") long`, `permission: SharePermission!`; `@Ignore nodeId`; `node: Node!` is a
      Phase-4 `@Source`.
- [ ] Run the gate; confirm the 3 blocks' scalar members match. Commit.

### Task P2.5: unions + notification member types + pages

**Files:** `model/SharedTarget.java`, `model/Account.java`, `model/Notification.java` (unions),
`model/NewShareModel.java`, `model/AddedNodeModel.java`, `model/RemovedNodeModel.java`,
`model/NodePageModel.java`, `model/NotificationPageModel.java` (new). Union pattern (one example):

```java
package com.zextras.carbonio.files.graphql.model;

import io.smallrye.graphql.api.Union;

@Union("Notification")
public interface Notification {}
```

- [ ] `SharedTarget` (`@Union("SharedTarget")`), `Account` (`@Union("Account")`),
      `Notification` (`@Union("Notification")`) — empty marker interfaces.
- [ ] Make `UserModel` and `DistributionListModel` (P2.2) `implements SharedTarget, Account`.
- [ ] `NewShareModel @Type("NewShare") implements Notification`: `@Id id`, `@Name("created_at") long`,
      `@Name("notification_type") NotificationType`, `@Name("node") SnapshotNodeModel`,
      `@Name("triggering_user") SnapshotUserModel`.
- [ ] `AddedNodeModel @Type("AddedNode") implements Notification`: `@Id id`, `@Name("created_at") long`,
      `@Name("notification_type") NotificationType`, `@Name("added_node") SnapshotNodeModel`,
      `@Name("destination_folder") SnapshotNodeModel`, `@Name("triggering_user") SnapshotUserModel`,
      `@Name("added_node_type") AddedNodeType`.
- [ ] `RemovedNodeModel @Type("RemovedNode") implements Notification`: `@Id id`,
      `@Name("created_at") long`, `@Name("notification_type") NotificationType`,
      `@Name("removed_node") SnapshotNodeModel`, `@Name("origin_folder") SnapshotNodeModel`,
      `@Name("triggering_user") SnapshotUserModel`, `@Name("removed_node_type") RemovedNodeType`.
- [ ] `NodePageModel @Type("NodePage")`: `@Name("nodes") List<NodeModel> getNodes()` (POJO field —
      populated inline by the query resolver from already-loaded DAOs; NO localContext),
      `@Name("page_token") String getPageToken()`.
- [ ] `NotificationPageModel @Type("NotificationPage")`: `@Name("notifications") List<Notification>`,
      `@Name("unread") int`, `@Name("last_seen") long`, `@Name("page_token") String`.
- [ ] Run the gate; confirm unions + these blocks match. Commit.

---

## Phase 3 — Internal operations (`@GraphQLApi` beans)

Each op delegates to the SAME service/repository method the legacy datafetcher used (cited per-op).
`requester` = `@AuthenticatedUser UserMyself`; `requesterId` = `requester.getId().getUserId()`. All ops
`@Authenticated`. Result construction reuses `NodeModelFactory` (P2.3). Where a legacy fetcher returned
a `DataFetcherResult` error, throw the matching `FilesGraphQLException` factory (Phase 4 — until Phase 4
lands, throw a plain `GraphQLException` with the message and add the code in P4.1 by swapping the
throw-site helper; sequence Phase 4 error task BEFORE relying on codes in assertions).

**One full `@GraphQLApi` pattern (ShareApi — has `@Query` + `@Mutation`; other beans mirror the shape):**

```java
package com.zextras.carbonio.files.graphql.api;

import com.zextras.carbonio.files.dal.dao.UserMyself;
import com.zextras.carbonio.files.dal.dao.ebean.Share;
import com.zextras.carbonio.files.dal.repositories.interfaces.NodeRepository;
import com.zextras.carbonio.files.dal.repositories.interfaces.ShareRepository;
import com.zextras.carbonio.files.graphql.auth.AuthenticatedUser;
import com.zextras.carbonio.files.graphql.errors.FilesGraphQLException;
import com.zextras.carbonio.files.graphql.errors.ErrorCodes;
import com.zextras.carbonio.files.graphql.model.ShareModel;
import com.zextras.carbonio.files.graphql.model.SharePermission;
import com.zextras.carbonio.files.graphql.validation.GraphQLInputValidator;
import com.zextras.carbonio.files.utilities.PermissionsChecker;
import io.quarkus.security.Authenticated;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Optional;
import org.eclipse.microprofile.graphql.GraphQLApi;
import org.eclipse.microprofile.graphql.Mutation;
import org.eclipse.microprofile.graphql.Name;
import org.eclipse.microprofile.graphql.NonNull;
import org.eclipse.microprofile.graphql.Query;

@GraphQLApi
@Authenticated
public class ShareApi {

  @Inject NodeRepository nodeRepository;
  @Inject ShareRepository shareRepository;
  @Inject PermissionsChecker permissionsChecker;
  @Inject GraphQLInputValidator validator;
  @Inject @AuthenticatedUser UserMyself requester;

  @Query("getShare")
  public ShareModel getShare(@Name("node_id") @NonNull String nodeId,
                             @Name("share_target_id") @NonNull String shareTargetId) {
    validator.checkNodeId(nodeId).checkUserId(shareTargetId).validate();
    String me = requester.getId().getUserId();
    if (!permissionsChecker.getPermissions(nodeId, me).has(
            com.zextras.carbonio.files.dal.dao.ebean.ACL.SharePermission.READ_ONLY)) {
      throw FilesGraphQLException.of(ErrorCodes.SHARE_NOT_FOUND, "node", nodeId, "user", shareTargetId);
    }
    return shareRepository.getShare(nodeId, shareTargetId)
        .map(ShareApi::toModel)
        .orElseThrow(() ->
            FilesGraphQLException.of(ErrorCodes.SHARE_NOT_FOUND, "node", nodeId, "user", shareTargetId));
  }

  @Mutation("createShare")
  @NonNull
  public ShareModel createShare(@Name("node_id") @NonNull String nodeId,
                                @Name("share_target_id") @NonNull String shareTargetId,
                                @Name("permission") @NonNull SharePermission permission,
                                @Name("expires_at") Long expiresAt,
                                @Name("custom_message") String customMessage) {
    // delegates exactly as ShareDataFetcher.createShareFetcher: PermissionsChecker.getPermissions(..)
    // .has(READ_AND_SHARE); NodeRepository.getNode(nodeId); ShareRepository.upsertShare(nodeId,
    // shareTargetId, ACL, isDirect=true, isInherited=false, Optional<Long> expiresAt) in
    // QuarkusTransaction.requiringNew(); cascadeUpsertShare for folders; conditional
    // NotificationRepository.createNewShareNotification(node, requester, usersToNotify).
    // ... real body ...
  }

  // updateShares / deleteShares: see the ShareApi checklist below.

  private static ShareModel toModel(Share s) {
    return new ShareModel(s.getNodeId(), s.getTargetUserId(), s.getCreatedAt(),
        map(s.getPermissions()), s.getExpiredAt().orElse(null));
  }
  // map(): ACL.SharePermission <-> model SharePermission (both 4-value; identical names).
}
```

### Task P3.1: NodeApi — read queries

**Files:** `app/src/main/java/com/zextras/carbonio/files/graphql/api/NodeApi.java` (new — authored ONCE;
this task creates the class with its queries, P3.2 ADDS mutations to the SAME file, P4.2/P4.4 ADD its
`@Source` methods). Test: `app/src/test/.../api/NodeApiIT.java`.

Injected (from `NodeDataFetcher`): `NodeRepository`, `FileVersionRepository`, `PermissionsChecker`,
`ShareRepository`, `FilesConfig`, `Filestore`, `TombstoneRepository`, `NotificationRepository`,
`CopyFailureClassifier`, `GraphQLInputValidator`, `@AuthenticatedUser UserMyself`.

Per-op checklist (arg `@Name` = SDL; Java type; delegate read from `NodeDataFetcher`):

- [ ] `@Query("getNode") NodeModel getNode(@Name("node_id") @NonNull String nodeId, @Name("version")
      Integer version)` — validate `checkNodeId`; `permissionsChecker.getPermissions(nodeId, me)
      .has(READ_ONLY)` else `NODE_NOT_FOUND`; fetch via `nodeRepository.getNode(nodeId)`
      (single-item here; batch loading applies only to the relational `parent` `@Source`); build via
      `NodeModelFactory.from(node, version, me)`.
- [ ] `@Query("findNodes") NodePageModel findNodes(...)` args: `keywords: [String!]`,
      `@Name("flagged") Boolean`, `@Name("shared_by_me") Boolean`, `@Name("shared_with_me") Boolean`,
      `@Name("direct_share") Boolean`, `@Name("folder_id") String`, `@Name("cascade") Boolean`,
      `type: NodeType`, `@Name("owner_id") String`, `limit: Integer`, `@Name("page_token") String`,
      `sort: NodeSort` → delegate `nodeRepository.findNodes(me, Optional<NodeSort>, Optional<Boolean>
      flagged, Optional<String> folderId, Optional<Boolean> cascade, Optional<Boolean> sharedWithMe,
      Optional<Boolean> sharedByMe, Optional<Boolean> directShare, Optional<Integer> limit,
      Optional<NodeType> nodeType, Optional<String> ownerId, List<String> keywords, Optional<String>
      pageToken): ImmutablePair<List<Node>,String>`; build `NodePageModel(nodes.map(n ->
      NodeModelFactory.from(n, null, me)), pageToken)`.
- [ ] `@Query("getVersions") @NonNull List<FileModel> getVersions(@Name("node_id") @NonNull String,
      versions: [Int!])` — `permissionsChecker...has(READ_ONLY)`; versions absent →
      `fileVersionRepository.getFileVersions(nodeId, List.of(FileVersionSort.VERSION_DESC))`; else
      `getFileVersions(nodeId, versions)`; `nodeRepository.getNode(nodeId)`; one `FileModel` per version.
- [ ] `@Query("getPath") @NonNull List<NodeModel> getPath(@Name("node_id") @NonNull String)` —
      `permissionsChecker...has(READ_ONLY)`; `nodeRepository.getNode`; `nodeRepository.getNodes(pathIds,
      Optional.empty())`; non-owner trimming via `shareRepository.getShares(nodeIds, me)`. Root→leaf order.
- [ ] `@Query("getRootsList") @NonNull List<RootModel> getRootsList()` — `nodeRepository.getRootsList()`;
      map each to `RootModel(id, name)` (no permission check, no version fields — matches legacy).
- [ ] TDD each with `NodeApiIT` (`@QuarkusTest`, WireMock user-management, Testcontainers PG) asserting
      the JSON matches the legacy `/graphql` response for the same query. Gate + commit.

### Task P3.2: NodeApi — mutations (11)

**Files:** SAME `api/NodeApi.java` (add methods; do NOT create a second file). Test: extend `NodeApiIT`.

Per-op checklist (delegate read verbatim from `NodeDataFetcher`; all wrap writes in
`QuarkusTransaction.requiringNew()` exactly where the legacy fetcher did; bulk ops are partial-success):

- [ ] `@Mutation("createFolder") @NonNull NodeModel createFolder(@Name("destination_id") @NonNull String
      destinationId, @Name("name") @NonNull String name)` — validate `checkNodeId(destinationId)
      .checkNodeName(name)`; `permissionsChecker.getPermissions(destinationId, me).has(READ_AND_WRITE)`
      else `NODE_WRITE_ERROR`; `nodeRepository.getNode(destinationId)` (FOLDER/ROOT else `NODE_NOT_FOUND`);
      `RenameNodeUtils.searchAlternativeName(nodeRepository, name, destinationId, ownerId)`;
      `nodeRepository.createNewNode(id, creatorId, ownerId, parentId, name, "", NodeType.FOLDER,
      ancestors, 0)`; inherited-share propagation via `shareRepository.getShares(...)` +
      `upsertShare(...)`; `NotificationRepository.createAddedNodeNotification(created, parent, requester,
      AddedNodeType.CREATE, usersToNotify)` when notifications enabled.
- [ ] `@Mutation("updateNode") @NonNull NodeModel updateNode(@Name("node_id") @NonNull String,
      @Name("name") String, @Name("description") String, @Name("flagged") Boolean)` — validate
      `checkNodeId.checkNodeName.checkNodeDescription`; `getPermissions...has(READ_AND_WRITE)`;
      `nodeRepository.getNode`; rename via `RenameNodeUtils.searchAlternativeName` (→ `NODE_DUPLICATED`);
      `nodeRepository.flagForUser(nodeId, me, flag)` when `flagged` present; `nodeRepository.updateNode`;
      re-`getNode` for the response.
- [ ] `@Mutation("flagNodes") List<String> flagNodes(node_ids: [ID!], @Name("flag") @NonNull Boolean)` —
      per id: `nodeRepository.getNode` (non-ROOT), `getPermissions...has(READ_AND_WRITE)`,
      `nodeRepository.flagForUser(id, me, flag)`; return flagged ids; `NODE_WRITE_ERROR` per skipped id.
- [ ] `@Mutation("trashNodes") List<String> trashNodes(node_ids: [ID!])` — delegate
      `nodeRepository.trashNode(id, oldParentId)` + `updateNode` + `cascadeUpdateAncestors`;
      `NotificationRepository.createRemovedNodeNotification(..., RemovedNodeType.DELETE, ...)`;
      `NODE_WRITE_ERROR` per skipped id.
- [ ] `@Mutation("restoreNodes") List<NodeModel> restoreNodes(node_ids: [ID!])` — delegate
      `nodeRepository.getTrashedNode` / `restoreNode` / `updateNode` + share promotion
      (`shareRepository.updateShare`/`upsertShare`, `cascadeUpsertShare`); returns restored `NodeModel`s
      + `NODE_WRITE_ERROR` entries.
- [ ] `@Mutation("moveNodes") @NonNull List<NodeModel> moveNodes(node_ids: [ID!], @Name("destination_id")
      @NonNull String)` — delegate `nodeRepository.moveNodes(ids, destFolder)` + ancestor/share cascade
      (`getShares`/`deleteShare`/`cascadeDeleteShare`/`upsertShare`/`cascadeUpsertShare`) +
      added/removed notifications (`AddedNodeType.MOVE`/`RemovedNodeType.MOVE`); `NODE_WRITE_ERROR`.
- [ ] `@Mutation("deleteNodes") List<String> deleteNodes(node_ids: [ID!])` — delegate subtree expansion
      via `nodeRepository.getChildrenIds(id, empty, empty, true)`; `tombstoneRepository.createTombstonesBulk`;
      `shareRepository.deleteSharesBulk`; `nodeRepository.deleteNodes`; post-commit
      `fileStore.bulkDelete(IdentifierType.files, ownerId, items)` +
      `tombstoneRepository.deleteTombstonesByNodeAndVersion`; `NODE_NOT_FOUND` per skipped id.
- [ ] `@Mutation("copyNodes") @NonNull List<NodeModel> copyNodes(node_ids: [ID!], @Name("destination_id")
      @NonNull String)` — args reuse the SAME names as `moveNodes` (verified: legacy reads
      `MoveNodes.NODE_IDS`/`DESTINATION_ID`). Delegate `copyFolder`/`copyFile` (blob `fileStore.copy`,
      `fileVersionRepository.createNewFileVersion`, `nodeRepository.createNewNode`) +
      `createIndirectShare` + `AddedNodeType.COPY` notification; `copyFailureClassifier.classify(...)` →
      `NODE_COPY_ERROR`; `NODE_WRITE_ERROR` on permission.
- [ ] `@Mutation("deleteVersions") @NonNull List<Integer> deleteVersions(@Name("node_id") @NonNull String,
      versions: [Int!])` — delegate `fileVersionRepository.getFileVersions` /
      `deleteFileVersions(nodeId, versions)` + tombstone/blob cleanup as legacy; `FILE_VERSION_NOT_FOUND`
      per skipped version.
- [ ] `@Mutation("keepVersions") @NonNull List<Integer> keepVersions(@Name("node_id") @NonNull String,
      versions: [Int!]!, @Name("keep_forever") @NonNull Boolean)` — cap check from
      `filesConfig.getMaxNumberOfVersions()` − `Config.DIFF_MAX_VERSION_AND_MAX_KEEP_VERSION`;
      `fileVersionRepository.getFileVersions` / `updateFileVersion`; `VERSIONS_LIMIT_REACHED` per
      over-cap version.
- [ ] `@Mutation("cloneVersion") @NonNull FileModel cloneVersion(@Name("node_id") @NonNull String,
      @Name("version") @NonNull Integer)` — cap check; `fileVersionRepository.getFileVersion`;
      `fileStore.copy(src, dest, false)`; `fileVersionRepository.createNewFileVersion`;
      `nodeRepository.updateNode` (currentVersion); `updateFileVersion` (clonedFromVersion);
      `copyFailureClassifier.classify` on blob failure; `VERSIONS_LIMIT_REACHED` /
      `FILE_VERSION_NOT_FOUND` / `NODE_WRITE_ERROR`.
- [ ] TDD each against the legacy response; gate + commit.

### Task P3.3: ShareApi (getShare, createShare, updateShares, deleteShares)

**Files:** `api/ShareApi.java` (new — full skeleton shown above). Test: `api/ShareApiIT.java`.

- [ ] `getShare` / `createShare` — as in the skeleton (delegates: `PermissionsChecker.getPermissions`,
      `ShareRepository.getShare` / `upsertShare` / `cascadeUpsertShare`,
      `NotificationRepository.createNewShareNotification`).
- [ ] `@Mutation("updateShares") @NonNull List<ShareModel> updateShares(@Name("node_id") @NonNull String,
      @Name("share_target_ids") @NonNull List<String>, @Name("permission") SharePermission,
      @Name("expires_at") Long)` — `getPermissions...has(READ_AND_SHARE)`; per target
      `ShareRepository.getShare` → `share.setPermissions/setExpiredAt` → `ShareRepository.updateShare` →
      `cascadeUpsertShare`. Partial-success (successful `ShareModel`s as data + per-target errors).
- [ ] `@Mutation("deleteShares") @NonNull List<String> deleteShares(@Name("node_id") @NonNull String,
      @Name("share_target_ids") @NonNull List<String>)` — `getPermissions...has(READ_AND_SHARE) ||
      me==target`; `ShareRepository.getShare`/`deleteShare`; folder → `cascadeDeleteShare`. Returns
      deleted target ids; partial-success errors for the rest.
- [ ] Validation: `checkNodeId` + `checkUserId`/`checkUserIds` (ports of `shareQueriesValidation` /
      `bulkShareQueriesValidation`). TDD; gate + commit.

### Task P3.4: LinkApi (getLinks, createLink, updateLink, deleteLinks)

**Files:** `api/LinkApi.java` (new). Test: `api/LinkApiIT.java`. Injected: `LinkRepository`,
`NodeRepository`, `PermissionsChecker`, validator, requester.

- [ ] `@Query("getLinks") @NonNull List<LinkModel> getLinks(@Name("node_id") @NonNull String)` —
      `getPermissions...has(READ_AND_SHARE)`; `NodeRepository.getNode`;
      `LinkRepository.getLinksByNodeId(nodeId, LinkSort.CREATED_AT_DESC)`.
- [ ] `@Mutation("createLink") @NonNull LinkModel createLink(@Name("node_id") @NonNull String,
      @Name("expires_at") Long, @Name("description") String, @Name("access_code") String)` — reuse the
      existing `LinkDataFetcher.createPublicLink(requesterId, nodeId, Optional<Long>, Optional<String>,
      Optional<String>)` (delegates `LinkRepository.getLinkCountByNode` → `LINK_LIMIT_EXCEEDED`,
      `LinkRepository.createLink`); validate `checkNodeId.checkLinkDescription.checkLinkAccessCode`.
- [ ] `@Mutation("updateLink") LinkModel updateLink(@Name("link_id") @NonNull String,
      @Name("expires_at") Long, @Name("description") String, @Name("access_code") String)` —
      `LinkRepository.getLinkById` (→ `LINK_NOT_FOUND`); `getPermissions...has(READ_AND_SHARE)`;
      `link.setExpiresAt/setDescription/setAccessCode`; `LinkRepository.updateLink`; validate
      `checkLinkId.checkLinkDescription.checkLinkAccessCode`.
- [ ] `@Mutation("deleteLinks") @NonNull List<String> deleteLinks(@Name("link_ids") @NonNull
      List<String>)` — per id `LinkRepository.getLinkById` + `getPermissions...has(READ_AND_SHARE)`;
      `LinkRepository.deleteLinksBulk(ids)`; partial-success; validate `checkLinkIds`.
- [ ] TDD; gate + commit.

### Task P3.5: CollaborationLinkApi

**Files:** `api/CollaborationLinkApi.java` (new). Test: `api/CollaborationLinkApiIT.java`. Injected:
`CollaborationLinkRepository`, `PermissionsChecker`, validator, requester.

- [ ] `@Query("getCollaborationLinks") @NonNull List<CollaborationLinkModel> getCollaborationLinks(
      @Name("node_id") @NonNull String)` — `getPermissions...has(READ_AND_SHARE/READ_WRITE_AND_SHARE)`;
      `CollaborationLinkRepository.getLinksByNodeId(nodeId)` filtered by the permission; validate
      `checkNodeId`.
- [ ] `@Mutation("createCollaborationLink") @NonNull CollaborationLinkModel createCollaborationLink(
      @Name("node_id") @NonNull String, @Name("permission") @NonNull SharePermission)` —
      `getPermissions...has(permission)`; `getLinksByNodeId` (return existing match) else
      `CollaborationLinkRepository.createLink(UUID.randomUUID(), nodeId, invitationId, permission)`;
      `NODE_WRITE_ERROR` on failure; validate `checkNodeId`.
- [ ] `@Mutation("deleteCollaborationLinks") @NonNull List<String> deleteCollaborationLinks(
      @Name("collaboration_link_ids") @NonNull List<String>)` — per id
      `CollaborationLinkRepository.getLinkById(UUID)` + `getPermissions...has(READ_WRITE_AND_SHARE/
      READ_AND_SHARE)`; `CollaborationLinkRepository.deleteLinks(ids)`; partial-success (`MISSING_FIELD`
      per forbidden id); validate `checkLinkIds`.
- [ ] TDD; gate + commit.

### Task P3.6: UserApi (getUserById, getAccountByEmail, getAccountsByEmail)

**Files:** `api/UserApi.java` (new — queries here; the batched `creator`/`owner`/`last_editor` +
`share_target` + `DistributionList.users` `@Source` resolvers are added in P4.2/P4.4, in this SAME
file). Test: `api/UserApiIT.java`. Injected: `UserRepository`, `AuthenticatedUserProducer` (for
`cookies()`), requester.

- [ ] `@Query("getUserById") UserModel getUserById(@Name("user_id") @NonNull String userId)` — delegate
      `UserRepository.getUserById(producer.cookies(), userId)` → `UserModel`; `ACCOUNT_NOT_FOUND` if
      absent. (SDL field name is `getUserById`; the legacy wiring key `getUser` was latent — the
      code-first name is the SDL name.)
- [ ] `@Query("getAccountByEmail") Account getAccountByEmail(@Name("email") @NonNull String email)` —
      validate `checkEmail`; `UserRepository.getUserByEmail(producer.cookies(), email)` → `UserModel`
      (Account union member); `ACCOUNT_NOT_FOUND` if absent.
- [ ] `@Query("getAccountsByEmail") @NonNull List<Account> getAccountsByEmail(@Name("emails") @NonNull
      List<String> emails)` — validate `checkEmails`; per email
      `UserRepository.getUserByEmail(cookies, email)`; one `Account` per email.
- [ ] TDD; gate + commit.

### Task P3.7: ConfigApi (getConfigs)

**Files:** `api/ConfigApi.java` (new). Test: `api/ConfigApiIT.java`. Injected: `FilesConfig`.

- [ ] `@Query("getConfigs") @NonNull List<ConfigModel> getConfigs()` — build the 4 entries exactly as
      `ConfigDataFetcher.getConfigs`: `MAX_VERSIONS` = `filesConfig.getMaxNumberOfVersionsRaw()`,
      `MAX_DOWNLOADABLE_SIZE_IN_MB` = `filesConfig.getMaxDownloadableFileSizeInMb()` (null when empty),
      `MAX_UPLOADABLE_SIZE_IN_MB` = `filesConfig.getMaxUploadableFileSizeInMb()` (null when empty),
      `MAX_KEEP_VERSIONS` = `maxVersions − Config.DIFF_MAX_VERSION_AND_MAX_KEEP_VERSION` (`"0"` if ≤0).
      No auth-sensitive data, but keep `@Authenticated` (legacy endpoint required auth).
- [ ] TDD; gate + commit.

### Task P3.8: NotificationApi (getNotifications)

**Files:** `api/NotificationApi.java` (new). Test: `api/NotificationApiIT.java`. Injected: `FilesConfig`,
`NotificationRepository`.

- [ ] `@Query("getNotifications") NotificationPageModel getNotifications(@Name("update_last_seen")
      @NonNull Boolean updateLastSeen, @Name("limit") Integer limit, @Name("page_token") String
      pageToken)` — delegate (when `filesConfig.areNotificationsEnabled()`):
      `NotificationRepository.getNotifications(me, Optional<Integer> limit, Optional<String> pageToken)`
      (→ `ImmutablePair<List<BaseNotification>,String>`); `getUserNotificationsInfo(me)` for `unread` /
      `last_seen`; when `updateLastSeen` → `upsertUserNotificationsInfo(me, ts, unread)`. Map each
      `BaseNotification` into `NewShareModel`/`AddedNodeModel`/`RemovedNodeModel` (by
      `notification_type`), and its nested snapshots into `SnapshotNodeModel`/`SnapshotUserModel`
      INLINE (POJO fields on `NotificationPageModel` — no localContext/second-level fetch, matching the
      legacy "return nested as-is" wiring). Build `NotificationPageModel(notifications, unread,
      lastSeen, pageToken)`.
- [ ] TDD; gate + commit.

---

## Phase 4 — Batch `@Source` resolvers, single `@Source`, validation, errors

### Task P4.1: error hierarchy + `errorCode` extension

**Files:** `errors/FilesGraphQLException.java`, `errors/FilesErrorCodeExtension.java`,
`src/main/resources/META-INF/services/io.smallrye.graphql.api.ErrorExtensionProvider` (new); reuse the
EXISTING `errors/ErrorCodes.java` enum (14 codes — do NOT create a second). Test:
`errors/FilesGraphQLExceptionTest.java` + assertions in the API ITs.

- [ ] VERIFY (§13) `ErrorExtensionProvider` (confirmed on `smallrye-graphql-api-2.18.3.jar`:
      `String getKey()` + `jakarta.json.JsonValue mapValueFrom(Throwable)`, loaded by
      `io.smallrye.graphql.execution.error.ExceptionHandler`). Confirm the discovery path (ServiceLoader
      via `META-INF/services`). Fallback if not ServiceLoader-discovered in 2.18.3: annotate the (few)
      exception subclasses with `@io.smallrye.graphql.api.ErrorCode("<code>")` (static per-class) —
      requires one subclass per code, which is acceptable but heavier; prefer the provider.
- [ ] Write `FilesGraphQLException`:
      ```java
      package com.zextras.carbonio.files.graphql.errors;

      import java.util.HashMap;
      import java.util.Map;
      import org.eclipse.microprofile.graphql.GraphQLException;

      public class FilesGraphQLException extends GraphQLException {
        private final ErrorCodes errorCode;
        private final Map<String, Object> data;

        public FilesGraphQLException(ErrorCodes errorCode, String message, Map<String, Object> data) {
          super(message);
          this.errorCode = errorCode;
          this.data = data;
        }
        // partial-success: carry the successful list as GraphQL `data`
        public FilesGraphQLException(ErrorCodes errorCode, String message, Object partialResults,
                                     Map<String, Object> data) {
          super(message, partialResults);
          this.errorCode = errorCode;
          this.data = data;
        }
        public ErrorCodes getErrorCode() { return errorCode; }
        public Map<String, Object> getData() { return data; }

        public static FilesGraphQLException of(ErrorCodes code, Object... kv) {
          Map<String, Object> d = new HashMap<>();
          for (int i = 0; i + 1 < kv.length; i += 2) d.put(String.valueOf(kv[i]), kv[i + 1]);
          return new FilesGraphQLException(code, code.name(), d);
        }
      }
      ```
- [ ] Write `FilesErrorCodeExtension` (ONE wire key `errorCode`, matching the legacy
      `GraphQLResultErrors` extensions key):
      ```java
      package com.zextras.carbonio.files.graphql.errors;

      import io.smallrye.graphql.api.ErrorExtensionProvider;
      import jakarta.json.Json;
      import jakarta.json.JsonValue;

      public class FilesErrorCodeExtension implements ErrorExtensionProvider {
        @Override public String getKey() { return "errorCode"; }
        @Override public JsonValue mapValueFrom(Throwable t) {
          if (t instanceof FilesGraphQLException f) {
            return Json.createValue(f.getErrorCode().name());
          }
          return null; // no extension for non-Files errors
        }
      }
      ```
- [ ] Register it: `META-INF/services/io.smallrye.graphql.api.ErrorExtensionProvider` containing the FQN
      `com.zextras.carbonio.files.graphql.errors.FilesErrorCodeExtension`.
- [ ] Confirm the 14 `ErrorCodes` values are all reachable from the migrated ops (map from the legacy
      `GraphQLResultErrors` factories): `ACCOUNT_NOT_FOUND`, `NODE_NOT_FOUND`, `FILE_VERSION_NOT_FOUND`,
      `SHARE_NOT_FOUND`, `SHARE_CREATION_ERROR`, `MISSING_FIELD`, `NODE_WRITE_ERROR`, `NODE_COPY_ERROR`,
      `NODE_DUPLICATED`, `LINK_NOT_FOUND`, `VERSIONS_LIMIT_REACHED`, `ACCESS_CODE_REQUIRED`,
      `WRONG_ACCESS_CODE`, `LINK_LIMIT_EXCEEDED`. Merge the previously duplicated NODE/LINK/ACCESS codes
      (public + internal now share this ONE source).
- [ ] Swap every throw-site added in Phase 3 to the `FilesGraphQLException.of(...)` factory. TDD: assert
      `errors[0].extensions.errorCode` equals the expected code for a not-found node. Gate + commit.

### Task P4.2: batch `@Source` — User (creator/owner/last_editor) with 100-cap

**Files:** add methods to `api/UserApi.java` (the batched user resolvers live here, alongside its
queries). Test: extend `NodeApiIT` (assert a multi-node query issues ONE user-management batch).

`NodeModel.creator/owner/last_editor` are resolved from the id-carriers via ONE batched user loader.

- [ ] VERIFY (§13) SmallRye batch `@Source` semantics on 2.18.3 (confirmed present:
      `io.smallrye.graphql.execution.datafetcher.BatchDataFetcher` + `helper.BatchLoaderHelper`
      `getSourceBatchLoader(...)` + `getDataLoader(BatchLoaderWithContext, DataLoaderOptions)`; schema
      model `Operation.isSourceField()`). Convention: a `@Source` method whose source parameter is
      `List<SourceType>` and returns `List<TargetType>` positionally aligned is batched via a DataLoader.
      Run `javap -p io.smallrye.graphql.execution.datafetcher.helper.BatchLoaderHelper` to confirm
      ordering (index-aligned) and confirm there is NO `maxBatchSize` config key (grep found none).
      **maxBatchSize=100 handling** (§4, §13): since SmallRye 2.18.3 exposes no batch-size config, cap
      INSIDE the resolver by partitioning the id list into ≤100 sublists and concatenating
      `UserRepository.getUsers` results. Fallback if batch `@Source` ordering proves non-aligned: return
      a `Map`-keyed result is not supported by SmallRye `@Source`; instead resolve per-source but wrap
      the shared `UserRepository.getUsers` behind a request-scoped memoizing cache keyed by id-set.
- [ ] Write the three batched resolvers (creator non-null, owner/last_editor nullable) sharing one
      helper:
      ```java
      // in UserApi
      @Name("creator")
      public List<UserModel> creators(@Source List<NodeModel> nodes) {
        return resolveUsers(nodes.stream().map(NodeModel::getCreatorId).toList());
      }
      @Name("owner")
      public List<UserModel> owners(@Source List<NodeModel> nodes) {
        return resolveUsers(nodes.stream().map(NodeModel::getOwnerId).toList());
      }
      @Name("last_editor")
      public List<UserModel> lastEditors(@Source List<NodeModel> nodes) {
        return resolveUsers(nodes.stream().map(NodeModel::getLastEditorId).toList());
      }
      private List<UserModel> resolveUsers(List<String> ids) {
        Map<String, UserInfo> byId = new HashMap<>();
        for (List<String> chunk : partition(ids.stream().filter(Objects::nonNull).distinct().toList(), 100)) {
          for (UserInfo u : userRepository.getUsers(chunk)) byId.put(u.getId().getUserId(), u);
        }
        return ids.stream()
            .map(id -> id == null ? null : byId.get(id))
            .map(u -> u == null ? null : new UserModel(u.getId().getUserId(), u.getEmail(), u.getFullName()))
            .toList(); // positionally aligned with the @Source list
      }
      ```
      (`UserRepository.getUsers(List<String>): List<UserInfo>` is the exact method `UserBatchLoader`
      used; the 100-cap matches the user-management `/internal/users` limit.)
- [ ] TDD; gate (Node interface `creator`/`owner`/`last_editor` blocks now appear + match). Commit.

### Task P4.3: batch `@Source` — Node.parent + Node.shares

**Files:** add batched `@Source` to `api/NodeApi.java` (parent) and `api/ShareApi.java` (shares).

- [ ] `Node.parent` batched via `NodeRepository.getNodes(List<String>, Optional.empty()): Stream<Node>`
      (the exact `NodeBatchLoader` source):
      ```java
      // in NodeApi
      @Name("parent")
      public List<NodeModel> parents(@Source List<NodeModel> nodes) {
        List<String> parentIds = nodes.stream().map(NodeModel::getParentId).toList();
        Map<String, Node> byId = nodeRepository
            .getNodes(parentIds.stream().filter(Objects::nonNull).distinct().toList(), Optional.empty())
            .collect(Collectors.toMap(Node::getId, n -> n));
        String me = requester.getId().getUserId();
        return parentIds.stream()
            .map(id -> id == null ? null : byId.get(id))
            .map(n -> n == null ? null : NodeModelFactory.from(n, null, me))
            .toList();
      }
      ```
- [ ] `Node.shares` batched via `ShareRepository.getShares(List<String> nodeIds): List<Share>` (the exact
      `ShareBatchLoader` source), then apply the per-node `limit`/`cursor`/`sorts` args in memory (as the
      legacy `getSharesFetcher` did):
      ```java
      // in ShareApi
      @Name("shares")
      public List<List<ShareModel>> shares(@Source List<NodeModel> nodes,
          @Name("limit") @NonNull int limit, @Name("cursor") String cursor,
          @Name("sorts") List<ShareSort> sorts) {
        List<String> nodeIds = nodes.stream().map(NodeModel::getId).toList();
        List<Share> all = shareRepository.getShares(nodeIds);
        return nodeIds.stream()
            .map(id -> all.stream().filter(s -> id.equals(s.getNodeId()))
                .skip(cursorIndex(cursor)).limit(limit).map(ShareApi::toModel).toList())
            .toList();
      }
      ```
      VERIFY (§13): confirm SmallRye allows extra `@Name` arguments on a batched `@Source` (args applied
      uniformly across the batch). Fallback: single-item `@Source` `shares(@Source NodeModel node, ...)`
      calling `shareRepository.getShares(List.of(node.getId()))` — correct, N+1 only across nodes in one
      list (acceptable; note the regression).
- [ ] `Node.share(share_target_id)` single-item `@Source` (per-node, has an argument →
      `ShareRepository.getShare(nodeId, targetId)` + `PermissionsChecker...has(READ_ONLY)`):
      `@Name("share") ShareModel share(@Source NodeModel node, @Name("share_target_id") @NonNull String
      targetId)`.
- [ ] TDD (assert one multi-node query → ONE `getShares`/`getNodes` DB call). Gate + commit.

### Task P4.4: single `@Source` — permissions, links, collaboration_links, children, share_target, DL.users

**Files:** add `@Source` methods to `NodeApi` (permissions, children), `LinkApi` (links),
`CollaborationLinkApi` (collaboration_links), `UserApi` (share_target, DistributionList.users).

- [ ] `Node.permissions` (single `@Source`, per-node permission check):
      `@Name("permissions") PermissionsModel permissions(@Source NodeModel node)` →
      `new PermissionsModel(permissionsChecker.getPermissions(node.getId(), me))`.
- [ ] `Folder.children` (single `@Source` on `FolderModel`, args from legacy `getChildNodesFetcherFast`):
      `@Name("children") NodePageModel children(@Source FolderModel folder, @Name("limit") @NonNull int
      limit, @Name("sort") @NonNull NodeSort sort, @Name("page_token") String pageToken)` →
      `nodeRepository.findNodes(me, Optional.of(sort), empty, Optional.of(folder.getId()),
      Optional.of(false), sharedWithMe, empty, empty, Optional.of(limit), empty, empty,
      Collections.emptyList(), Optional.ofNullable(pageToken))` → `NodePageModel`.
- [ ] `Node.links` (single `@Source`): `@Name("links") List<LinkModel> links(@Source NodeModel node)` →
      the legacy `LinkDataFetcher.getLinks` path (`getPermissions...has(READ_AND_SHARE)` +
      `LinkRepository.getLinksByNodeId(node.getId(), LinkSort.CREATED_AT_DESC)`).
- [ ] `Node.collaboration_links` (single `@Source`):
      `@Name("collaboration_links") List<CollaborationLinkModel> collaborationLinks(@Source NodeModel
      node)` → the legacy `CollaborationLinkDataFetcher.getCollaborationLinksByNodeId` path.
- [ ] `Share.node` / `Link.node` / `CollaborationLink.node` single `@Source` from the model's `@Ignore
      nodeId` → `NodeRepository.getNode(nodeId)` → `NodeModelFactory.from(...)` (legacy `sharedNodeFetcher`).
- [ ] `Share.share_target` single `@Source` from `ShareModel.getShareTargetId()` →
      `UserRepository.getUsers(List.of(id))` → `UserModel` (SharedTarget member) (legacy
      `shareTargetUserFetcher`).
- [ ] `DistributionList.users` single `@Source`: `@Name("users") List<UserModel> users(@Source
      DistributionListModel dl, @Name("limit") @NonNull int limit, @Name("cursor") String cursor)` →
      empty list (legacy `getDLUsersFetcher` is a no-op stub; keep behaviour).
- [ ] TDD; gate (Node/File/Folder/Share/Link/CollaborationLink relational members now all present +
      match). Commit.

### Task P4.5: input validation

**Files:** `validation/GraphQLInputValidator.java` (new — ports `GenericControllerEvaluator` primitives
into a CDI `@ApplicationScoped` fluent validator throwing `FilesGraphQLException(MISSING_FIELD, ...)`).
Test: `validation/GraphQLInputValidatorTest.java`.

One full pattern (the ported primitives + fluent builder; exact rules read from
`GenericControllerEvaluator`):

```java
package com.zextras.carbonio.files.graphql.validation;

import com.zextras.carbonio.files.Constants.Db.RootId;
import com.zextras.carbonio.files.graphql.errors.ErrorCodes;
import com.zextras.carbonio.files.graphql.errors.FilesGraphQLException;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.ArrayList;
import java.util.List;
import org.apache.commons.validator.routines.EmailValidator;

@ApplicationScoped
public class GraphQLInputValidator {

  public Chain chain() { return new Chain(); }

  public static final class Chain {
    private final List<String> errors = new ArrayList<>();
    private static final int ID_LEN = 36;

    public Chain checkNodeId(String v) {
      if (v != null && !v.equals(RootId.LOCAL_ROOT) && !v.equals(RootId.TRASH_ROOT) && v.length() != ID_LEN)
        errors.add("Invalid node ID: \"" + v + "\". Length must be " + ID_LEN + " characters");
      return this;
    }
    public Chain checkNodeName(String v) {
      if (v != null && (v.trim().isEmpty() || v.trim().length() > 1024))
        errors.add("Invalid node name. The name cannot be empty, longer than 1024 characters, nor be composed only by blank spaces.");
      return this;
    }
    public Chain checkNodeDescription(String v) {
      if (v != null && v.length() > 1024)
        errors.add("Invalid node description. Length cannot be empty or more than 1024 characters");
      return this;
    }
    public Chain checkUserId(String v) {
      if (v != null && v.isEmpty()) errors.add("Invalid user ID. Length cannot be empty");
      return this;
    }
    public Chain checkLinkDescription(String v) {
      if (v != null && v.trim().length() > 300)
        errors.add("Invalid link description. The description cannot be longer than 300 characters");
      return this;
    }
    public Chain checkLinkAccessCode(String v) {
      if (v != null && !v.isEmpty() && (v.length() >= 255 || v.length() < 10))
        errors.add("Invalid link access code. The access code must be between 10 and 255 characters long");
      return this;
    }
    public Chain checkEmail(String v) {
      if (!EmailValidator.getInstance().isValid(v)) errors.add("Invalid Email");
      return this;
    }
    public Chain checkLinkId(String v) {
      if (v == null || v.trim().length() != ID_LEN)
        errors.add("Invalid link ID: \"" + v + "\". Length must be " + ID_LEN + " characters");
      return this;
    }
    public Chain checkNodeIds(List<String> vs) { if (vs != null) vs.forEach(this::checkNodeId); return this; }
    public Chain checkUserIds(List<String> vs) { if (vs != null) vs.forEach(this::checkUserId); return this; }
    public Chain checkLinkIds(List<String> vs) { if (vs != null) vs.forEach(this::checkLinkId); return this; }
    public Chain checkEmails(List<String> vs) { if (vs != null) vs.forEach(this::checkEmail); return this; }

    public void validate() {
      if (!errors.isEmpty())
        throw new FilesGraphQLException(ErrorCodes.MISSING_FIELD, String.join("\n", errors), java.util.Map.of());
    }
  }
}
```

- [ ] Write `GraphQLInputValidator` exactly (rules verbatim from `GenericControllerEvaluator`:
      node-id len 36 or LOCAL/TRASH root; name non-blank ≤1024; description ≤1024; link access-code
      empty or [10,255); link-id len 36; email via commons-validator). `commons-validator:1.9.0` stays on
      the classpath until teardown; if teardown removes it, inline a regex email check (note in P6.2).
- [ ] Apply `validator.chain().checkX(...)...validate()` at the head of each op per the
      `InputFieldsController` map (getNode→checkNodeId; createFolder→checkNodeId(destination_id)+
      checkNodeName; updateNode→checkNodeId+checkNodeName+checkNodeDescription; move/copy→checkNodeIds+
      checkNodeId(destination); trash/restore/delete→checkNodeIds; createShare/getShare→checkNodeId+
      checkUserId; update/deleteShares→checkNodeId+checkUserIds; createLink→checkNodeId+
      checkLinkDescription+checkLinkAccessCode; getLinks→checkNodeId; updateLink→checkLinkId+
      checkLinkDescription+checkLinkAccessCode; deleteLinks/deleteCollaborationLinks→checkLinkIds;
      getPath→checkNodeId; getAccountByEmail→checkEmail; getAccountsByEmail→checkEmails;
      create/getCollaborationLinks→checkNodeId).
- [ ] TDD (invalid inputs → `MISSING_FIELD` error, matching legacy). Gate + commit.

---

## Phase 5 — Public surface

### Task P5.1: Public* types

**Files:** `model/PublicNode.java` (interface), `model/PublicFolder.java`, `model/PublicFile.java`,
`model/PublicNodePage.java` (new). Fields per §7 (public interface: `id`, `created_at`, `updated_at`,
`name`, `type`; File adds `extension`, `mime_type`, `size`). Full pattern (`PublicFile` + interface):

```java
package com.zextras.carbonio.files.graphql.model;

import org.eclipse.microprofile.graphql.Id;
import org.eclipse.microprofile.graphql.Interface;
import org.eclipse.microprofile.graphql.Name;
import org.eclipse.microprofile.graphql.Type;

@Interface("PublicNode")
public interface PublicNode {
  @Id String getId();
  @Name("created_at") long getCreatedAt();
  @Name("updated_at") long getUpdatedAt();
  String getName();
  NodeType getType();
}

@Type("PublicFile")
class PublicFile implements PublicNode {
  private final String id; private final long createdAt; private final long updatedAt;
  private final String name; private final String extension; private final String mimeType;
  private final double size;
  PublicFile(String id, long c, long u, String name, String ext, String mime, double size) {
    this.id=id; this.createdAt=c; this.updatedAt=u; this.name=name; this.extension=ext;
    this.mimeType=mime; this.size=size;
  }
  @Id public String getId() { return id; }
  @Name("created_at") public long getCreatedAt() { return createdAt; }
  @Name("updated_at") public long getUpdatedAt() { return updatedAt; }
  public String getName() { return name; }
  public NodeType getType() { return NodeType.OTHER; } // set from Node.getNodeType() by the factory
  @Name("extension") public String getExtension() { return extension; }
  @Name("mime_type") public String getMimeType() { return mimeType; }
  @Name("size") public double getSize() { return size; }  // double -> Float (P0.3 NOTE)
}
```

- [ ] Write `PublicNode` interface, `PublicFolder` (interface fields only), `PublicFile` (adds
      extension/mime_type/size). Public `type` carries `ROOT` too since it is the ONE shared `NodeType`
      (unified target). Add a `PublicNode from(Node)` factory (mirrors the existing
      `PublicNode.createFromNode` + `convertToMap`: FOLDER/ROOT → `PublicFolder`; else `PublicFile` with
      `extension`/mime/size from the current `FileVersion`; size cast `Long`→`double`).
- [ ] `PublicNodePage @Type("PublicNodePage")`: `@Name("nodes") List<PublicNode> getNodes()` (POJO field,
      populated inline), `@Name("page_token") String getPageToken()`.
- [ ] Gate; confirm the 4 Public* blocks match. Commit.

### Task P5.2: PublicApi (@PermitAll) — getPublicNode, findPublicNodes

**Files:** `api/PublicApi.java` (new). Test: `api/PublicApiIT.java`. Injected: `NodeRepository`,
`LinkRepository`. NO requester (unauthenticated). Reuses the ONE `FilesGraphQLException`/`ErrorCodes`.

- [ ] `@Query("getPublicNode") @PermitAll PublicNode getPublicNode(@Name("node_link_id") @NonNull String
      nodeLinkId, @Name("access_code") String accessCode)` — delegate exactly as
      `PublicNodeDataFetchers.getNodeByPublicLinkId`: `LinkRepository.getLinkByNotExpiredPublicId(
      nodeLinkId)` (→ `LINK_NOT_FOUND`); `NodeRepository.getNode(link.getNodeId())`;
      `NodeRepository.getTrashedNode(...)` present → `NODE_NOT_FOUND`; access-code:
      `link.getAccessCode().isPresent()` && `accessCode==null` → `ACCESS_CODE_REQUIRED`; present &&
      mismatch → `WRONG_ACCESS_CODE`; build `PublicNode.from(node)`.
- [ ] `@Query("findPublicNodes") @PermitAll PublicNodePage findPublicNodes(@Name("folder_id") @NonNull
      String folderId, @Name("limit") Integer limit, @Name("node_link_id") String nodeLinkId,
      @Name("access_code") String accessCode, @Name("page_token") String pageToken)` — delegate exactly
      as `PublicNodeDataFetchers.findNodes` + `findNodesByNodePage`: `NodeRepository.getNode(folderId)`;
      `LinkRepository.isLinkValidForNode(nodeLinkId, folder)` (→ `NODE_NOT_FOUND`);
      `LinkRepository.getLinkByNotExpiredPublicId(nodeLinkId)`; access-code check (legacy collapses both
      cases to `ACCESS_CODE_REQUIRED` — preserve that); `NodeRepository.publicFindNodes(folderId, limit,
      pageToken): ImmutablePair<List<Node>,String>`; build `PublicNodePage(nodes.map(PublicNode::from),
      pageToken)`. **Name is `findPublicNodes` (renamed from the public `findNodes`); the internal
      `findNodes` is untouched.**
- [ ] TDD `PublicApiIT`: POST `/graphql` (NO cookie) `{ getPublicNode(...) { ... } }` and
      `{ findPublicNodes(folder_id: ...) { nodes { ... } page_token } }` succeed; assert the returned
      types are `PublicFile`/`PublicFolder`/`PublicNodePage` and the query names are `getPublicNode` /
      `findPublicNodes` (do NOT assert absence of `findNodes`). Access-code error paths → the shared
      codes.
- [ ] Run the FULL gate `./mvnw -q -pl app test -Dtest=SchemaContractTest`; expect **GREEN** (the
      generated schema now equals the frozen `docs/schema.graphql`). Run the whole `app` test suite.
      Commit.

---

## Phase 6 — Native image + teardown

### Task P6.1: native reflection + native build args

**Files:** `app/src/main/java/com/zextras/carbonio/files/config/NativeReflectionConfig.java` (modify).

- [ ] SmallRye GraphQL registers its own model reflection at build time (the `@Type`/`@Interface`/`@Enum`
      beans are indexed), so the manual `PublicNode.class`/`Permissions.class` entries are obsolete.
      Remove `PublicNode.class` and `Permissions.class` from `@RegisterForReflection` (they will be
      deleted in P6.2). KEEP the Jackson DTOs (`BlobResponse`, `UploadAttachmentResponse`,
      `UploadVersionResponse`, `UploadToRequest`, `PreviewQueryParameters`, `NodeRepositoryImpl.PageToken`,
      `MyselfDto`, `UserInfoDto`, `HealthResponse`, `ServiceHealth`, `DependencyType`).
- [ ] In `application.properties` `quarkus.native.additional-build-args`: the graphql-java i18n
      ResourceBundle args (`i18n.General/Parsing/Scalars/Validation/Execution`) and
      `--initialize-at-run-time=graphql.util.IdGenerator` were for the schema-first engine. SmallRye's
      needed bundles (`i18n.Scalars`, `i18n.Execution`) are registered by the extension
      (`ExtensionsGraphqlProcessor.registerGraphqlJavaResourceBundles`); Quarkus SmallRye registers
      `i18n.Validation`/`i18n.Parsing`. Remove the 5 manual `-H:IncludeResourceBundles` entries and the
      `graphql.util.IdGenerator` run-time-init (keep `org.postgresql.sspi.SSPIClient`). Also remove
      `quarkus.native.resources.includes=api/*.graphql` (the SDL files are deleted; the schema is
      code-first).
- [ ] VERIFY (§13) via a native build (`./mvnw -q -pl app -Pnative -DskipTests package`) that boot +
      an authenticated query + a public query work; fallback: if a missing-bundle/`MissingResourceException`
      surfaces, re-add only the specific `-H:IncludeResourceBundles` entry the stack trace names.
- [ ] Commit.

### Task P6.2: teardown (delete the legacy graphql-java stack)

**Files:** deletions per §11. Do this ONLY here, only after Phases 1–5 are green.

- [ ] Delete: `app/src/main/resources/api/schema.graphql`, `api/public-schema.graphql`;
      `graphql/GraphQLProvider.java`, `graphql/PublicGraphQLProvider.java`,
      `graphql/FilesGraphQLRoutes.java`, `graphql/FilesAuthenticationFilter.java`,
      `graphql/GraphQLRequest.java`, `graphql/SyncCompletableFuture.java`,
      `graphql/GraphQLSchemaContributor.java`, `graphql/GraphQLWiringContributor.java`,
      `graphql/GraphQLFieldValidationContributor.java`; the entire `graphql/datafetchers/` (incl
      `DateTimeScalar`), `graphql/dataloaders/`, `graphql/validators/`; `graphql/errors/GraphQLResultErrors.java`;
      `graphql/types/PublicNode.java` + `graphql/types/Permissions.java` (superseded by the model classes).
      KEEP `graphql/errors/ErrorCodes.java` (reused), `graphql/errors/CopyFailureClassifier.java` +
      `DefaultCopyFailureClassifier.java` (used by cloneVersion/copyNodes).
- [ ] `app/pom.xml`: remove `com.graphql-java:graphql-java`, `com.graphql-java:java-dataloader`, and
      `commons-validator:commons-validator` from the COMPILE deps (§10, §11). (If a native/i18n concern
      remains, keep graphql-java transitively only via the test-scope schema-builder used by
      `SchemaContractTest` — it must NOT be a compile dep.) If `commons-validator` is removed, replace the
      email check in `GraphQLInputValidator` with an inline regex (RFC-lite) and update its test.
- [ ] Note on the CE-extension seam: the graphql-java contributor SPI is gone. In code-first, the
      Advanced edition extends the schema simply by shipping additional `@GraphQLApi`/`@Type` beans on
      the classpath (SmallRye auto-discovers them) — no `SchemaContributor`/`WiringContributor` needed.
      Document this in the class-level Javadoc of one `@GraphQLApi` bean (e.g. `NodeApi`).
- [ ] `./mvnw -q -pl app test` (full suite incl `SchemaContractTest`) — expect ALL GREEN with the
      legacy stack removed. `./mvnw -q -pl app -Pnative -DskipTests package` — expect a clean native
      build. Commit. Open the PR (`feat/graphql-code-first` → `devel`).

---

## Self-Review

### Spec coverage — every SDL element mapped

**Enums (7):** SharePermission→P2.1, NodeSort→P2.1, ShareSort→P2.1, NodeType (incl ROOT, unified)→P2.1,
NotificationType→P2.1, AddedNodeType→P2.1, RemovedNodeType→P2.1. ✅

**Unions (3):** SharedTarget→P2.5 (UserModel, DistributionListModel), Account→P2.5 (same members),
Notification→P2.5 (NewShare/AddedNode/RemovedNode). ✅

**Object/interface types (18 internal):** User→P2.2, Config→P2.2, DistributionList→P2.2 (+users @Source
P4.4), Permissions→P2.2, Root→P2.2, SnapshotNode→P2.2, SnapshotUser→P2.2, Node(interface)→P2.3, File→P2.3,
Folder→P2.3 (+children @Source P4.4), Share→P2.4 (+node/share_target @Source P4.3/P4.4), Link→P2.4
(+node @Source P4.4), CollaborationLink→P2.4 (+node @Source P4.4), NewShare/AddedNode/RemovedNode→P2.5,
NodePage→P2.5, NotificationPage→P2.5. **Node relational members**: creator/owner/last_editor→P4.2
(batch), parent/shares→P4.3 (batch), share/permissions/links/collaboration_links→P4.3/P4.4 (single). ✅

**Public types (4):** PublicNode(interface)/PublicFolder/PublicFile/PublicNodePage→P5.1. ✅

**Internal queries (13):** getNode/findNodes/getVersions/getPath/getRootsList→P3.1;
getUserById/getAccountByEmail/getAccountsByEmail→P3.6; getShare→P3.3; getLinks→P3.4;
getCollaborationLinks→P3.5; getConfigs→P3.7; getNotifications→P3.8. ✅

**Internal mutations (19):** createFolder/updateNode/flagNodes/trashNodes/restoreNodes/moveNodes/
deleteNodes/copyNodes/deleteVersions/keepVersions/cloneVersion→P3.2; createShare/updateShares/
deleteShares→P3.3; createLink/updateLink/deleteLinks→P3.4; createCollaborationLink/
deleteCollaborationLinks→P3.5. ✅

**Public queries (2):** getPublicNode, findPublicNodes (renamed)→P5.2 (@PermitAll). ✅

**Scalars:** DateTime→`long`/`Long`→`BigInteger` (P0.1 renderer emits `scalar BigInteger`; verified
mapping); `File.size`/public `size` kept `Float` via `double` (P0.3 NOTE). ✅

**Errors:** ONE `FilesGraphQLException` + reused `ErrorCodes` (14) + ONE `errorCode` extension provider
(P4.1); duplicated NODE/LINK/ACCESS codes merged; partial-success via `GraphQLException(msg,
partialResults)` (P3.2/P3.3/P3.4). ✅

**Auth:** OPTIONAL `FilesGraphQLAuthMechanism` (P1.1) + `@RequestScoped` UserMyself producer (P1.2) +
per-op `@Authenticated`/`@PermitAll`; introspection off in prod / schema-available dev-test, ONE place
(P0.2). ✅  **Golden gate:** ONE `SchemaContractTest` + ONE `docs/schema.graphql` (P0.4/P0.3). ✅
**Teardown:** ONE final task (P6.2). ✅

### Placeholder scan
No `TODO`, no `{ ... }` op bodies, no "similar to Task N". Repetitive ops are per-item checklists citing
the exact delegate method (read from the real datafetchers) with exact `@Name`/Java type; distinct
patterns (one `@GraphQLApi` with `@Query`+`@Mutation`; one `@Type` incl `@Source`; one interface; one
enum; one union; one batch `@Source`; one single `@Source`; the auth mechanism; the identity producer;
`SchemaContractTest`; one validation; one error mapping; `PublicFile`+`PublicApi`) have full real code.
Every path is exact and absolute-from-root. All uncertain SmallRye SPIs are VERIFY steps with a stated
fallback (batch `@Source` size cap, `ErrorExtensionProvider` discovery, introspection toggle, native
bundles, in-test index coverage).

### Type/naming consistency (single scheme)
ONE model-naming scheme: internal `@Type`/`@Interface` → `*Model` with `@Type("<Sdl>")`; enums → no
suffix, `@Enum("<Sdl>")`; unions → `@Union("<Sdl>")` marker interfaces (no suffix); public → `Public*`.
ONE `NodeType` (incl ROOT) shared internal+public. ONE enum-value set each (pinned). ONE `@GraphQLApi`
bean per file, authored once (Node/Share/Link/CollaborationLink/User/Config/Notification/Public), with
`@Source` methods ADDED to the same file in Phase 4 (no second copy). ONE `SchemaContractTest`, ONE
baseline. ONE error hierarchy. Every `@Name` matches the SDL exactly (`flagged` not `favorite`; `rootId`
camelCase; `mime_type`/`keep_forever`/`cloned_from_version`/`created_at`/`share_target_id`/`page_token`
snake_case). No duplicate resolver for any field (batched xor single, never both).
