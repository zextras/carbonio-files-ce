# Default Configuration

## Networking Config

Overridable by `/etc/carbonio/files/config.properties`

| Key | Default |
| --- | ------- |
| `carbonio.files.enable-notifications` | `true` |
| `carbonio.postgresql.host` | `127.78.0.2` |
| `carbonio.postgresql.port` | `20000` |
| `carbonio.service-discover.host` | `127.0.0.1` |
| `carbonio.service-discover.port` | `8500` |
| `carbonio.service.host` | `127.78.0.2` |
| `carbonio.service.port` | `10000` |
| `carbonio.storages.host` | `127.78.0.2` |
| `carbonio.storages.port` | `20002` |

## Application Config

Overridable by Consul KV

| Key | Default | If not set |
| --- | ------- | ---------- |
| `carbonio-files/database/credentials/db-name` | *(not set)* | Crashes; but always set by database bootstrap |
| `carbonio-files/database/credentials/db-password` | *(not set)* | Crashes; but always set by database bootstrap |
| `carbonio-files/database/credentials/db-username` | *(not set)* | Crashes; but always set by database bootstrap |
| `carbonio-files/database/db-pool-idle-timeout` | *(not set)* | Quarkus default: 5 minutes |
| `carbonio-files/database/db-pool-leak-detection` | *(not set)* | Quarkus default: disabled |
| `carbonio-files/database/db-pool-max-lifetime` | *(not set)* | Quarkus default: no limit |
| `carbonio-files/database/db-pool-max-size` | *(not set)* | Quarkus default: 20 |
| `carbonio-files/database/db-pool-min-size` | *(not set)* | Quarkus default: 0 |
| `carbonio-files/max-downloadable-size-in-mb` | *(not set)* | no limit |
| `carbonio-files/max-number-of-versions` | `30` |  |
| `carbonio-files/max-uploadable-size-in-mb` | *(not set)* | no limit |
| `carbonio-files/server/idle-timeout` | *(not set)* | Quarkus default: 30s |
| `carbonio-files/server/max-connections` | *(not set)* | Quarkus default: no limit |
| `carbonio-files/server/max-threads` | *(not set)* | Quarkus default: 200 |
| `carbonio-files/server/queue-size` | *(not set)* | Quarkus default: unbounded |

