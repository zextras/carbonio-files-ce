// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.rest.resources;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.AccessTarget.MethodCallTarget;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HEAD;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Cross-cutting guard (F5, Quarkus-rewrite hardening restoration): every JAX-RS resource method in
 * {@code app/src/main} must EITHER call {@link BlobAuthenticator#requireUser} (directly, via a
 * private same-class helper, or via a lambda body — see {@link #resolvesRequireUserCall}) OR be
 * named on the {@link #ALLOWLIST} below, with a one-line reason.
 *
 * <p>This is the test that would have caught F3 on its own: {@code
 * PreviewResource#unmatchedPreviewPath} silently stopped calling {@code requireUser} during the
 * Quarkus port, and no other test in the suite is shaped to notice "one method in an otherwise-
 * authenticated class lost its auth call" — every behavioural IT test that exercises that method
 * happens to send a valid cookie. This test instead inspects the compiled bytecode of every
 * resource method directly, so it fails the moment ANY method (present or future) stops
 * authenticating without an explicit, reviewed allowlist entry.
 *
 * <p><b>Verified fault injection:</b> temporarily deleting the {@code
 * authenticator.requireUser(...)} call from {@code PreviewResource#unmatchedPreviewPath} (with no
 * allowlist entry added) makes this test fail, naming that exact method in the assertion message.
 */
class AuthenticationCoverageTest {

  /**
   * Scans the WHOLE app package (not just {@code rest.resources}) so a future JAX-RS resource class
   * added anywhere else in {@code app/src/main} is picked up automatically -- narrowing this to
   * today's one resource package would silently stop protecting the next one.
   */
  private static final String APP_PACKAGE = "com.zextras.carbonio.files";

  /** JAX-RS HTTP method designator annotations that mark a method as a resource endpoint. */
  private static final List<Class<? extends Annotation>> HTTP_METHOD_ANNOTATIONS =
      List.of(GET.class, POST.class, PUT.class, DELETE.class, HEAD.class, PATCH.class);

  /**
   * Methods that are intentionally unauthenticated (no cookie/session check), with the reason each
   * is safe. Every entry here must be independently justified — this list is the ONLY escape hatch
   * from the rule below, so it must never be used to silence a genuine gap.
   *
   * <ul>
   *   <li><b>{@code PublicBlobResource}</b> — the {@code /public/**} link-based routes: access is
   *       granted by a valid, non-expired public link (optionally + an access code), enforced by
   *       {@code BlobService} against each node; there is no session cookie to check by design (see
   *       the class javadoc).
   *   <li><b>{@code InternalBlobResource}</b> / <b>{@code InternalNodeResource}</b> — the {@code
   *       /internal/**} trusted-caller routes: reached only over the mesh, where mTLS/service
   *       intentions are the trust boundary; the acting {@code userId} is an explicit path/body
   *       parameter and node ACLs are still enforced via {@code PermissionsChecker}, but there is
   *       no cookie auth filter by design (mirrors the retired header-based {@code POST
   *       /internal/upload} / gRPC {@code FilesGrpcService} contract — see the classes' javadocs).
   * </ul>
   *
   * <p>Health checks and metrics scraping ({@code /q/health}, {@code /metrics}) are intentionally
   * NOT in this list: they are served entirely by Quarkus extensions (quarkus-smallrye-health,
   * quarkus-micrometer-registry-prometheus) with no {@code @Path}-annotated class of our own in
   * {@link #RESOURCES_PACKAGE} — {@code MetricsAcceptFilter} only tweaks a request header on a
   * plain Vert.x route and declares no JAX-RS resource method, so this scan never sees it (even
   * though the scan itself now covers the whole app package, see {@link #APP_PACKAGE}).
   */
  private static final Map<String, Set<String>> ALLOWLIST =
      Map.of(
          "com.zextras.carbonio.files.rest.resources.PublicBlobResource",
          Set.of(
              "downloadByPublicLink",
              "downloadViaPublicLink",
              "downloadPublicFile",
              "checkDownloadPublicFile",
              "downloadPublicMultiple",
              "checkDownloadPublicMultiple"),
          "com.zextras.carbonio.files.rest.resources.InternalBlobResource",
          Set.of("upload", "uploadVersion", "download", "downloadVersion"),
          "com.zextras.carbonio.files.rest.resources.InternalNodeResource",
          Set.of("getNode", "createFolder", "createPublicLink", "deleteAllNodesAndBlobs"),
          "com.zextras.carbonio.files.rest.resources.HealthResource",
          Set.of("health", "healthLive", "healthReady"));

  @Test
  void everyResourceMethodEitherAuthenticatesOrIsOnTheDocumentedAllowlist() {
    JavaClasses importedClasses = new ClassFileImporter().importPackages(APP_PACKAGE);

    List<String> offendingMethods = new ArrayList<>();

    for (JavaClass javaClass : importedClasses) {
      if (!javaClass.isAnnotatedWith(Path.class)) {
        continue;
      }

      for (JavaMethod method : javaClass.getMethods()) {
        if (!isJaxRsResourceMethod(method)) {
          continue;
        }

        String className = javaClass.getFullName();
        String methodName = method.getName();

        boolean allowlisted = ALLOWLIST.getOrDefault(className, Set.of()).contains(methodName);
        if (allowlisted) {
          continue;
        }

        if (!resolvesRequireUserCall(method)) {
          offendingMethods.add(className + "#" + methodName);
        }
      }
    }

    assertThat(offendingMethods)
        .as(
            "The following JAX-RS resource method(s) neither call"
                + " BlobAuthenticator#requireUser (directly, via a private helper, or via a lambda"
                + " body) NOR appear on AuthenticationCoverageTest's ALLOWLIST: %s -- either add"
                + " the missing authenticator.requireUser(...) call, or justify the method as an"
                + " explicit, documented allowlist entry (with a one-line reason) if it is"
                + " genuinely meant to be unauthenticated (e.g. a /public/** link-based route or an"
                + " /internal/** mesh-trusted route).",
            offendingMethods)
        .isEmpty();
  }

  private static boolean isJaxRsResourceMethod(JavaMethod method) {
    for (Class<? extends Annotation> annotation : HTTP_METHOD_ANNOTATIONS) {
      if (method.isAnnotatedWith(annotation)) {
        return true;
      }
    }
    return false;
  }

  /**
   * {@code true} if {@code method} (or a same-class method it is bound to — a direct call, a
   * private helper reached transitively, or a lambda body compiled from within it) contains a
   * bytecode call to {@code BlobAuthenticator#requireUser}.
   *
   * <p>Two indirection shapes exist in this codebase and both must be followed:
   *
   * <ul>
   *   <li><b>Helper delegation</b> — {@code BlobResource#download}/{@code #downloadVersion} call a
   *       private {@code doDownload} which calls {@code requireUser}: a genuine call-graph edge,
   *       found by recursing into same-class {@link JavaMethodCall} targets.
   *   <li><b>Lambda bodies</b> — {@code BlobResource#upload}/{@code #uploadVersion} wrap their body
   *       in {@code Uni.createFrom().item(() -> {...})}; the lambda compiles to a synthetic {@code
   *       lambda$upload$0}-shaped method that is invoked later by Mutiny's internals, NOT by a
   *       direct call recorded in {@code upload}'s own bytecode — so lambda bodies are located by
   *       the standard javac naming convention ({@code lambda$<enclosingMethod>$<index>}) instead
   *       of a call-graph edge.
   * </ul>
   */
  private static boolean resolvesRequireUserCall(JavaMethod method) {
    JavaClass owner = method.getOwner();
    Pattern lambdaPattern =
        Pattern.compile("^lambda\\$" + Pattern.quote(method.getName()) + "\\$\\d+$");

    Set<JavaMethod> seeds = new HashSet<>();
    seeds.add(method);
    for (JavaMethod candidate : owner.getMethods()) {
      if (lambdaPattern.matcher(candidate.getName()).matches()) {
        seeds.add(candidate);
      }
    }

    Set<JavaMethod> visited = new HashSet<>();
    List<JavaMethod> worklist = new ArrayList<>(seeds);
    while (!worklist.isEmpty()) {
      JavaMethod current = worklist.remove(worklist.size() - 1);
      if (!visited.add(current)) {
        continue;
      }

      for (JavaMethodCall call : current.getMethodCallsFromSelf()) {
        MethodCallTarget target = call.getTarget();
        if (target.getOwner().isEquivalentTo(BlobAuthenticator.class)
            && target.getName().equals("requireUser")) {
          return true;
        }
        if (target.getOwner().equals(owner)) {
          target.resolveMember().ifPresent(worklist::add);
        }
      }
    }
    return false;
  }
}
