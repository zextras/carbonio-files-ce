# Prep stage runs on BUILDPLATFORM (amd64 CI builder) — no QEMU. Creates the
# arch-independent config dir so the final image needs no RUN step.
FROM --platform=$BUILDPLATFORM busybox AS prep
RUN mkdir -p /staging/etc/carbonio/files

FROM eclipse-temurin:21.0.11_10-jdk-alpine

WORKDIR /app

COPY --from=prep /staging/etc /etc

COPY ../../boot/target/carbonio-files-*-jar-with-dependencies.jar .

COPY docker/minimal/carbonio-files/entrypoint.sh entrypoint.sh

ENTRYPOINT ["/app/entrypoint.sh"]