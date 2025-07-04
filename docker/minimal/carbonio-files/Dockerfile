FROM openjdk:17-jdk-slim

WORKDIR /app

RUN mkdir -p /etc/carbonio/files

COPY ../../boot/target/carbonio-files-*-jar-with-dependencies.jar .

CMD sh -c 'echo "\n\
# Files Service\n\
carbonio.files.host=${CARBONIO_FILES_HOST}\n\
carbonio.files.port=${CARBONIO_FILES_PORT}\n\
\n\
# Docs Connector Service\n\
carbonio.docs-connector.host=${CARBONIO_DOCS_CONNECTOR_HOST}\n\
carbonio.docs-connector.port=${CARBONIO_DOCS_CONNECTOR_PORT}\n\
\n\
# Mailbox Service\n\
carbonio.mailbox.host=${CARBONIO_MAILBOX_HOST}\n\
carbonio.mailbox.port=${CARBONIO_MAILBOX_PORT}\n\
\n\
# Message Broker Service\n\
carbonio.message-broker.host=${CARBONIO_MESSAGE_BROKER_HOST}\n\
carbonio.message-broker.port=${CARBONIO_MESSAGE_BROKER_PORT}\n\
\n\
# PostgreSQL Database Service\n\
carbonio.postgresql.host=${CARBONIO_POSTGRESQL_HOST}\n\
carbonio.postgresql.port=${CARBONIO_POSTGRESQL_PORT}\n\
\n\
# Preview Service\n\
carbonio.preview.host=${CARBONIO_PREVIEW_HOST}\n\
carbonio.preview.port=${CARBONIO_PREVIEW_PORT}\n\
\n\
# Storages Service\n\
carbonio.storages.host=${CARBONIO_STORAGES_HOST}\n\
carbonio.storages.port=${CARBONIO_STORAGES_PORT}\n\
\n\
# User Management Service\n\
carbonio.user-management.host=${CARBONIO_USER_MANAGEMENT_HOST}\n\
carbonio.user-management.port=${CARBONIO_USER_MANAGEMENT_PORT}" > /etc/carbonio/files/config.properties && \
JAR=$(ls carbonio-files-*-jar-with-dependencies.jar | head -n 1) && \
java -Djava.net.preferIPv4Stack=true \
     -Xms4096m \
     -Xmx4096m \
     -DFILES_LOG_LEVEL=warn \
     -jar "$JAR"'