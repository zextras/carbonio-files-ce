# SPDX-FileCopyrightText: 2025 Zextras <https://www.zextras.com>
#
# SPDX-License-Identifier: AGPL-3.0-only

FROM docker.io/library/alpine:3.22.1 AS prep
WORKDIR /staging
COPY ../../. .

RUN mkdir -p package && \
    cp ./boot/target/carbonio-files-ce-*-jar-with-dependencies.jar package/carbonio-files.jar && \
    cp ./package/watches/* package/ 2>/dev/null

# Stage Ubuntu 20.04 (Focal)
FROM docker.io/m0rf30/yap-ubuntu-focal:1.44 AS ubuntu-focal-builder
WORKDIR /tmp/staging
COPY --from=prep /staging .
RUN yap build ubuntu-focal /tmp/staging/

# Stage Ubuntu 22.04 (Jammy)
FROM docker.io/m0rf30/yap-ubuntu-jammy:1.44 AS ubuntu-jammy-builder
WORKDIR /tmp/staging
COPY --from=prep /staging .
RUN yap build ubuntu-jammy /tmp/staging/

# Stage Ubuntu 24.04 (Noble)
FROM docker.io/m0rf30/yap-ubuntu-noble:1.44 AS ubuntu-noble-builder
WORKDIR /tmp/staging
COPY --from=prep /staging .
RUN yap build ubuntu-noble /tmp/staging/

# Stage RHEL/Rocky 8
FROM docker.io/m0rf30/yap-rocky-8:1.55 AS rocky-8-builder
WORKDIR /tmp/staging
COPY --from=prep /staging .
RUN yap build rocky-8 /tmp/staging/

# Stage RHEL/Rocky 9
FROM docker.io/m0rf30/yap-rocky-9:1.44 AS rocky-9-builder
WORKDIR /tmp/staging
COPY --from=prep /staging .
RUN yap build rocky-9 /tmp/staging/

# Final stage
FROM docker.io/library/alpine:3.22.1 AS final
WORKDIR /output

COPY --from=ubuntu-focal-builder /tmp/staging/artifacts ./ubuntu-focal/
COPY --from=ubuntu-jammy-builder /tmp/staging/artifacts ./ubuntu-jammy/
COPY --from=ubuntu-noble-builder /tmp/staging/artifacts ./ubuntu-noble/
COPY --from=rocky-8-builder /tmp/staging/artifacts ./rocky-8/
COPY --from=rocky-9-builder /tmp/staging/artifacts ./rocky-9/

CMD ["echo", "Packages built successfully."]