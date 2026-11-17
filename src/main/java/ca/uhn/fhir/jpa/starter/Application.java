package ca.uhn.fhir.jpa.starter;

import ca.uhn.fhir.batch2.jobs.config.Batch2JobsConfig;
import ca.uhn.fhir.interceptor.api.IInterceptorService;
import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.batch2.JpaBatch2Config;
import ca.uhn.fhir.jpa.starter.annotations.OnEitherVersion;
import ca.uhn.fhir.jpa.starter.cdshooks.StarterCdsHooksConfig;
import ca.uhn.fhir.jpa.starter.cr.StarterCrDstu3Config;
import ca.uhn.fhir.jpa.starter.cr.StarterCrR4Config;
import ca.uhn.fhir.jpa.starter.mdm.MdmConfig;
import ca.uhn.fhir.jpa.subscription.channel.config.SubscriptionChannelConfig;
import ca.uhn.fhir.jpa.subscription.match.config.SubscriptionProcessorConfig;
import ca.uhn.fhir.jpa.subscription.submit.config.SubscriptionSubmitterConfig;
import ca.uhn.fhir.jpa.topic.SubscriptionTopicConfig;
import ca.uhn.fhir.jpa.topic.SubscriptionTopicDispatcher;
import ca.uhn.fhir.rest.server.RestfulServer;
import com.lantanagroup.common.CapabilityStatementCustomizer;
import com.lantanagroup.common.ProcessCustomizer;
import com.lantanagroup.notification.PctSubscriptionTopicProfileInterceptor;
import com.lantanagroup.notification.SubscriptionNotificationInterceptor;
import com.lantanagroup.notification.SubscriptionWebSocketConfig;
import com.lantanagroup.providers.GfeCoordinationRequestProvider;
import com.lantanagroup.providers.GfeRetrieveOperation;
import com.lantanagroup.providers.SystemValidateProvider;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.AutowireCapableBeanFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.elasticsearch.ElasticsearchRestClientAutoConfiguration;
import org.springframework.boot.autoconfigure.thymeleaf.ThymeleafAutoConfiguration;
import org.springframework.boot.web.servlet.ServletComponentScan;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.boot.web.servlet.support.SpringBootServletInitializer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Import;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@ServletComponentScan(basePackageClasses = {RestfulServer.class})
@SpringBootApplication(
		scanBasePackages = {"ca.uhn.fhir.jpa.starter", "com.lantanagroup"},
		exclude = {ElasticsearchRestClientAutoConfiguration.class, ThymeleafAutoConfiguration.class})
@Import({
	StarterCrR4Config.class,
	StarterCrDstu3Config.class,
	StarterCdsHooksConfig.class,
	SubscriptionSubmitterConfig.class,
	SubscriptionProcessorConfig.class,
	SubscriptionChannelConfig.class,
	SubscriptionWebSocketConfig.class,
	SubscriptionTopicConfig.class,
	MdmConfig.class,
	JpaBatch2Config.class,
	Batch2JobsConfig.class
})
public class Application extends SpringBootServletInitializer {

	public static void main(String[] args) {

		SpringApplication.run(Application.class, args);

		// Server is now accessible at eg. http://localhost:8080/fhir/metadata
		// UI is now accessible at http://localhost:8080/
	}

	@Autowired
	AutowireCapableBeanFactory beanFactory;

	@Bean
	@Conditional(OnEitherVersion.class)
	public ServletRegistrationBean<RestfulServer> hapiServletRegistration(
			RestfulServer restfulServer,
			DaoRegistry daoRegistry,
			SubscriptionNotificationInterceptor subscriptionNotificationInterceptor,
			IInterceptorService interceptorService,
			@org.springframework.beans.factory.annotation.Value(
					"${hapi.implementationguides.hl_7_fhir_us_davinci_pct.version}") String pctIgVersion) {
		ServletRegistrationBean<RestfulServer> servletRegistrationBean = new ServletRegistrationBean<>();
		beanFactory.autowireBean(restfulServer);
		restfulServer.registerInterceptor(new CapabilityStatementCustomizer(pctIgVersion));
		restfulServer.registerInterceptor(new ProcessCustomizer(restfulServer.getFhirContext(), daoRegistry));
		interceptorService.registerInterceptor(new PctSubscriptionTopicProfileInterceptor());
		interceptorService.registerInterceptor(subscriptionNotificationInterceptor);
		restfulServer.registerProviders(
				new GfeRetrieveOperation(restfulServer.getFhirContext(), daoRegistry),
				new GfeCoordinationRequestProvider(daoRegistry),
				new SystemValidateProvider(daoRegistry));
		servletRegistrationBean.setServlet(restfulServer);
		servletRegistrationBean.addUrlMappings("/fhir/*");
		servletRegistrationBean.setLoadOnStartup(1);

		return servletRegistrationBean;
	}

	@Bean
	public SubscriptionNotificationInterceptor subscriptionNotificationInterceptor(
			SubscriptionTopicDispatcher dispatcher) {
		return new SubscriptionNotificationInterceptor(dispatcher);
	}

	@Bean
	public WebMvcConfigurer notificationCorsConfigurer(AppProperties appProperties) {
		return new WebMvcConfigurer() {
			@Override
			public void addCorsMappings(CorsRegistry registry) {
				AppProperties.Cors cors = appProperties.getCors();
				List<String> allowedOrigins = cors != null ? cors.getAllowed_origin() : List.of("*");
				String[] origins = allowedOrigins != null && !allowedOrigins.isEmpty()
						? allowedOrigins.toArray(new String[0])
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
