// SPDX-FileCopyrightText: 2022 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

def buildContainer(String title, String description, String dockerfile, String tag) {
    sh 'docker build ' +
            '--label org.opencontainers.image.title="' + title + '" ' +
            '--label org.opencontainers.image.description="' + description + '" ' +
            '--label org.opencontainers.image.vendor="Zextras" ' +
            '-f ' + dockerfile + ' -t ' + tag + ' .'
    sh 'docker push ' + tag
}

pipeline {
    agent {
        node {
            label 'zextras-v1'
        }
    }
    environment {
        JAVA_OPTS = '-Dfile.encoding=UTF8'
        LC_ALL = 'C.UTF-8'
        jenkins_build = 'true'
    }
    parameters {
        booleanParam defaultValue: false, description: 'Whether to upload the packages in playground repositories', name: 'PLAYGROUND'
        booleanParam defaultValue: false, description: 'Whether to upload the packages in custom repositories', name: 'CUSTOM'
        choice choices: ['rc-jdk17'], description: 'Suffix of the custom repositories (it uploads on the specified repo only if CUSTOM flag is checked)', name: 'SUFFIX_CUSTOM_REPOS'
        booleanParam defaultValue: false, description: 'Run dependencyCheck', name: 'RUN_DEPENDENCY_CHECK'
    }
    options {
        buildDiscarder(logRotator(numToKeepStr: '25'))
        timeout(time: 2, unit: 'HOURS')
        skipDefaultCheckout()
    }
    stages {
        stage('Checkout') {
            steps {
                checkout scm
                script {
                    env.GIT_COMMIT = sh(script: 'git rev-parse HEAD', returnStdout: true).trim()
                }
            }
        }
        stage('Check SNAPSHOT version') {
            when {
                allOf {
                    expression { env.BRANCH_NAME.contains("PR") }
                }
            }
            steps {
                script {
                    def commentMessage = ""
                    def commitTitle = env.CHANGE_TITLE ? env.CHANGE_TITLE : ""
                    if (!commitTitle.contains("chore(release)") && !readFile('package/PKGBUILD').trim().contains('SNAPSHOT')) {
                        commentMessage = "Please increase the micro version in the `pkgver` and add a **SNAPSHOT** label to the `pkgrel`."
                    }

                    if (commitTitle.contains("chore(release)") && readFile('package/PKGBUILD').trim().contains('SNAPSHOT')) {
                        commentMessage = "Please remove the **SNAPSHOT** label to the `pkgrel`."
                    }

                    if (commentMessage) {
                        withCredentials([usernamePassword(credentialsId: 'tarsier-bot-pr-token-github', usernameVariable: 'GH_USERNAME', passwordVariable: 'GH_TOKEN')]) {
                            sh(script: """
                              curl https://api.github.com/repos/zextras/carbonio-files-ce/issues/${env.CHANGE_ID}/comments \
                              -X POST \
                              -H 'Accept: application/vnd.github.v3+json' \
                              -H 'Authorization: token ${GH_TOKEN}' \
                              -d '{
                                  \"body\": \"${commentMessage}\\nMake sure to update the `PKGBUILD` and all `pom.xml` files accordingly.\"
                              }'
                          """)
                        }
                        error("The development package version is not marked as SNAPSHOT")
                    }
                }
            }
        }
        stage('Setup') {
            steps {
                withCredentials([file(credentialsId: 'jenkins-maven-settings.xml', variable: 'SETTINGS_PATH')]) {
                    sh 'cp $SETTINGS_PATH settings-jenkins.xml'
                }
            }
        }
        stage('Build jar') {
            steps {
                container('jdk-17') {
                   sh 'mvn -B --settings settings-jenkins.xml clean package'
                   sh 'cp boot/target/carbonio-files-*-jar-with-dependencies.jar package/carbonio-files.jar'
                   sh 'cp package/watches/* package/'
                }
            }
        }
        stage("Tests") {
            parallel {
                stage("UTs") {
                    steps {
                        container('jdk-17') {
                            sh 'mvn -B --settings settings-jenkins.xml verify -P run-unit-tests'
                        }
                    }
                }
                stage("ITs") {
                    steps {
                        container('jdk-17') {
                            sh 'mvn -B --settings settings-jenkins.xml verify -P run-integration-tests'
                        }
                    }
                }
            }
        }
        stage('Coverage') {
            steps {
                container('jdk-17') {
                    sh 'mvn -B --settings settings-jenkins.xml verify -P generate-jacoco-full-report'
                    recordCoverage(tools: [[parser: 'JACOCO']],sourceCodeRetention: 'MODIFIED')
                }
            }
        }
        stage('SonarQube analysis') {
            when {
                anyOf {
                    branch 'develop'
                    expression { env.BRANCH_NAME.contains("PR") }
                }
            }
            steps {
                container('jdk-17') {
                    withSonarQubeEnv(credentialsId: 'sonarqube-user-token', installationName: 'SonarQube instance') {
                        sh 'mvn -B --settings settings-jenkins.xml sonar:sonar'
                    }
                }
            }
        }
        stage('Build deb/rpm') {
            stages {
                // Replace the pkgrel value with the git commit hash to ensure that
                // each merged PR has unique artifacts and to prevent conflicts between them.
                // Note that the pkgrel value will remain as it was in the codebase to avoid
                // conflicts between multiple open PRs
                stage('Add timestamp and commit hash') {
                    when {
                        branch 'develop'
                    }
                    steps {
                        script {
                            def timestamp = sh(script: 'date +%s', returnStdout: true).trim()
                            def gitCommitShort = env.GIT_COMMIT.take(8)
                            sh """
                                sed -i "s/pkgrel=\\".*\\"/pkgrel=\\"${timestamp}+${gitCommitShort}\\"/" ./package/PKGBUILD
                            """
                        }
                    }
                }
                stage('Stash') {
                    steps {
                        stash includes: 'yap.json,package/**', name: 'binaries'
                    }
                }
                stage('yap') {
                    parallel {
                        stage('Ubuntu 20.04') {
                            agent {
                                node {
                                    label 'yap-ubuntu-20-v1'
                                }
                            }
                            steps {
                                container('yap') {
                                    unstash 'binaries'
                                    sh 'sudo yap build ubuntu-focal .'
                                    stash includes: 'artifacts/*focal*.deb', name: 'artifacts-ubuntu-focal'
                                }
                            }
                            post {
                                always {
                                    archiveArtifacts artifacts: 'artifacts/*focal*.deb', fingerprint: true
                                }
                            }
                        }
                        stage('Ubuntu 22.04') {
                            agent {
                                node {
                                    label 'yap-ubuntu-22-v1'
                                }
                            }
                            steps {
                                container('yap') {
                                    unstash 'binaries'
                                    sh 'sudo yap build ubuntu-jammy .'
                                    stash includes: 'artifacts/*jammy*.deb', name: 'artifacts-ubuntu-jammy'
                                }
                            }
                            post {
                                always {
                                    archiveArtifacts artifacts: 'artifacts/*jammy*.deb', fingerprint: true
                                }
                            }
                        }
                        stage('Ubuntu 24.04') {
                            agent {
                                node {
                                    label 'yap-ubuntu-24-v1'
                                }
                            }
                            steps {
                                container('yap') {
                                    unstash 'binaries'
                                    sh 'sudo yap build ubuntu-noble .'
                                    stash includes: 'artifacts/*noble*.deb', name: 'artifacts-ubuntu-noble'
                                }
                            }
                            post {
                                always {
                                    archiveArtifacts artifacts: 'artifacts/*noble*.deb', fingerprint: true
                                }
                            }
                        }
                        stage('RHEL8') {
                            agent {
                                node {
                                    label 'yap-rocky-8-v1'
                                }
                            }
                            steps {
                                container('yap') {
                                    unstash 'binaries'
                                    sh 'sudo yap build rocky-8 .'
                                    stash includes: 'artifacts/*el8*.rpm', name: 'artifacts-rocky-8'
                                }
                            }
                            post {
                                always {
                                    archiveArtifacts artifacts: 'artifacts/*el8*.rpm', fingerprint: true
                                }
                            }
                        }
                        stage('RHEL9') {
                            agent {
                                node {
                                    label 'yap-rocky-9-v1'
                                }
                            }
                            steps {
                                container('yap') {
                                    unstash 'binaries'
                                    sh 'sudo yap build rocky-9 .'
                                    stash includes: 'artifacts/*el9*.rpm', name: 'artifacts-rocky-9'
                                }
                            }
                            post {
                                always {
                                    archiveArtifacts artifacts: 'artifacts/*el9*.rpm', fingerprint: true
                                }
                            }
                        }
                    }
                }
            }
        }
        stage('Upload to Develop') {
            when {
                branch 'develop'
            }
            steps {
                unstash 'artifacts-ubuntu-focal'
                unstash 'artifacts-ubuntu-jammy'
                unstash 'artifacts-ubuntu-noble'
                unstash 'artifacts-rocky-8'
                unstash 'artifacts-rocky-9'

                script {
                    // ubuntu
                    def server = Artifactory.server 'zextras-artifactory'
                    def buildInfo
                    def uploadSpec

                    buildInfo = Artifactory.newBuildInfo()
                    uploadSpec = """{
                        "files": [
                            {
                                "pattern": "artifacts/*focal*.deb",
                                "target": "ubuntu-devel/pool/",
                                "props": "deb.distribution=focal;deb.component=main;deb.architecture=amd64;vcs.revision=${env.GIT_COMMIT}"
                            },
                            {
                                "pattern": "artifacts/*jammy*.deb",
                                "target": "ubuntu-devel/pool/",
                                "props": "deb.distribution=jammy;deb.component=main;deb.architecture=amd64;vcs.revision=${env.GIT_COMMIT}"
                            },
                            {
                                "pattern": "artifacts/*noble*.deb",
                                "target": "ubuntu-devel/pool/",
                                "props": "deb.distribution=noble;deb.component=main;deb.architecture=amd64;vcs.revision=${env.GIT_COMMIT}"
                            },
                            {
                                "pattern": "artifacts/(carbonio-files-ce)-(*).el8.x86_64.rpm",
                                "target": "centos8-devel/zextras/{1}/{1}-{2}.el8.x86_64.rpm",
                                "props": "rpm.metadata.arch=x86_64;rpm.metadata.vendor=zextras;vcs.revision=${env.GIT_COMMIT}"
                            },
                            {
                                "pattern": "artifacts/(carbonio-files-ce)-(*).el9.x86_64.rpm",
                                "target": "rhel9-devel/zextras/{1}/{1}-{2}.el9.x86_64.rpm",
                                "props": "rpm.metadata.arch=x86_64;rpm.metadata.vendor=zextras;vcs.revision=${env.GIT_COMMIT}"
                            }
                        ]
                    }"""
                    server.upload spec: uploadSpec, buildInfo: buildInfo, failNoOp: false
                }
            }
        }
        stage('Upload to Playground') {
            when {
                anyOf {
                    branch 'playground/*'
                    expression { params.PLAYGROUND == true }
                }
            }
            steps {
                unstash 'artifacts-ubuntu-focal'
                unstash 'artifacts-ubuntu-jammy'
                unstash 'artifacts-ubuntu-noble'
                script {
                    def server = Artifactory.server 'zextras-artifactory'
                    def buildInfo
                    def uploadSpec

                    buildInfo = Artifactory.newBuildInfo()
                    uploadSpec = """{
                        "files": [
                            {
                                "pattern": "artifacts/*focal*.deb",
                                "target": "ubuntu-playground/pool/",
                                "props": "deb.distribution=focal;deb.component=main;deb.architecture=amd64;vcs.revision=${env.GIT_COMMIT}"
                            },
                            {
                                "pattern": "artifacts/*jammy*.deb",
                                "target": "ubuntu-playground/pool/",
                                "props": "deb.distribution=jammy;deb.component=main;deb.architecture=amd64;vcs.revision=${env.GIT_COMMIT}"
                            },
                            {
                                "pattern": "artifacts/*noble*.deb",
                                "target": "ubuntu-playground/pool/",
                                "props": "deb.distribution=noble;deb.component=main;deb.architecture=amd64;vcs.revision=${env.GIT_COMMIT}"
                            }
                        ]
                    }"""
                    server.upload spec: uploadSpec, buildInfo: buildInfo, failNoOp: false
                }
            }
        }
        stage('Upload to Custom') {
            when {
                anyOf {
                    expression { params.CUSTOM == true }
                }
            }
            steps {
                unstash 'artifacts-ubuntu-focal'
                unstash 'artifacts-ubuntu-jammy'
                unstash 'artifacts-ubuntu-noble'
                unstash 'artifacts-rocky-8'
                unstash 'artifacts-rocky-9'

                script {
                    def server = Artifactory.server 'zextras-artifactory'
                    def buildInfo
                    def uploadSpec

                    buildInfo = Artifactory.newBuildInfo()
                    uploadSpec = """{
                        "files": [
                            {
                                "pattern": "artifacts/*focal*.deb",
                                "target": "ubuntu-''' + params.SUFFIX_CUSTOM_REPOS + '''/pool/",
                                "props": "deb.distribution=focal;deb.component=main;deb.architecture=amd64;vcs.revision=${env.GIT_COMMIT}"
                            },
                            {
                                "pattern": "artifacts/*jammy*.deb",
                                "target": "ubuntu-''' + params.SUFFIX_CUSTOM_REPOS + '''/pool/",
                                "props": "deb.distribution=jammy;deb.component=main;deb.architecture=amd64;vcs.revision=${env.GIT_COMMIT}"
                            },
                            {
                                "pattern": "artifacts/*noble*.deb",
                                "target": "ubuntu-''' + params.SUFFIX_CUSTOM_REPOS + '''/pool/",
                                "props": "deb.distribution=noble;deb.component=main;deb.architecture=amd64;vcs.revision=${env.GIT_COMMIT}"
                            }
                            {
                                "pattern": "artifacts/(carbonio-files-ce)-(*).el8.x86_64.rpm",
                                "target": "centos8-''' + params.SUFFIX_CUSTOM_REPOS + '''/zextras/{1}/{1}-{2}.el8.x86_64.rpm",
                                "props": "rpm.metadata.arch=x86_64;rpm.metadata.vendor=zextras;vcs.revision=${env.GIT_COMMIT}"
                            },
                            {
                                "pattern": "artifacts/(carbonio-files-ce)-(*).el9.x86_64.rpm",
                                "target": "rhel9-''' + params.SUFFIX_CUSTOM_REPOS + '''/zextras/{1}/{1}-{2}.el9.x86_64.rpm",
                                "props": "rpm.metadata.arch=x86_64;rpm.metadata.vendor=zextras;vcs.revision=${env.GIT_COMMIT}"
                            }
                        ]
                    }"""
                    server.upload spec: uploadSpec, buildInfo: buildInfo, failNoOp: false
                }
            }
        }
        stage('Upload & Promotion Config') {
            when {
                anyOf {
                    branch 'release/*'
                    buildingTag()
                }
            }
            steps {
                unstash 'artifacts-ubuntu-focal'
                unstash 'artifacts-ubuntu-jammy'
                unstash 'artifacts-ubuntu-noble'
                unstash 'artifacts-rocky-8'
                unstash 'artifacts-rocky-9'

                script {
                    def server = Artifactory.server 'zextras-artifactory'
                    def buildInfo
                    def uploadSpec
                    def config

                    //ubuntu
                    buildInfo = Artifactory.newBuildInfo()
                    buildInfo.name += '-ubuntu'
                    uploadSpec= """{
                        "files": [
                            {
                                "pattern": "artifacts/*focal*.deb",
                                "target": "ubuntu-rc/pool/",
                                "props": "deb.distribution=focal;deb.component=main;deb.architecture=amd64;vcs.revision=${env.GIT_COMMIT}"
                            },
                            {
                                "pattern": "artifacts/*jammy*.deb",
                                "target": "ubuntu-rc/pool/",
                                "props": "deb.distribution=jammy;deb.component=main;deb.architecture=amd64;vcs.revision=${env.GIT_COMMIT}"
                            },
                            {
                                "pattern": "artifacts/*noble*.deb",
                                "target": "ubuntu-rc/pool/",
                                "props": "deb.distribution=noble;deb.component=main;deb.architecture=amd64;vcs.revision=${env.GIT_COMMIT}"
                            }
                        ]
                    }"""
                    server.upload spec: uploadSpec, buildInfo: buildInfo, failNoOp: false
                    config = [
                            'buildName'          : buildInfo.name,
                            'buildNumber'        : buildInfo.number,
                            'sourceRepo'         : 'ubuntu-rc',
                            'targetRepo'         : 'ubuntu-release',
                            'comment'            : 'Do not change anything! Just press the button',
                            'status'             : 'Released',
                            'includeDependencies': false,
                            'copy'               : true,
                            'failFast'           : true
                    ]
                    Artifactory.addInteractivePromotion server: server, promotionConfig: config, displayName: 'Ubuntu Promotion to Release'
                    server.publishBuildInfo buildInfo

                    //rhel 8
                    buildInfo = Artifactory.newBuildInfo()
                    buildInfo.name += '-centos8'
                    uploadSpec= """{
                        "files": [
                            {
                                "pattern": "artifacts/(carbonio-files-ce)-(*).el8.x86_64.rpm",
                                "target": "centos8-rc/zextras/{1}/{1}-{2}.el8.x86_64.rpm",
                                "props": "rpm.metadata.arch=x86_64;rpm.metadata.vendor=zextras;vcs.revision=${env.GIT_COMMIT}"
                            }
                        ]
                    }"""
                    server.upload spec: uploadSpec, buildInfo: buildInfo, failNoOp: false
                    config = [
                            'buildName'          : buildInfo.name,
                            'buildNumber'        : buildInfo.number,
                            'sourceRepo'         : 'centos8-rc',
                            'targetRepo'         : 'centos8-release',
                            'comment'            : 'Do not change anything! Just press the button',
                            'status'             : 'Released',
                            'includeDependencies': false,
                            'copy'               : true,
                            'failFast'           : true
                    ]
                    Artifactory.addInteractivePromotion server: server, promotionConfig: config, displayName: 'RHEL8 Promotion to Release'
                    server.publishBuildInfo buildInfo

                    //rhel 9
                    buildInfo = Artifactory.newBuildInfo()
                    buildInfo.name += '-rhel9'
                    uploadSpec= """{
                        "files": [
                            {
                                "pattern": "artifacts/(carbonio-files-ce)-(*).el9.x86_64.rpm",
                                "target": "rhel9-rc/zextras/{1}/{1}-{2}.el9.x86_64.rpm",
                                "props": "rpm.metadata.arch=x86_64;rpm.metadata.vendor=zextras;vcs.revision=${env.GIT_COMMIT}"
                            }
                        ]
                    }"""
                    server.upload spec: uploadSpec, buildInfo: buildInfo, failNoOp: false
                    config = [
                            'buildName'          : buildInfo.name,
                            'buildNumber'        : buildInfo.number,
                            'sourceRepo'         : 'rhel9-rc',
                            'targetRepo'         : 'rhel9-release',
                            'comment'            : 'Do not change anything! Just press the button',
                            'status'             : 'Released',
                            'includeDependencies': false,
                            'copy'               : true,
                            'failFast'           : true
                    ]
                    Artifactory.addInteractivePromotion server: server, promotionConfig: config, displayName: 'RHEL9 Promotion to Release'
                    server.publishBuildInfo buildInfo
                }
            }
        }
        stage('Build and Publish Docker Image - Dev') {
            when {
                not {
                    buildingTag()
                }
            }
            steps {
                container('dind') {
                    withDockerRegistry(credentialsId: 'private-registry', url: 'https://registry.dev.zextras.com') {
                        script {
                            def branchTag = env.BRANCH_NAME.replaceAll('/', '-').toLowerCase()
                            def imageTag = "registry.dev.zextras.com/dev/carbonio-files-ce:${branchTag}"

                            buildContainer(
                                'Carbonio Files CE',
                                'Carbonio Files Community Edition',
                                'docker/minimal/carbonio-files/Dockerfile',
                                imageTag
                            )

                            // alias "latest" for last build of develop
                            if (env.BRANCH_NAME == 'develop') {
                                def latestTag = "registry.dev.zextras.com/dev/carbonio-files-ce:latest"

                                sh "docker tag ${imageTag} ${latestTag}"
                                sh "docker push ${latestTag}"
                            }
                        }
                    }
                }
            }
        }
        stage('Build and Publish Docker Image - Stable') {
            when {
                buildingTag()
            }
            steps {
                container('dind') {
                    withDockerRegistry(credentialsId: 'private-registry', url: 'https://registry.dev.zextras.com') {
                        script {
                            def releaseTag = env.TAG_NAME.startsWith('v') ? env.TAG_NAME.substring(1) : env.TAG_NAME
                            def imageTag = "registry.dev.zextras.com/dev/carbonio-files-ce:${releaseTag}"

                            buildContainer(
                                'Carbonio Files CE - Release',
                                'Carbonio Files Community Edition - Official Release',
                                'docker/minimal/carbonio-files/Dockerfile',
                                imageTag
                            )
                        }
                    }
                }
            }
        }
    }
}