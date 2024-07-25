#!/bin/bash

# SPDX-FileCopyrightText: 2024 Zextras <https://www.zextras.com>
#
# SPDX-License-Identifier: AGPL-3.0-only

# python version
py_ver=pythonPYTHON_VER
py_prefix=PREFIX

# set proper modules path
PYTHONPATH="/opt/zextras/common/lib/${py_ver}/dist-packages:"
PYTHONPATH+="/opt/zextras/common/lib/${py_ver}/site-packages:"
PYTHONPATH+="/opt/zextras/common/lib64/${py_ver}/dist-packages:"
PYTHONPATH+="/opt/zextras/common/lib64/${py_ver}/site-packages:"
PYTHONPATH+="/opt/zextras/common/local/lib/${py_ver}/dist-packages:"
PYTHONPATH+="/opt/zextras/common/local/lib/${py_ver}/site-packages:"
PYTHONPATH+="/opt/zextras/common/local/lib64/${py_ver}/dist-packages:"
PYTHONPATH+="/opt/zextras/common/local/lib64/${py_ver}/site-packages:"

export PYTHONPATH

# Here we start all watchers instead of having a systemd service for each kv
consul watch -type=key -key=carbonio-files/max-number-of-versions -token-file=/etc/carbonio/files/service-discover/token "python3 /usr/bin/carbonio-files-handle-kv-changes.py"