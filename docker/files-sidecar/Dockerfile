# apt-installing a JRE in the final stage needs QEMU on arm64; copy temurin's instead.
ARG SIDECAR_IMAGE=registry.dev.zextras.com/dev/carbonio-sidecar:main
FROM docker.io/library/eclipse-temurin:21-jre-jammy AS jre

FROM --platform=$BUILDPLATFORM docker.io/library/alpine:3.23 AS consul-template
ARG TARGETARCH
ARG CONSUL_TEMPLATE_VERSION=0.42.1
RUN apk add --no-cache curl unzip && \
    curl -fsSL -o /tmp/ct.zip "https://releases.hashicorp.com/consul-template/${CONSUL_TEMPLATE_VERSION}/consul-template_${CONSUL_TEMPLATE_VERSION}_linux_${TARGETARCH}.zip" && \
    curl -fsSL -o /tmp/ct.sums "https://releases.hashicorp.com/consul-template/${CONSUL_TEMPLATE_VERSION}/consul-template_${CONSUL_TEMPLATE_VERSION}_SHA256SUMS" && \
    echo "$(grep "_linux_${TARGETARCH}.zip" /tmp/ct.sums | cut -d' ' -f1)  /tmp/ct.zip" | sha256sum -c - && \
    unzip -j /tmp/ct.zip consul-template -d /out && \
    chmod +x /out/consul-template

FROM ${SIDECAR_IMAGE}

COPY --from=jre /opt/java/openjdk /opt/java/openjdk
ENV JAVA_HOME=/opt/java/openjdk
ENV PATH="/opt/java/openjdk/bin:${PATH}"

# Service registration
COPY --from=consul-template /out/consul-template /usr/local/bin/consul-template
COPY package/carbonio-files-template.hcl /consul/templates/carbonio-files-template.hcl

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

COPY --chmod=755 docker/files-sidecar/carbonio-files-hcl-entrypoint /usr/local/bin/carbonio-files-hcl-entrypoint
ENTRYPOINT ["carbonio-files-hcl-entrypoint"]
