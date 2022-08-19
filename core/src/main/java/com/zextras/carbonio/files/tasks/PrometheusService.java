package com.zextras.carbonio.files.tasks;

import com.google.inject.Singleton;
import io.micrometer.prometheus.PrometheusConfig;
import io.micrometer.prometheus.PrometheusMeterRegistry;

@Singleton
public class PrometheusService {

  private static final PrometheusMeterRegistry prometheusRegistry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);

  public PrometheusMeterRegistry getRegistry(){
    return prometheusRegistry;
  }
}
