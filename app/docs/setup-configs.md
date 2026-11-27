# Setup Configuration

## Sidecar Config

Overridable by `/etc/zextras/service-discover/carbonio-files.env`, one `KEY=value` per line.
The service-discover hcl is generated from a packaged template on every upgrade and by `carbonio-files-setup`, which applies the changes: do not edit the hcl by hand.

| Key | Default | Description |
| --- | ------- | ----------- |
| `CHECK_TIMEOUT` | `1s` | Timeout of the Consul health check |
| `CHECK_INTERVAL` | `5s` | Interval between two Consul health checks |
