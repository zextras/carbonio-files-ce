# Default Configuration

## Networking Config

Overridable by `/etc/carbonio/files/config.properties`

| Key | Default | Description |
| --- | ------- | ----------- |
| `carbonio.files.enable-notifications` | `true` | Enables new-share/added-node/removed-node notifications |
| `carbonio.postgresql.host` | `127.78.0.2` |  |
| `carbonio.postgresql.port` | `20000` |  |
| `carbonio.service-discover.host` | `127.0.0.1` |  |
| `carbonio.service-discover.port` | `8500` |  |
| `carbonio.service.host` | `127.78.0.2` |  |
| `carbonio.service.port` | `10000` |  |
| `carbonio.storages.host` | `127.78.0.2` | Host of the carbonio-storages upstream (blob store) |
| `carbonio.storages.port` | `20002` | Port of the carbonio-storages upstream (blob store) |

## Application Config

Overridable by Consul KV

| Key | Default | If not set | Description |
| --- | ------- | ---------- | ----------- |
| `carbonio-files/database/credentials/db-name` | *(not set)* | Crashes; but always set by database bootstrap |  |
| `carbonio-files/database/credentials/db-password` | *(not set)* | Crashes; but always set by database bootstrap |  |
| `carbonio-files/database/credentials/db-username` | *(not set)* | Crashes; but always set by database bootstrap |  |
| `carbonio-files/database/db-pool-foreground-validation` | *(not set)* | 500ms (foreground validation enabled by default) | Idle time in milliseconds after which a connection is validated when borrowed |
| `carbonio-files/database/db-pool-idle-timeout` | *(not set)* | Quarkus default: 5 minutes | Interval at which idle pooled connections are removed, in milliseconds |
| `carbonio-files/database/db-pool-leak-detection` | *(not set)* | Quarkus default: disabled | Interval after which a connection held too long is reported as leaked, in milliseconds |
| `carbonio-files/database/db-pool-max-lifetime` | *(not set)* | Quarkus default: no limit | Max lifetime of a pooled connection, in milliseconds |
| `carbonio-files/database/db-pool-max-size` | *(not set)* | Quarkus default: 50 | Max connections in the database pool |
| `carbonio-files/database/db-pool-min-size` | *(not set)* | Quarkus default: 0 | Min connections kept open in the database pool |
| `carbonio-files/max-downloadable-size-in-mb` | *(not set)* | no limit | Max downloadable file size in MB |
| `carbonio-files/max-number-of-versions` | `30` |  | Max number of kept versions for a file (see also max-number-of-keep-versions) |
| `carbonio-files/max-uploadable-size-in-mb` | *(not set)* | no limit | Max uploadable file size in MB |
| `carbonio-files/server/idle-timeout` | *(not set)* | Quarkus default: 30 minutes | Idle HTTP connection timeout, in seconds |
| `carbonio-files/server/max-connections` | *(not set)* | Quarkus default: no limit | Max concurrent HTTP connections accepted by the server |
| `carbonio-files/server/max-threads` | *(not set)* | Quarkus default: max(8 x CPUs, 200) | Max threads of the Quarkus worker pool that runs blocking HTTP endpoints and GraphQL; service-specific pools are not affected |
| `carbonio-files/server/queue-size` | *(not set)* | Quarkus default: unbounded | Max requests queued waiting for a Quarkus worker thread; service-specific pools are not affected |
| `carbonio-files/transfer/max-threads` | `64` |  | Threads of the transfer pool. The pool runs the REST byte transfers (upload, upload-version, downloads: single, zip, public, /internal accounts GetFile/PutFile, preview streaming, /upload-to) and the long GraphQL blob mutations (copyNodes, cloneVersion, transferOwnership). One thread per operation for its whole duration; an operation that finds all threads busy waits in the queue. Not the Quarkus worker pool (server/max-threads) |
| `carbonio-files/transfer/queue-size` | `256` |  | Operations that can wait for a free transfer-pool thread (see transfer/max-threads). When threads and queue are both full a new one is answered with 503 |

## Hierarchical Config

Overridable using admin panel

| Key | Default | Description |
| --- | ------- | ----------- |
| `test-key` | `default-value` | Test key of the hierarchical config skeleton (CO-4496), no behaviour |

