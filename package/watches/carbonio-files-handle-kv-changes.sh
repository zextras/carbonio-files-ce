#!/bin/bash

# SPDX-FileCopyrightText: 2024 Zextras <https://www.zextras.com>
#
# SPDX-License-Identifier: AGPL-3.0-only

read INPUT

KEY=$(echo $INPUT | jq -r '.Key')
VALUE=$(echo $INPUT | jq -r '.Value' | base64 --decode)

echo "La chiave $KEY è stata modificata. Il nuovo valore è $VALUE."

USERNAME=$(consul kv get -token-file=/etc/carbonio/files/service-discover/token carbonio-message-bro>
PASSWORD=$(consul kv get -token-file=/etc/carbonio/files/service-discover/token carbonio-message-bro>

BODY=$(jq -n --arg key "$KEY" --arg value "$VALUE" '{($key): $value}')
echo "$BODY"

curl --http0.9 -u $USERNAME:$PASSWORD -X POST -H "Content-Type: application/json" \
-d "$BODY" http://127.78.0.2:20006/api/exchanges/%2f/TEST/publish \
-d '{
    "properties": {
        "delivery_mode": 2
    },
    "routing_key": "",
    "payload": "'"$BODY"'",
    "payload_encoding": "string"
}'