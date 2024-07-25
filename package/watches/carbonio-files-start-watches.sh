#!/bin/bash

# SPDX-FileCopyrightText: 2024 Zextras <https://www.zextras.com>
#
# SPDX-License-Identifier: AGPL-3.0-only

# Here we start all watchers instead of having a systemd service for each kv
consul watch -type=key -key=carbonio-files/max-number-of-versions -token-file=/etc/carbonio/files/service-discover/token "python3 /usr/bin/carbonio-files-handle-kv-changes.py"