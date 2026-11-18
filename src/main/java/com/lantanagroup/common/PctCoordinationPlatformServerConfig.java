package com.lantanagroup.common;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.jpa.starter.AppProperties;
import ca.uhn.fhir.jpa.starter.annotations.OnR4Condition;
import ca.uhn.fhir.jpa.topic.SubscriptionTopicDispatcher;
import com.lantanagroup.notification.SubscriptionNotificationInterceptor;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.OpenTelemetry;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Beans the PCT coordination platform adds on top of the HAPI starter.
 */
@Configuration
public class PctCoordinationPlatformServerConfig {

  @Bean
  public OpenTelemetry openTelemetry() {
    return GlobalOpenTelemetry.get();
  }

  @Bean
  @Conditional(OnR4Condition.class)
  public SubscriptionNotificationInterceptor subscriptionNotificationInterceptor(
      SubscriptionTopicDispatcher dispatcher, FhirContext fhirContext, OpenTelemetry openTelemetry) {
    return new SubscriptionNotificationInterceptor(dispatcher, fhirContext, openTelemetry);
  }

  @Bean
  public WebMvcConfigurer notificationCorsConfigurer(AppProperties appProperties) {
    return new WebMvcConfigurer() {
      @Override
      public void addCorsMappings(CorsRegistry registry) {
        AppProperties.Cors cors = appProperties.getCors();
        List<String> allowedOrigins = cors != null ? cors.getAllowed_origin() : null;
        String[] origins = allowedOrigins != null && !allowedOrigins.isEmpty()
            ? allowedOrigins.toArray(String[]::new)
            : new String[] {"*"};

        registry.addMapping("/notification/**")
            .allowedOriginPatterns(origins)
            .allowedMethods("GET", "POST", "DELETE", "OPTIONS")
            .allowedHeaders("*")
            .allowCredentials(cors != null && Boolean.TRUE.equals(cors.getAllow_Credentials()));
      }
    };
  }
}
