#!/bin/bash

# SPDX-FileCopyrightText: 2024 Zextras <https://www.zextras.com>
#
# SPDX-License-Identifier: AGPL-3.0-only

read INPUT

KEY=$(echo $INPUT | jq -r '.Key')
VALUE=$(echo $INPUT | jq -r '.Value' | base64 --decode)

echo "La chiave $KEY è stata modificata. Il nuovo valore è $VALUE."