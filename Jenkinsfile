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
    identifier: 'jenkins-lib-common@v2.8.8',
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
            description: 'Check this to prepare a new release (runs semantic-release)'
        )
    }

    stages {
        stage('Setup') {
            steps {
                checkout scm
                script {
                    gitMetadata()
                    semanticRelease.guard()
                }
            }
        }

        stage('Maven') {
            steps {
                script {
                    mavenStage(
                        splitTests: true,
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
                        buildStageConfig: [
                            addCarbonioRepos: true,
                        ]
                    ])
                }
            }
        }

        stage('Upload artifacts') {
            when {
                expression { return uploadStage.shouldUpload() }
            }
            tools {
                jfrog 'jfrog-cli'
            }
            steps {
                uploadStage()
            }
        }

        stage('Prepare Release') {
            when {
                allOf {
                    branch 'devel'
                    expression { params.PREPARE_RELEASE == true }
                }
            }
            steps {
                script {
                    semanticRelease()
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
                dockerStage(
                    imageName: 'carbonio-files-ce',
                    dockerfile: 'docker/minimal/carbonio-files/Dockerfile',
                    ocLabels: [
                        title: 'Carbonio Files CE',
                        description: 'Carbonio Files Community Edition'
                    ]
                )
            }
        }
    }
}
