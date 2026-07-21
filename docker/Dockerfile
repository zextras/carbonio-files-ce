# SPDX-FileCopyrightText: 2022 Zextras <https://www.zextras.com>
#
# SPDX-License-Identifier: AGPL-3.0-only

# Single-stage-per-arch native image built multi-arch (linux/amd64 + linux/arm64, see Jenkinsfile).
# The prep stage runs on BUILDPLATFORM so the final (target-arch) stage needs zero RUN — a RUN in
# the TARGETPLATFORM stage would require QEMU and fail (exec format error) on arm64 cross-builds.
# The previous `FROM --platform=linux/amd64 ubuntu:noble` pinned the base to amd64, so the arm64
# image would have shipped an amd64 base; mirrors docker/Dockerfile in carbonio-files (Advanced).
FROM --platform=$BUILDPLATFORM docker.io/library/ubuntu:noble AS prep
RUN mkdir -p /etc/carbonio/files

FROM docker.io/library/ubuntu:noble

LABEL maintainer="Zextras <https://www.zextras.com>"
LABEL description="Carbonio Files CE - File management service (native binary)"

WORKDIR /app

# Empty runtime config dir, created QEMU-free above.
COPY --from=prep /etc/carbonio/files /etc/carbonio/files

# dt3 stages app/target/*-runner; --chmod makes it executable without a target-arch RUN.
COPY --chmod=755 app/target/*-runner /app/carbonio-files-ce-runner

EXPOSE 10000

ENTRYPOINT ["/app/carbonio-files-ce-runner", "-Djava.net.preferIPv4Stack=true"]
