package com.zextras.carbonio.files.tasks;

import com.google.inject.Singleton;
import io.micrometer.core.instrument.Counter;
import io.micrometer.prometheus.PrometheusConfig;
import io.micrometer.prometheus.PrometheusMeterRegistry;

@Singleton
public class PrometheusService {

  private static final PrometheusMeterRegistry prometheusRegistry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
  private static final Counter uploadCounter = prometheusRegistry.counter("files.upload","files");
  private static final Counter uploadVersionCounter = prometheusRegistry.counter("files.upload.version","files","version");

  public PrometheusMeterRegistry getRegistry(){
    return prometheusRegistry;
  }

  public Counter getUploadCounter(){
    return uploadCounter;
  }
  public Counter getUploadVersionCounter(){
    return uploadVersionCounter;
  }
}
