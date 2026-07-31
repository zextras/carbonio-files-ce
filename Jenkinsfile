// SPDX-FileCopyrightText: 2022 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

library(
    identifier: 'jenkins-lib-common@feat/verify-only',
    retriever: modernSCM([
        $class: 'GitSCMSource',
        credentialsId: 'jenkins-integration-with-github-account',
        remote: 'git@github.com:zextras/jenkins-lib-common.git',
    ])
)

// carbonio-files-ce uses a maven-shade fat JAR (boot/target/carbonio-files-*-jar-with-dependencies.jar),
// not a Quarkus *-runner.jar. dt3_pipeline's jarBuild copies only *-runner.jar patterns, so we use
// appModule: 'boot' to enable the Java build stage and handle the JAR + watches copy via
// packaging.overrides.preBuildScript (runs in the yap container after workspace unstash, before yap build).
// dt3_buildWithZextrasRepo merges its own preBuildScript (repo injection) before ours, so
// the order is: [repo setup] → [jar copy + watches copy] → yap build.
dt3_pipeline(
    repoName: 'carbonio-files-ce',
    appModule: 'boot',
    packaging: [
        addCarbonioRepos: true,
        preBuildScript: '''
                    cp -a boot/target/carbonio-files-*-jar-with-dependencies.jar package/carbonio-files.jar
                    cp -a package/watches/* package/
                ''',
    ],
    docker: [[
        dockerfile: 'docker/Dockerfile',
        imageName: 'carbonio-files-ce',
        title: 'Carbonio Files CE',
        description: 'Carbonio Files Community Edition',
        platforms: ['linux/amd64', 'linux/arm64'] as Set,
    ]],
    reuse: [projectType: 'CE'],
    flywayGuard: [
        migrationPaths: ['core/src/main/resources/db/migration'],
    ],
)
