// SPDX-FileCopyrightText: 2022 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

library(
    identifier: 'jenkins-dt3-lib@v1.2.0',
    retriever: modernSCM([
        $class: 'GitSCMSource',
        remote: 'git@github.com:zextras/jenkins-dt3-lib.git',
        credentialsId: 'jenkins-integration-with-github-account'
    ])
)

library(
    identifier: 'jenkins-lib-common@feat/add-maven',
    retriever: modernSCM([
        $class: 'GitSCMSource',
        credentialsId: 'jenkins-integration-with-github-account',
        remote: 'git@github.com:zextras/jenkins-lib-common.git'
    ])
)

properties(defaultPipelineProperties())

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

    options {
        buildDiscarder(logRotator(numToKeepStr: '25'))
        skipDefaultCheckout()
        timeout(time: 2, unit: 'HOURS')
    }

    parameters {
        booleanParam(
            name: 'PREPARE_RELEASE',
            defaultValue: false,
            description: 'Check this to prepare a new release (creates pre-release branch and PR)'
        )
        booleanParam(
            name: 'SKIP_TESTS',
            defaultValue: false,
            description: 'Skip unit tests and integration tests'
        )
        booleanParam(
            name: 'SKIP_CHECKS',
            defaultValue: false,
            description: 'Skip coverage and SonarQube analysis'
        )
    }

    stages {
        stage('Setup') {
            steps {
                checkout scm
                script {
                    gitMetadata()
                }
            }
        }

        stage('Maven') {
            steps {
                script {
                    mavenStage(
                        skipTests: params.SKIP_TESTS,
                        skipCoverage: params.SKIP_CHECKS,
                        skipSonar: params.SKIP_CHECKS,
                        skipDeploy: true,
                        overrideTestCmd: 'mvn -B verify -P run-unit-tests && mvn -B verify -P run-integration-tests',
                        postBuildScript: '''
                            cp -a boot/target/carbonio-files-*-jar-with-dependencies.jar package/carbonio-files.jar
                            cp -a package/watches/* package/
                        '''
                    )
                }
            }
        }

        stage('Build deb/rpm') {
            steps {
                script {
                    buildPackages([
                        pkgbuildPath: 'package/PKGBUILD',
                        buildStageConfig: [:]
                    ])
                }
            }
        }

        stage('Upload artifacts') {
            tools {
                jfrog 'jfrog-cli'
            }
            steps {
                uploadStage(
                    packages: yapHelper.resolvePackageNames()
                )
            }
        }

        stage('Prepare Release') {
            agent {
                node {
                    label 'nodejs-v1'
                }
            }
            when {
                allOf {
                    branch 'devel'
                    expression { params.PREPARE_RELEASE == true }
                    not {
                        expression {
                            return env.GIT_COMMIT_MSG.contains('[skip ci]') ||
                                   env.GIT_COMMIT_MSG.contains('chore(release):')
                        }
                    }
                }
            }
            steps {
                script {
                    container('nodejs-20') {
                        prepareRelease(
                            repoName: 'carbonio-files-ce'
                        )
                    }
                }
            }
        }

        stage('Tag for release') {
            when {
                allOf {
                    branch 'devel'
                    expression {
                        return env.GIT_COMMIT_MSG.contains('chore(release):') &&
                               env.GIT_COMMIT_MSG.contains('[skip ci]')
                    }
                }
            }
            steps {
                script {
                    tagRelease()
                }
            }
        }

        stage('Build and Publish Docker Image') {
            when {
                not {
                    expression { env.BRANCH_NAME.startsWith('PR-') }
                }
            }
            steps {
                buildAndPublishDockerImage(
                    projectName: 'carbonio-files-ce',
                    dockerfile: 'docker/minimal/carbonio-files/Dockerfile',
                    imageTitle: 'Carbonio Files CE',
                    imageDescription: 'Carbonio Files Community Edition'
                )
            }
        }
    }
}