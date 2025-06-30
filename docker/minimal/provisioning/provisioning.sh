#!/bin/sh
#
# SPDX-FileCopyrightText: 2025 Zextras <https://www.zextras.com>
#
# SPDX-License-Identifier: AGPL-3.0-only
#

echo "Provisioning containers"

wait_for_mailbox() {
    local max_attempts=60
    local attempt=1

    echo "Waiting for mailbox to be ready..."

    while [ $attempt -le $max_attempts ]; do
        echo "Attempt $attempt/$max_attempts..."

        OUTPUT=$(docker exec minimal-mailbox-1 sh -c "echo 'cd demo.zextras.io' | zmprov 2>&1")

        if echo "$OUTPUT" | grep -q "DOMAIN_EXISTS"; then
            echo "Mailbox is ready! Domain already exists."
            return 0
        fi

        if [ $? -eq 0 ] && ! echo "$OUTPUT" | grep -q "ERROR"; then
            echo "Mailbox is ready! Domain does not exist yet."
            return 0
        fi

        echo "Mailbox not ready yet, waiting 5 seconds..."
        sleep 5
        attempt=$((attempt + 1))
    done

    echo "Timeout: Mailbox did not become ready in time"
    return 1
}

if ! wait_for_mailbox; then
    echo "Failed to provision: mailbox not ready"
    exit 1
fi

echo "Executing provisioning commands..."
docker exec minimal-mailbox-1 sh -c "> /tmp/prov.ls && cat > /tmp/prov.ls <<EOF
cd demo.zextras.io
ca test@demo.zextras.io password
ca admin@demo.zextras.io password zimbraIsAdminAccount TRUE
EOF
zmprov < /tmp/prov.ls"

if [ $? -eq 0 ]; then
    echo "Provisioning completed successfully"
else
    echo "Note: Some accounts may already exist, which is normal"
fi