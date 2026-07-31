#!/bin/bash
# SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
#
# SPDX-License-Identifier: AGPL-3.0-only
#
# Regenerates THIRDPARTIES. Vendored from jenkins-lib-common's dt3_thirdparties.groovy
# (generateMaven); Jenkins only verifies, by re-running this same generation and diffing.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
cd "${REPO_ROOT}"

LICENSE_MAVEN_PLUGIN='org.codehaus.mojo:license-maven-plugin:2.7.1'
LICENSE_FILE_TEMPLATE='/org/codehaus/mojo/license/third-party-file-groupByLicense.ftl'

# pom.xml defines <revision>/<changelist> defaults, so mvn resolves the version unaided.
PROJECT_GROUP_ID=$(mvn -B -q help:evaluate -Dexpression=project.groupId -DforceStdout)

# Exclude our own groupId: first-party versions change on every release and would drift the manifest.
OWN_GROUP_REGEX="^${PROJECT_GROUP_ID//./\\.}.*"

echo "[thirdparties] excludedGroups=${OWN_GROUP_REGEX}"

# Must match dt3_thirdparties.groovy's generateMaven mvn call byte-for-byte, or verify goes red.
mvn -B \
    "${LICENSE_MAVEN_PLUGIN}:aggregate-add-third-party" \
    -Dlicense.outputDirectory=. \
    -Dlicense.thirdPartyFilename=THIRDPARTIES \
    -Dlicense.force=true \
    -Dlicense.excludedScopes=test,provided \
    -Dlicense.excludedGroups="${OWN_GROUP_REGEX}" \
    -Dlicense.sortArtifactByName=true \
    -Dlicense.includeTransitiveDependencies=true \
    -Dlicense.fileTemplate="${LICENSE_FILE_TEMPLATE}"

echo "[thirdparties] THIRDPARTIES regenerated."
