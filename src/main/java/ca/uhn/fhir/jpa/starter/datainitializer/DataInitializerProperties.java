package ca.uhn.fhir.jpa.starter.datainitializer;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
@ConfigurationProperties
public class DataInitializerProperties {

  private List<String> initialData;

  public List<String> getInitialData() {
    return initialData;
  }

  public void setInitialData(List<String> initialData) {
    this.initialData = initialData;
  }
  
}