FROM openjdk:17-jdk-slim

WORKDIR /app

CMD sh -c 'JAR=$(ls carbonio-files-ce-*-jar-with-dependencies.jar | head -n 1) && \
           java -Djava.net.preferIPv4Stack=true \
                -Xms1024m \
                -Xmx2048m \
                -DTASKS_LOG_LEVEL=warn \
                -Ddb.postgresql.url=localhost \
                -Ddb.postgresql.port=5432 \
                -jar "$JAR"'
