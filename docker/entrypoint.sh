#!/bin/sh

echo "" > /etc/carbonio/files/config.properties

addEnvToProperties() {
  if [ -n "$2" ];
  then echo "$1=$2" >> /etc/carbonio/files/config.properties;
  else echo "$1 is not set. Skipping it.";
  fi
}

addEnvToProperties "carbonio.files.host" "${CARBONIO_FILES_HOST}"
addEnvToProperties "carbonio.files.port" "${CARBONIO_FILES_PORT}"

# Docs Connector Service
addEnvToProperties "carbonio.docs-connector.host" "${CARBONIO_DOCS_CONNECTOR_HOST}"
addEnvToProperties "carbonio.docs-connector.port" "${CARBONIO_DOCS_CONNECTOR_PORT}"

# Mailbox Service
addEnvToProperties "carbonio.mailbox.host" "${CARBONIO_MAILBOX_HOST}"
addEnvToProperties "carbonio.mailbox.port" "${CARBONIO_MAILBOX_PORT}"

# Message Broker Service
addEnvToProperties "carbonio.message-broker.host" "${CARBONIO_MESSAGE_BROKER_HOST}"
addEnvToProperties "carbonio.message-broker.port" "${CARBONIO_MESSAGE_BROKER_PORT}"

# PostgreSQL Database Service
addEnvToProperties "carbonio.postgresql.host" "${CARBONIO_POSTGRESQL_HOST}"
addEnvToProperties "carbonio.postgresql.port" "${CARBONIO_POSTGRESQL_PORT}"

# Preview Service
addEnvToProperties "carbonio.preview.host" "${CARBONIO_PREVIEW_HOST}"
addEnvToProperties "carbonio.preview.port" "${CARBONIO_PREVIEW_PORT}"

# Storages Service
addEnvToProperties "carbonio.storages.host" "${CARBONIO_STORAGES_HOST}"
addEnvToProperties "carbonio.storages.port" "${CARBONIO_STORAGES_PORT}"

# Service Discover
addEnvToProperties "carbonio.service-discover.host" "${CARBONIO_SERVICE_DISCOVER_HOST}"
addEnvToProperties "carbonio.service-discover.port" "${CARBONIO_SERVICE_DISCOVER_PORT}"

# User Management Service
addEnvToProperties "carbonio.user-management.host" "${CARBONIO_USER_MANAGEMENT_HOST}"
addEnvToProperties "carbonio.user-management.port" "${CARBONIO_USER_MANAGEMENT_PORT}"


JAR=$(find . -maxdepth 1 -name 'carbonio-files-*-jar-with-dependencies.jar' | head -n 1)

exec java -Djava.net.preferIPv4Stack=true \
          -Xms4096m \
          -Xmx4096m \
          -DFILES_LOG_LEVEL=info \
          -agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5050 \
          -jar "$JAR"
