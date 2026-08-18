// SPDX-FileCopyrightText: 2024 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.config;

import com.zextras.carbonio.files.Constants;
import com.zextras.carbonio.message_broker.MessageBrokerClient;
import com.zextras.carbonio.message_broker.config.enums.Service;
import com.zextras.carbonio.quarkus.extensions.bootstrap.ApplicationConfigService;
import com.zextras.carbonio.quarkus.extensions.bootstrap.NetworkingConfigService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;

/**
 * CDI producer for the {@link MessageBrokerClient} bean. Mirrors the legacy Guice {@code
 * FilesModule.provideMessageBrokerClient}: host/port come from {@link NetworkingConfigService}
 * ({@code carbonio.message-broker.*}, defaulting to the mesh IP/port from {@code
 * package/carbonio-files.hcl}); username/password are read from the {@code carbonio-message-broker}
 * service's own Consul KV namespace (NOT this service's own {@code application-config.*} prefix),
 * via {@link ApplicationConfigService#getFromRawPath}.
 *
 * <p>{@link MessageBrokerClient#fromConfig} only builds a (lazy, not-yet-connected) {@code
 * ConnectionFactory}; it never attempts a connection, so this producer never throws even if
 * RabbitMQ is unreachable. The actual connection attempt (and its graceful degrade) happens in
 * {@link com.zextras.carbonio.files.message_broker.MessageBrokerManagerImpl}.
 */
@ApplicationScoped
public class MessageBrokerClientProducer {

  private final ApplicationConfigService applicationConfig;
  private final NetworkingConfigService networkingConfig;

  @Inject
  public MessageBrokerClientProducer(
      ApplicationConfigService applicationConfig, NetworkingConfigService networkingConfig) {
    this.applicationConfig = applicationConfig;
    this.networkingConfig = networkingConfig;
  }

  @Produces
  @ApplicationScoped
  public MessageBrokerClient produceMessageBrokerClient() {
    String host =
        networkingConfig
            .get(Constants.Config.MessageBroker.HOST_PROPERTY)
            .orElse(Constants.Config.MessageBroker.DEFAULT_HOST);
    int port =
        networkingConfig
            .get(Constants.Config.MessageBroker.PORT_PROPERTY)
            .map(Integer::parseInt)
            .orElse(Constants.Config.MessageBroker.DEFAULT_PORT);
    String username =
        applicationConfig
            .getFromRawPath(
                Constants.ServiceDiscover.MESSAGE_BROKER_SERVICE_NAME + "/default/username")
            .orElse(Constants.MessageBroker.Config.DEFAULT_USERNAME);
    String password =
        applicationConfig
            .getFromRawPath(
                Constants.ServiceDiscover.MESSAGE_BROKER_SERVICE_NAME + "/default/password")
            .orElse(Constants.MessageBroker.Config.DEFAULT_PASSWORD);

    return MessageBrokerClient.fromConfig(host, port, username, password)
        .withCurrentService(Service.FILES);
  }
}
