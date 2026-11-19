#!/usr/bin/env bash
# SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
#
# SPDX-License-Identifier: AGPL-3.0-only
set -euo pipefail

version="${1:?usage: stamp-api-converters.sh <version>}"
if [[ ! "${version}" =~ ^[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z.-]+)?$ ]]; then
  echo "stamp-api-converters: invalid version '${version}'" >&2
  exit 2
fi

root="$(git rev-parse --show-toplevel)"
cd "${root}"

pattern='(^|[^A-Za-z0-9_$])since[[:space:]]*=[[:space:]]*(([A-Za-z_][A-Za-z0-9_]*[[:space:]]*[.][[:space:]]*)*ApiVersion[[:space:]]*[.][[:space:]]*)?CURRENT([^A-Za-z0-9_$]|$)'
subst='s/(^|[^A-Za-z0-9_$])since[[:space:]]*=[[:space:]]*(([A-Za-z_][A-Za-z0-9_]*[[:space:]]*[.][[:space:]]*)*ApiVersion[[:space:]]*[.][[:space:]]*)?CURRENT([^A-Za-z0-9_$]|$)/\1since = "'"${version}"'"\4/g'

changed=0
while IFS= read -r -d '' file; do
  if ! sed -z -E -n "/${pattern}/q1" "${file}"; then
    sed -z -E -i "${subst}" "${file}"
    echo "stamped ${file} -> since = \"${version}\""
    changed=$((changed + 1))
  fi
done < <(git ls-files -z --cached --others --exclude-standard -- 'app/src/main/java/*.java')

echo "stamp-api-converters: ${changed} file(s) stamped with ${version}"
