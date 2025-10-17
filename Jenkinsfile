// SPDX-FileCopyrightText: 2022 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

library(
    identifier: 'jenkins-packages-build-library@1.0.4',
    retriever: modernSCM([
        $class: 'GitSCMSource',
        remote: 'git@github.com:zextras/jenkins-packages-build-library.git',
        credentialsId: 'jenkins-integration-with-github-account'
    ])
)

pipeline {
    agent {
        node {
            label 'zextras-v1'
        }
    }

    environment {
        JAVA_OPTS = '-Dfile.encoding=UTF8'
        LC_ALL = 'C.UTF-8'
        MVN_OPTS = '-B'
    }

    options {
        buildDiscarder(logRotator(numToKeepStr: '25'))
        timeout(time: 2, unit: 'HOURS')
    }

    parameters {
        booleanParam defaultValue: false,
            description: 'Whether to upload the packages in playground repositories',
            name: 'PLAYGROUND'
        booleanParam defaultValue: false,
            description: 'Run dependencyCheck',
            name: 'RUN_DEPENDENCY_CHECK'
    }

    tools {
        jfrog 'jfrog-cli'
    }

    stages {
        stage('Checkout') {
            steps {
                checkout scm
                script {
                    gitMetadata()
                }
            }
        }

        stage('Check SNAPSHOT version') {
            when {
                expression { env.BRANCH_NAME.contains('PR') }
            }
            steps {
                script {
                    String commentMessage = ''
                    String commitTitle = env.CHANGE_TITLE ? env.CHANGE_TITLE : ''
                    if (!commitTitle.contains('chore(release)') && !readFile('package/PKGBUILD').trim().contains('SNAPSHOT')) {
                        commentMessage = 'Please increase the micro version in the `pkgver` and add a **SNAPSHOT** label to the `pkgrel`.'
                    }

                    if (commitTitle.contains('chore(release)') && readFile('package/PKGBUILD').trim().contains('SNAPSHOT')) {
                        commentMessage = 'Please remove the **SNAPSHOT** label to the `pkgrel`.'
                    }

                    if (commentMessage) {
                        withCredentials([usernamePassword(credentialsId: 'tarsier-bot-pr-token-github', usernameVariable: 'GH_USERNAME', passwordVariable: 'GH_TOKEN')]) {
                            sh(script: """
                              curl https://api.github.com/repos/zextras/carbonio-files/issues/${env.CHANGE_ID}/comments \
                              -X POST \
                              -H 'Accept: application/vnd.github.v3+json' \
                              -H 'Authorization: token ${GH_TOKEN}' \
                              -d '{
                                  \"body\": \"${commentMessage}\\nMake sure to update the `PKGBUILD` and all `pom.xml` files accordingly.\"
                              }'
                          """)
                        }
                        error('The development package version is not marked as SNAPSHOT')
                    }
                }
            }
        }

        stage('Build jar') {
            steps {
                script {
                    def profile = '-P dev'
                    if (env.TAG_NAME) {
                        profile = '-P prod'
                    }
                    container('jdk-17') {
                        sh """
                            mvn ${MVN_OPTS} clean package ${profile}
                            cp -a boot/target/carbonio-files-*-jar-with-dependencies.jar package/carbonio-files.jar
                            cp -a package/watches/* package/
                        """
                    }
                }
            }
        }

        stage('UTs') {
            steps {
                container('jdk-17') {
                    sh "mvn ${MVN_OPTS} verify -P run-unit-tests"
                }
            }
        }

        stage('ITs') {
            steps {
                container('jdk-17') {
                    sh "mvn ${MVN_OPTS} verify -P run-integration-tests"
                }
            }
        }

        stage('Coverage') {
            steps {
                container('jdk-17') {
                    sh "mvn ${MVN_OPTS} verify -P generate-jacoco-full-report"
                    recordCoverage(tools: [[parser: 'JACOCO']], sourceCodeRetention: 'MODIFIED')
                }
            }
        }

        stage('SonarQube analysis') {
            steps {
                container('jdk-17') {
                    withSonarQubeEnv(credentialsId: 'sonarqube-user-token', installationName: 'SonarQube instance') {
                        sh "mvn ${MVN_OPTS} sonar:sonar"
                    }
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
                container('dind') {
                    withDockerRegistry(credentialsId: 'private-registry', url: 'https://registry.dev.zextras.com') {
                        script {
                            String branchTag = env.BRANCH_NAME.replaceAll('/', '-').toLowerCase()
                            Set<String> imageTags = [ branchTag ]

                            if (env.BRANCH_NAME == 'develop') {
                                imageTags.add('latest')
                            } else if (buildingTag() && env.TAG_NAME?.trim()) {
                                imageTags.add(env.TAG_NAME?.startsWith('v') ? env.TAG_NAME.substring(1) : env.TAG_NAME)
                            }

                            dockerHelper.buildImage([
                                imageName: 'registry.dev.zextras.com/dev/carbonio-files',
                                imageTags: imageTags,
                                dockerfile: 'docker/minimal/carbonio-files/Dockerfile',
                                ocLabels: [
                                    title: 'Carbonio Files CE',
                                    description: 'Carbonio Files Community Edition',
                                    version: branchTag
                                ]
                            ])
                        }
                    }
                }
            }
        }

        stage('Build deb/rpm') {
            steps {
                echo 'Building deb/rpm packages'
                buildStage()
            }
        }

        stage('Upload artifacts') {
            steps {
                uploadStage(
                    packages: yapHelper.getPackageNames()
                )
            }
        }
    }
}
