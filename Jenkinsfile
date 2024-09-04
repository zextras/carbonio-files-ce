// SPDX-FileCopyrightText: 2022 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

pipeline {
    agent {
        node {
            label 'openjdk17-agent-v1'
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
                    expression { env.BRANCH_NAME != "release" }
                    expression { env.BRANCH_NAME.contains("PR") }
                    expression { !readFile('package/PKGBUILD').trim().contains('SNAPSHOT') }
                }
            }
            steps {
                withCredentials([usernamePassword(credentialsId: 'tarsier-bot-pr-token-github', usernameVariable: 'GH_USERNAME', passwordVariable: 'GH_TOKEN')]) {
                  sh(script: """
                      curl https://api.github.com/repos/zextras/carbonio-files-ce/pulls/${env.CHANGE_ID}/reviews \
                      -X POST \
                      -H 'Accept: application/vnd.github.v3+json' \
                      -H 'Authorization: token ${GH_TOKEN}' \
                      -d '{
                          \"body\": \"Please increase the micro version in the `pkgver` and add a **SNAPSHOT** label to the `pkgrel`.\\nMake sure to update the `PKGBUILD` and all `pom.xml` files accordingly.\",
                          \"event\": \"REQUEST_CHANGES\"
                      }'
                  """)
                }
                error("The development package version is not marked as SNAPSHOT")
            }
        }
        stage('Setup') {
            steps {
                withCredentials([file(credentialsId: 'jenkins-maven-settings.xml', variable: 'SETTINGS_PATH')]) {
                    sh "cp ${SETTINGS_PATH} settings-jenkins.xml"
                }
            }
        }
        stage('Build jar') {
            steps {
                sh 'mvn -B --settings settings-jenkins.xml clean package'
                sh 'cp boot/target/carbonio-files-ce-*-jar-with-dependencies.jar package/carbonio-files.jar'
                sh 'cp core/src/main/resources/carbonio-files.properties package/config.properties'
            }
        }
        stage("Tests") {
            parallel {
                stage("UTs") {
                    steps {
                        sh 'mvn -B --settings settings-jenkins.xml verify -P run-unit-tests'
                    }
                }
                stage("ITs") {
                    steps {
                        sh 'mvn -B --settings settings-jenkins.xml verify -P run-integration-tests'
                    }
                }
            }
        }
        stage('Coverage') {
            steps {
                sh 'mvn -B --settings settings-jenkins.xml verify -P generate-jacoco-full-report'
                recordCoverage(tools: [[parser: 'JACOCO']],sourceCodeRetention: 'MODIFIED')
            }
        }
        stage('Dependency check'){
            when {
                expression { params.RUN_DEPENDENCY_CHECK == true }
            }
            steps {
                dependencyCheck additionalArguments: '''-f "HTML" --prettyPrint''', odcInstallation: 'dependency-check'
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
                withSonarQubeEnv(credentialsId: 'sonarqube-user-token', installationName: 'SonarQube instance') {
                    sh 'mvn -B --settings settings-jenkins.xml sonar:sonar'
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
                        stage('Ubuntu') {
                            agent {
                                node {
                                    label 'yap-agent-ubuntu-20.04-v2'
                                }
                            }
                            steps {
                                dir('/tmp/staging'){
                                    unstash 'binaries'
                                }
                                sh 'sudo yap build ubuntu /tmp/staging/'
                                stash includes: 'artifacts/', name: 'artifacts-deb'
                            }
                            post {
                                always {
                                    archiveArtifacts artifacts: 'artifacts/*.deb', fingerprint: true
                                }
                            }
                        }
                        stage('RHEL') {
                            agent {
                                node {
                                    label 'yap-agent-rocky-8-v2'
                                }
                            }
                            steps {
                                dir('/tmp/staging'){
                                    unstash 'binaries'
                                }
                                sh 'sudo yap build rocky /tmp/staging/'
                                stash includes: 'artifacts/x86_64/*.rpm', name: 'artifacts-rpm'
                            }
                            post {
                                always {
                                    archiveArtifacts artifacts: 'artifacts/x86_64/*.rpm', fingerprint: true
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
                unstash 'artifacts-deb'
                unstash 'artifacts-rpm'
                script {
                    // ubuntu
                    def server = Artifactory.server 'zextras-artifactory'
                    def buildInfo
                    def uploadSpec

                    buildInfo = Artifactory.newBuildInfo()
                    uploadSpec = """{
                        "files": [
                            {
                                "pattern": "artifacts/*.deb",
                                "target": "ubuntu-devel/pool/",
                                "props": "deb.distribution=focal;deb.distribution=jammy;deb.component=main;deb.architecture=amd64;vcs.revision=${env.GIT_COMMIT}"
                            },
                            {
                                "pattern": "artifacts/x86_64/(carbonio-files-ce)-(*).x86_64.rpm",
                                "target": "centos8-devel/zextras/{1}/{1}-{2}.x86_64.rpm",
                                "props": "rpm.metadata.arch=x86_64;rpm.metadata.vendor=zextras;vcs.revision=${env.GIT_COMMIT}"
                            },
                            {
                                "pattern": "artifacts/x86_64/(carbonio-files-ce)-(*).x86_64.rpm",
                                "target": "rhel9-devel/zextras/{1}/{1}-{2}.x86_64.rpm",
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
                unstash 'artifacts-deb'
                script {
                    def server = Artifactory.server 'zextras-artifactory'
                    def buildInfo
                    def uploadSpec

                    buildInfo = Artifactory.newBuildInfo()
                    uploadSpec = """{
                        "files": [
                            {
                                "pattern": "artifacts/carbonio-files*.deb",
                                "target": "ubuntu-playground/pool/",
                                "props": "deb.distribution=focal;deb.distribution=jammy;deb.component=main;deb.architecture=amd64;vcs.revision=${env.GIT_COMMIT}"
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
                unstash 'artifacts-deb'
                unstash 'artifacts-rpm'
                script {
                    def server = Artifactory.server 'zextras-artifactory'
                    def buildInfo
                    def uploadSpec

                    buildInfo = Artifactory.newBuildInfo()
                    uploadSpec = """{
                        "files": [
                            {
                                "pattern": "artifacts/carbonio-files*.deb",
                                "target": "ubuntu-''' + params.SUFFIX_CUSTOM_REPOS + '''/pool/",
                                "props": "deb.distribution=bionic;deb.distribution=focal;deb.component=main;deb.architecture=amd64;vcs.revision=${env.GIT_COMMIT}"
                            },
                            {
                                "pattern": "artifacts/x86_64/(carbonio-files-ce)-(*).x86_64.rpm",
                                "target": "centos8-''' + params.SUFFIX_CUSTOM_REPOS + '''/zextras/{1}/{1}-{2}.x86_64.rpm",
                                "props": "rpm.metadata.arch=x86_64;rpm.metadata.vendor=zextras;vcs.revision=${env.GIT_COMMIT}"
                            },
                            {
                                "pattern": "artifacts/x86_64/(carbonio-files-ce)-(*).x86_64.rpm",
                                "target": "rhel9-''' + params.SUFFIX_CUSTOM_REPOS + '''/zextras/{1}/{1}-{2}.x86_64.rpm",
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
                unstash 'artifacts-deb'
                unstash 'artifacts-rpm'
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
                                "pattern": "artifacts/carbonio-files*.deb",
                                "target": "ubuntu-rc/pool/",
                                "props": "deb.distribution=focal;deb.distribution=jammy;deb.component=main;deb.architecture=amd64;vcs.revision=${env.GIT_COMMIT}"
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
                                "pattern": "artifacts/x86_64/(carbonio-files-ce)-(*).x86_64.rpm",
                                "target": "centos8-rc/zextras/{1}/{1}-{2}.x86_64.rpm",
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
                                "pattern": "artifacts/x86_64/(carbonio-files-ce)-(*).x86_64.rpm",
                                "target": "rhel9-rc/zextras/{1}/{1}-{2}.x86_64.rpm",
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
    }
}
