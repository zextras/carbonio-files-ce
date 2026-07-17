// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.acceptance;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

/**
 * Purity guard for the immutable black-box acceptance suite (see plan §0.1 / §0.1's physical
 * isolation layout).
 *
 * <p>Everything under {@code com.zextras.carbonio.files.acceptance} — except the mutable seam
 * implementation confined to {@code acceptance.seam.impl} — must depend ONLY on neutral types
 * (the {@code FilesTestApp}/{@code TestDataAccess}/{@code Mocks} seam, neutral HTTP POJOs, JUnit,
 * AssertJ, JSON helpers). It must NOT reach for Guice, MockServer, Netty, or Ebean-DSL types
 * directly, and it must NOT import the Ebean repository-implementation package — those are
 * Bucket-C / framework-bound and are exactly what the seam exists to keep out of the 31 immutable
 * IT bodies. If this test fails on a class other than via the seam impl, that is a real
 * regression: either the seam leaked, or a test body reached around it.
 */
class AcceptanceSuitePurityTest {

  @Test
  void acceptanceSuiteBodiesMustNotDependOnFrameworkTypes() {
    JavaClasses importedClasses =
        new ClassFileImporter().importPackages("com.zextras.carbonio.files.acceptance");

    ArchRule rule =
        noClasses()
            .that()
            .resideInAPackage("..acceptance..")
            .and()
            .resideOutsideOfPackage("..acceptance.seam.impl..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(
                "com.google.inject..",
                "org.mockserver..",
                "io.netty..",
                "io.ebean..",
                "com.zextras.carbonio.files.dal.repositories.impl.ebean..");

    rule.check(importedClasses);
  }
}
