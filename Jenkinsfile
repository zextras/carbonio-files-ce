// SPDX-FileCopyrightText: 2022 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

library(
    identifier: 'jenkins-lib-common@v5.4.0',
    retriever: modernSCM([
        $class: 'GitSCMSource',
        credentialsId: 'jenkins-integration-with-github-account',
        remote: 'git@github.com:zextras/jenkins-lib-common.git',
    ])
)

properties(defaultPipelineProperties())

dt3_pipeline(
    repoName: 'carbonio-files-ce',
    projectType: 'CE',
    appModule: 'boot',
    packaging: [
        addCarbonioRepos: true,
        preBuildScript: '''
            cp -a boot/target/carbonio-files-*-jar-with-dependencies.jar package/carbonio-files.jar
            cp -a package/watches/* package/
        ''',
    ],
)
