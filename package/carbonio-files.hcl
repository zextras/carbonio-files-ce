// SPDX-FileCopyrightText: 2022 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

services {
  check {
    http     = "http://127.78.0.2:10000/health/ready/"
    method   = "GET"
    timeout  = "1s"
    interval = "5s"
  }
  meta = {
    prom_port = "21500"
  }
  tags = [
    "prometheus-exporter"
  ]
  connect {
    sidecar_service {
      proxy {
        config {
          local_request_timeout_ms = 3600000
        }
        local_service_address = "127.78.0.2"
        expose {
          paths = [
            {
              path            = "/metrics",
              local_path_port = 10000
              listener_port   = 21500
            }
          ]
        }
        upstreams = [
          {
            destination_name   = "carbonio-files-db"
            local_bind_address = "127.78.0.2"
            local_bind_port    = 20000
          },
          {
            destination_name   = "carbonio-user-management"
            local_bind_address = "127.78.0.2"
            local_bind_port    = 20001
          },
          {
            destination_name   = "carbonio-storages"
            local_bind_address = "127.78.0.2"
            local_bind_port    = 20002
          },
          {
            destination_name   = "carbonio-preview"
            local_bind_address = "127.78.0.2"
            local_bind_port    = 20003
          },
          {
            destination_name   = "carbonio-mailbox"
            local_bind_address = "127.78.0.2"
            local_bind_port    = 20004
          },
          {
            destination_name   = "carbonio-docs-connector"
            local_bind_address = "127.78.0.2"
            local_bind_port    = 20005
          },
          {
            destination_name   = "carbonio-message-broker"
            local_bind_address = "127.78.0.2"
            local_bind_port    = 20006
          }
        ]
      }
    }
  }

  name = "carbonio-files"
  port = 10000
}
