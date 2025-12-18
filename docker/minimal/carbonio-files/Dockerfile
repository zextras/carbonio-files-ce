FROM eclipse-temurin:21-jdk-alpine

WORKDIR /app

RUN mkdir -p /etc/carbonio/files

COPY ../../boot/target/carbonio-files-*-jar-with-dependencies.jar .

COPY docker/minimal/carbonio-files/entrypoint.sh entrypoint.sh

ENTRYPOINT ["/app/entrypoint.sh"]