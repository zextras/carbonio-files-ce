// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zextras.carbonio.files.dal.repositories.impl.NodeRepositoryImpl;
import com.zextras.carbonio.files.rest.types.BlobResponse;
import com.zextras.carbonio.files.rest.types.UploadAttachmentResponse;
import com.zextras.carbonio.files.rest.types.UploadToRequest;
import com.zextras.carbonio.files.rest.types.UploadVersionResponse;
import io.quarkus.runtime.annotations.RegisterForReflection;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Plain JUnit (NOT {@code @QuarkusTest}) regression guard for the GraalVM native-image reflection
 * registration in {@link NativeReflectionConfig}.
 *
 * <p>Under native, {@link NodeRepositoryImpl.PageToken} is (de)serialized with a raw {@link
 * ObjectMapper} and never appears in any JAX-RS/GraphQL type signature Quarkus scans at build time;
 * without an explicit {@code @RegisterForReflection} entry, the closed-world native image cannot
 * introspect its public fields, and every paginated {@code children}/{@code findNodes} response
 * throws "Unable to serialize page token". The JVM (this test) can never reproduce that failure —
 * ordinary reflection is always open here — so this test only:
 *
 * <ol>
 *   <li>asserts the {@code targets()} annotation array still contains {@code PageToken.class} (and
 *       its sibling manually-Jackson'd DTOs); this is the actual guard against someone dropping the
 *       registration, and
 *   <li>sanity-checks, on the JVM, that the same Base64-JSON round trip {@code NodeRepositoryImpl}
 *       performs preserves the token's fields.
 * </ol>
 *
 * <p>Native-reflection coverage itself can only be verified by an actual GraalVM native build/run —
 * this test proves the shape/logic on the JVM, nothing more.
 */
class NativeReflectionConfigTest {

  @Test
  void nativeReflectionConfigRegistersThePageTokenAndManualJacksonDtos() {
    RegisterForReflection annotation =
        NativeReflectionConfig.class.getAnnotation(RegisterForReflection.class);

    assertThat(annotation).isNotNull();
    assertThat(annotation.targets())
        .contains(
            NodeRepositoryImpl.PageToken.class,
            BlobResponse.class,
            UploadAttachmentResponse.class,
            UploadVersionResponse.class,
            UploadToRequest.class);
  }

  @Test
  void pageTokenSurvivesABase64JsonRoundTripOnTheJvm() throws Exception {
    // Mirrors how NodeRepositoryImpl (de)serializes the keyset pagination cursor: a raw
    // ObjectMapper over the token's public fields, Base64-url-encoded. This proves the shape/logic
    // works on the JVM; it does NOT prove native-image reflection availability (see class javadoc) —
    // ordinary JVM reflection is always open-world, so it cannot catch the closed-world native gap
    // that the targets() assertion above guards against.
    NodeRepositoryImpl.PageToken original = new NodeRepositoryImpl.PageToken();
    original.limit = 25;
    original.sort = "NAME_ASC";
    original.flagged = true;
    original.folderId = "00000000-0000-0000-0000-000000000001";
    original.cascade = false;
    original.sharedWithMe = true;
    original.sharedByMe = false;
    original.directShare = true;
    original.nodeType = "TEXT";
    original.ownerId = "00000000-0000-0000-0000-000000000002";
    original.keywords = List.of("foo", "bar");
    original.cursor = List.of("00000000-0000-0000-0000-000000000003", 42);

    ObjectMapper objectMapper = new ObjectMapper();
    String json = objectMapper.writeValueAsString(original);
    String encoded = Base64.getUrlEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8));

    String decodedJson =
        new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
    NodeRepositoryImpl.PageToken roundTripped =
        objectMapper.readValue(decodedJson, NodeRepositoryImpl.PageToken.class);

    assertThat(roundTripped.limit).isEqualTo(original.limit);
    assertThat(roundTripped.sort).isEqualTo(original.sort);
    assertThat(roundTripped.flagged).isEqualTo(original.flagged);
    assertThat(roundTripped.folderId).isEqualTo(original.folderId);
    assertThat(roundTripped.cascade).isEqualTo(original.cascade);
    assertThat(roundTripped.sharedWithMe).isEqualTo(original.sharedWithMe);
    assertThat(roundTripped.sharedByMe).isEqualTo(original.sharedByMe);
    assertThat(roundTripped.directShare).isEqualTo(original.directShare);
    assertThat(roundTripped.nodeType).isEqualTo(original.nodeType);
    assertThat(roundTripped.ownerId).isEqualTo(original.ownerId);
    assertThat(roundTripped.keywords).isEqualTo(original.keywords);
    assertThat(roundTripped.cursor).isEqualTo(original.cursor);
  }
}
