package com.lantanagroup.servers.davincipctcoordinationplatform;

import ca.uhn.fhir.jpa.topic.SubscriptionTopicConfig;
import com.lantanagroup.common.*;
import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.jpa.subscription.match.matcher.subscriber.SubscriptionMatchDeliverer;
import ca.uhn.fhir.jpa.subscription.match.registry.SubscriptionRegistry;
import ca.uhn.fhir.jpa.topic.SubscriptionTopicDispatcher;
import ca.uhn.fhir.jpa.topic.SubscriptionTopicPayloadBuilder;
import com.lantanagroup.notification.SubscriptionWebSocketConfig;
import com.lantanagroup.providers.GfeCoordinationRequestProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.elasticsearch.ElasticsearchRestClientAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.PropertySource;
import com.lantanagroup.providers.GfeRetrieveOperation;


import ca.uhn.fhir.batch2.jobs.config.Batch2JobsConfig;
import ca.uhn.fhir.jpa.batch2.JpaBatch2Config;
import ca.uhn.fhir.jpa.config.r4.JpaR4Config;
import ca.uhn.fhir.jpa.starter.common.FhirServerConfigCommon;
import ca.uhn.fhir.jpa.starter.common.StarterJpaConfig;
import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.subscription.channel.config.SubscriptionChannelConfig;
import ca.uhn.fhir.jpa.subscription.match.config.SubscriptionProcessorConfig;
import ca.uhn.fhir.jpa.subscription.submit.config.SubscriptionSubmitterConfig;
import ca.uhn.fhir.rest.server.RestfulServer;
import ca.uhn.fhir.rest.server.interceptor.ResponseHighlighterInterceptor;
import com.lantanagroup.notification.SubscriptionNotificationInterceptor;
import com.lantanagroup.notification.SubscriptionNotificationController;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
@ComponentScan(basePackageClasses = { DavinciPctCoordinationPlatformConfig.class, SubscriptionNotificationController.class },
    basePackages = { "ca.uhn.fhir.jpa.starter.datainitializer" })
@PropertySource("classpath:davincipctcoordinationplatform.properties")
@EnableAutoConfiguration(exclude = {
  ElasticsearchRestClientAutoConfiguration.class
})
@Import({
  JpaR4Config.class,
  StarterJpaConfig.class,
  FhirServerConfigCommon.class,
  SubscriptionSubmitterConfig.class,
	SubscriptionProcessorConfig.class,
	SubscriptionChannelConfig.class,
    SubscriptionWebSocketConfig.class,
    SubscriptionTopicConfig.class,
  JpaBatch2Config.class,
	Batch2JobsConfig.class
})
public class DavinciPctCoordinationPlatformConfig extends CommonConfig {

  @Autowired
  protected DavinciPctCoordinationPlatformProperties serverProperties;

  @Autowired
  protected DaoRegistry daoRegistry;

  @Primary
  @Bean
  public DataSourceProperties dataSourceProperties() {
    return serverProperties.getDatasource();
  }

  @Autowired
  private SubscriptionNotificationInterceptor subscriptionNotificationInterceptor;

  @Bean
  public SubscriptionNotificationInterceptor subscriptionNotificationInterceptor(SubscriptionTopicDispatcher dispatcher) {
    return new SubscriptionNotificationInterceptor(dispatcher);
  }

  @Bean
  public ServletRegistrationBean<RestfulServer> fhirServletRegistrationBean(RestfulServer restfulServer, SubscriptionNotificationInterceptor subscriptionNotificationInterceptor) {

    restfulServer.registerInterceptor(new ResponseHighlighterInterceptor());
    restfulServer.registerInterceptor(new CapabilityStatementCustomizer(restfulServer.getFhirContext(), "davincipctcoordinationplatform"));
    restfulServer.registerInterceptor(new ProcessCustomizer(restfulServer.getFhirContext(), daoRegistry, "davincipctcoordinationplatform"));
    restfulServer.registerInterceptor(subscriptionNotificationInterceptor);
    restfulServer.registerProviders(
        new GfeRetrieveOperation(restfulServer.getFhirContext(), daoRegistry),
        new GfeCoordinationRequestProvider(daoRegistry)
    );

    ServletRegistrationBean<RestfulServer> registration = new ServletRegistrationBean<>(restfulServer, "/fhir/*");
    registration.setLoadOnStartup(1);
    return registration;
  }

  @Bean
  public SubscriptionTopicDispatcher subscriptionTopicDispatcher(
      FhirContext fhirContext,
      SubscriptionRegistry subscriptionRegistry,
      SubscriptionMatchDeliverer subscriptionMatchDeliverer,
      SubscriptionTopicPayloadBuilder subscriptionTopicPayloadBuilder
  ) {
      return new SubscriptionTopicDispatcher(
          fhirContext,
          subscriptionRegistry,
          subscriptionMatchDeliverer,
          subscriptionTopicPayloadBuilder
      );
  }

  @Bean
  public WebMvcConfigurer notificationCorsConfigurer() {
    return new WebMvcConfigurer() {
      @Override
      public void addCorsMappings(CorsRegistry registry) {
        java.util.List<String> allowedOrigins = serverProperties.getCors() != null
            ? serverProperties.getCors().getAllowed_origin()
            : java.util.List.of("*");
        boolean allowCredentials = serverProperties.getCors() != null
            && Boolean.TRUE.equals(serverProperties.getCors().getAllow_Credentials());

        String[] origins = (allowedOrigins != null && !allowedOrigins.isEmpty())
            ? allowedOrigins.toArray(new String[0])
            : new String[]{"*"};

        var mapping = registry.addMapping("/notification/**")
            .allowedOriginPatterns(origins)
            .allowedMethods("GET", "POST", "DELETE", "OPTIONS")
            .allowedHeaders("*");

        if (allowCredentials) {
          mapping.allowCredentials(true);
        }
      }
    };
  }

}
