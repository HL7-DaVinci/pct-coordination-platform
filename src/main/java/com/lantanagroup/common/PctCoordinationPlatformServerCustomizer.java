package com.lantanagroup.common;

import ca.uhn.fhir.interceptor.api.IInterceptorService;
import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.starter.annotations.OnR4Condition;
import ca.uhn.fhir.rest.server.RestfulServer;
import com.lantanagroup.notification.PctSubscriptionTopicProfileInterceptor;
import com.lantanagroup.notification.SubscriptionNotificationInterceptor;
import com.lantanagroup.providers.GfeCoordinationRequestProvider;
import com.lantanagroup.providers.GfeRetrieveOperation;
import com.lantanagroup.providers.SystemValidateProvider;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Conditional;
import org.springframework.stereotype.Component;

/**
 * Registers the R4 PCT providers and interceptors before the starter servlet initializes.
 */
@Component
@Conditional(OnR4Condition.class)
public class PctCoordinationPlatformServerCustomizer {

  private final RestfulServer restfulServer;
  private final IInterceptorService interceptorService;
  private final DaoRegistry daoRegistry;
  private final SubscriptionNotificationInterceptor subscriptionNotificationInterceptor;
  private final String pctIgVersion;

  public PctCoordinationPlatformServerCustomizer(
      RestfulServer restfulServer,
      IInterceptorService interceptorService,
      DaoRegistry daoRegistry,
      SubscriptionNotificationInterceptor subscriptionNotificationInterceptor,
      @Value("${hapi.fhir.implementationguides.hl_7_fhir_us_davinci_pct.version}") String pctIgVersion) {
    this.restfulServer = restfulServer;
    this.interceptorService = interceptorService;
    this.daoRegistry = daoRegistry;
    this.subscriptionNotificationInterceptor = subscriptionNotificationInterceptor;
    this.pctIgVersion = pctIgVersion;
  }

  @PostConstruct
  public void register() {
    restfulServer.registerInterceptor(new CapabilityStatementCustomizer(pctIgVersion));
    restfulServer.registerInterceptor(new ProcessCustomizer(restfulServer.getFhirContext(), daoRegistry));
    interceptorService.registerInterceptor(new PctSubscriptionTopicProfileInterceptor());
    interceptorService.registerInterceptor(subscriptionNotificationInterceptor);
    restfulServer.registerProviders(
        new GfeRetrieveOperation(restfulServer.getFhirContext(), daoRegistry),
        new GfeCoordinationRequestProvider(daoRegistry),
        new SystemValidateProvider(daoRegistry));
  }
}
