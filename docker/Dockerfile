# Prep stage runs on BUILDPLATFORM (amd64 CI builder) — no QEMU. Creates the
# arch-independent config dir so the final image needs no RUN step.
FROM --platform=$BUILDPLATFORM docker.io/library/busybox AS prep
RUN mkdir -p /staging/etc/carbonio/files

FROM docker.io/library/eclipse-temurin:21-jdk-alpine

WORKDIR /app

COPY --from=prep /staging/etc /etc

COPY boot/target/carbonio-files-*-jar-with-dependencies.jar .

COPY docker/entrypoint.sh entrypoint.sh

ENTRYPOINT ["/app/entrypoint.sh"]