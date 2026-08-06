#!/bin/bash
# SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
#
# SPDX-License-Identifier: AGPL-3.0-only
#
# Warns about long // comment runs in Java. Always exits 0 — it never blocks a commit.
# No AST needed: Java documentation is /** */, so a // run is a non-documentation comment by construction.

set -uo pipefail

MAX_LINES=3

findings=0
for file in "$@"; do
    [ -f "$file" ] || continue
    # One awk per file so a run ending at EOF is reported against the right filename.
    hits=$(awk -v max="$MAX_LINES" -v name="$file" '
        /^[[:space:]]*\/\// { if (run == 0) start = FNR; run++; next }
        { report(); run = 0 }
        END { report() }
        function report() {
            if (run > max) printf "  %s:%d  %d-line // comment\n", name, start, run
        }
    ' "$file")
    if [ -n "$hits" ]; then
        printf '%s\n' "$hits"
        findings=$((findings + $(printf '%s\n' "$hits" | wc -l)))
    fi
done

if [ "$findings" -gt 0 ]; then
    echo "WARN ${findings} long non-documentation comment(s), max ${MAX_LINES} lines."
    echo "     Shorten them, or use /* */ (Javadoc /** */ is never counted)."
fi

exit 0
