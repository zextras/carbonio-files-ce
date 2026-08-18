// SPDX-FileCopyrightText: 2022 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

library(
    identifier: 'jenkins-lib-common@v4.5.0',
    retriever: modernSCM([
        $class: 'GitSCMSource',
        credentialsId: 'jenkins-integration-with-github-account',
        remote: 'git@github.com:zextras/jenkins-lib-common.git',
    ])
)

properties(defaultPipelineProperties())

// Quarkus JVM uber-jar build (NON-native for now — mirrors carbonio-user-management on devel).
// jarBuild copies the app module's `*-runner.jar` (the Quarkus uber-jar, produced because
// application.properties sets quarkus.package.jar.type=uber-jar) into package/ as
// `carbonio-files-ce.jar`, consumed by package/PKGBUILD (install to /usr/share/carbonio) and
// docker/Dockerfile. No nativeBuild block => no GraalVM/Mandrel stage. mavenPublish still ships
// the sdk AND the app: the app's *thin* jar (Quarkus keeps the -runner suffix on the uber-jar, so
// the plain classes jar stays the Maven main artifact) is what carbonio-files (Advanced) consumes.
dt3_pipeline(
    repoName: 'carbonio-files-ce',
    mavenPublish: ['sdk', 'app'],
    jarBuild: [jarName: 'carbonio-files-ce.jar'],
    packaging: [
        buildFlags: '-ds',
        // Stage the live-config watch bridge (package/watches/*, the pika Consul-KV ->
        // message-broker republisher) into package/ before yap runs, so the PKGBUILD can install
        // carbonio-files-watches.service / -start-watches.sh / -handle-kv-changes.py. Mirrors
        // carbonio-files (Advanced); the uber-jar is staged automatically from jarBuild.jarName.
        preBuildScript: 'cp -a package/watches/* package/',
    ],
    docker: [
        [dockerfile: 'docker/Dockerfile',
         imageName: 'carbonio-files-ce',
         title: 'Carbonio Files CE',
         description: 'Carbonio Files Community Edition',
         platforms: ['linux/amd64', 'linux/arm64'] as Set],
    ],
    reuse: [projectType: 'CE'],
    flywayGuard: [
        migrationPaths: ['app/src/main/resources/db/migration'],
    ]
)
