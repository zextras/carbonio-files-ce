services {
  check {
    http = "http://127.78.0.2:10000/health/",
    method = "GET",
    timeout = "1s"
    interval = "5s"
  }
  connect {
    sidecar_service {
      proxy {
        local_service_address = "127.78.0.2"
        upstreams = [
          {
            destination_name = "carbonio-files-db"
            local_bind_address = "127.78.0.2"
            local_bind_port = 20000
          },
          {
            destination_name = "carbonio-user-management"
            local_bind_address = "127.78.0.2"
            local_bind_port = 20001
          },
          {
            destination_name = "carbonio-storages"
            local_bind_address = "127.78.0.2"
            local_bind_port = 20002
          },
          {
            destination_name = "carbonio-preview"
            local_bind_address = "127.78.0.2"
            local_bind_port = 20003
          },
          {
            destination_name = "carbonio-mailbox"
            local_bind_address = "127.78.0.2"
            local_bind_port = 20004
          }
        ]
      }
    }
  }
  name = "carbonio-files"
  port = 10000
}