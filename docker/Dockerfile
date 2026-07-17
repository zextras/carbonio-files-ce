# SPDX-FileCopyrightText: 2022 Zextras <https://www.zextras.com>
#
# SPDX-License-Identifier: AGPL-3.0-only

FROM --platform=linux/amd64 docker.io/library/ubuntu:noble

LABEL maintainer="Zextras <https://www.zextras.com>"
LABEL description="Carbonio Files CE - File management service (native binary)"

RUN groupadd -r carbonio-files && useradd -r -g carbonio-files carbonio-files

WORKDIR /app

COPY app/target/*-runner /app/carbonio-files-ce-runner
RUN chmod +x /app/carbonio-files-ce-runner

USER carbonio-files

EXPOSE 10000

ENTRYPOINT ["/app/carbonio-files-ce-runner", "-Djava.net.preferIPv4Stack=true"]
