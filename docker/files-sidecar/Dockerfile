# apt-installing a JRE in the final stage needs QEMU on arm64; copy temurin's instead.
ARG SIDECAR_IMAGE=registry.dev.zextras.com/dev/carbonio-sidecar:main
FROM docker.io/library/eclipse-temurin:21-jre-jammy AS jre

FROM --platform=$BUILDPLATFORM docker.io/library/alpine:3.20 AS hcl
COPY package/carbonio-files-template.hcl /carbonio-files-template.hcl
RUN sed -E 's/\{\{ or \(env "[A-Z0-9_]+"\) "([^"]*)" \}\}/\1/g' \
    /carbonio-files-template.hcl > /carbonio-files.hcl \
    && ! grep -q '{{' /carbonio-files.hcl

FROM ${SIDECAR_IMAGE}

COPY --from=jre /opt/java/openjdk /opt/java/openjdk
ENV JAVA_HOME=/opt/java/openjdk
ENV PATH="/opt/java/openjdk/bin:${PATH}"

# Service registration
COPY --from=hcl /carbonio-files.hcl /consul/config/

# Setup files
COPY package/service-protocol.json /etc/carbonio/files/service-discover/
COPY package/intentions.json       /etc/carbonio/files/service-discover/
COPY package/policies.json         /etc/carbonio/files/service-discover/
COPY package/service-router.json   /etc/carbonio/files/service-discover/

# App jar (used for --setup only)
COPY app/target/*-runner.jar /usr/share/carbonio/carbonio-files-ce.jar

# COPY --chmod avoids a target-arch RUN (QEMU-free)
COPY --chmod=755 docker/files-sidecar/carbonio-files-setup /usr/local/bin/carbonio-files-setup

ENV SERVICE_NAME=carbonio-files
ENV SETUP_SCRIPT="carbonio-files-setup"
ENV TOKEN_FILE=/etc/carbonio/files/service-discover/token
