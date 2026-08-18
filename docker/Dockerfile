# SPDX-FileCopyrightText: 2022 Zextras <https://www.zextras.com>
#
# SPDX-License-Identifier: AGPL-3.0-only

# JVM uber-jar image (NON-native for now — mirrors carbonio-user-management). The prep stage runs
# on BUILDPLATFORM so the final (target-arch) stage needs zero RUN — a RUN in the TARGETPLATFORM
# stage would require QEMU and fail (exec format error) on arm64 cross-builds. Quarkus maps
# NETWORKING_CONFIG_CARBONIO_* / QUARKUS_* environment variables straight onto its config at
# runtime, so no env->config.properties entrypoint is needed.
FROM --platform=$BUILDPLATFORM docker.io/library/eclipse-temurin:21-jdk-alpine AS prep
RUN mkdir -p /out/etc/carbonio/files

FROM docker.io/library/eclipse-temurin:21-jdk-alpine

LABEL maintainer="Zextras <https://www.zextras.com>"
LABEL description="Carbonio Files CE - File management service"

WORKDIR /app

# Empty runtime config dir, created QEMU-free above.
COPY --from=prep /out/etc /etc

# dt3 stages app/target/*-runner.jar (the Quarkus uber-jar).
COPY app/target/*-runner.jar carbonio-files-ce.jar

EXPOSE 10000

# --enable-preview + --enable-native-access: required by the bootstrap sd_notify (Java 21 FFM).
ENTRYPOINT ["java", "--enable-preview", "--enable-native-access=ALL-UNNAMED", "-Djava.net.preferIPv4Stack=true", "-jar", "/app/carbonio-files-ce.jar"]
