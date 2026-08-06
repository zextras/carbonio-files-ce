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

// Quarkus native build. dt3_pipeline provides the Mandrel builder image and passes
// -Dquarkus.native.march=compatibility (native crashes on v2/QEMU vCPUs without it); do NOT
// pin march/Mandrel in the pom. mavenPublish ships BOTH the generated gRPC SDK (sdk module,
// carbonio-files-grpc-sdk) and the app; the *-runner is the native binary consumed by
// package/PKGBUILD (install to /usr/share/carbonio) and docker/Dockerfile.
dt3_pipeline(
    repoName: 'carbonio-files-ce',
    mavenPublish: ['sdk', 'app'],
    nativeBuild: [runnerName: 'carbonio-files-ce-runner'],
    packaging: [
        buildFlags: '-ds',
        // Stage the live-config watch bridge (package/watches/*, the pika Consul-KV ->
        // message-broker republisher) into package/ before yap runs, so the PKGBUILD can install
        // carbonio-files-watches.service / -start-watches.sh / -handle-kv-changes.py. Mirrors
        // carbonio-files (Advanced); the native *-runner is staged automatically from runnerName.
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
