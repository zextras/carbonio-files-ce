# Run files locally with Docker

This minimal setup includes all necessary dependencies without mocks (with Consul + Storages being the only exceptions).

Steps:
    1. `mvn clean install -DskipTests=true`
    2. `cd docker/minimal`
    3. `docker compose up --build`
    4. Browse Carbonio on `http://docker.carbonio.localhost`, backends are exposed on various ports (see docker-compose.yaml), debug on 5050
    5. Login using `user@carbonio.localhost`/`assext`

Possible configs for files:
  - CARBONIO_FILES_HOST
  - CARBONIO_FILES_PORT
  - CARBONIO_DOCS_CONNECTOR_HOST
  - CARBONIO_DOCS_CONNECTOR_PORT
  - CARBONIO_MAILBOX_HOST
  - CARBONIO_MAILBOX_PORT
  - CARBONIO_MESSAGE_BROKER_HOST
  - CARBONIO_MESSAGE_BROKER_PORT
  - CARBONIO_POSTGRESQL_HOST
  - CARBONIO_POSTGRESQL_PORT
  - CARBONIO_PREVIEW_HOST
  - CARBONIO_PREVIEW_PORT
  - CARBONIO_STORAGES_HOST
  - CARBONIO_STORAGES_PORT
  - CARBONIO_USER_MANAGEMENT_HOST
  - CARBONIO_USER_MANAGEMENT_PORT