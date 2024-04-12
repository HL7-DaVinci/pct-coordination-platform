package com.lantanagroup;

import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.elasticsearch.ElasticsearchRestClientAutoConfiguration;
import org.springframework.boot.autoconfigure.thymeleaf.ThymeleafAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;

import com.lantanagroup.common.CommonConfig;
import com.lantanagroup.servers.davincipctcoordinationplatform.DavinciPctCoordinationPlatformConfig;
import com.lantanagroup.servers.davincipctcoordinationrequester.DavinciPctCoordinationRequesterConfig;
import com.lantanagroup.servers.davincipctgfecontributor.DavinciPctGfeContributorConfig;
import com.lantanagroup.servers.davincipct.DavinciPctConfig;


@SpringBootApplication(exclude = {ElasticsearchRestClientAutoConfiguration.class, ThymeleafAutoConfiguration.class})
public class ReferenceImplementationApplication {

  public static void main(String[] args) {
		new SpringApplicationBuilder().sources(ReferenceImplementationApplication.class)
			.parent(CommonConfig.class).web(WebApplicationType.NONE)
			.child(DavinciPctCoordinationPlatformConfig.class).web(WebApplicationType.SERVLET)
// Coordination Requester client			
//			.sibling(DavinciPctCoordinationRequesterConfig.class).web(WebApplicationType.SERVLET)
// contributor client
//			.sibling(DavinciPctGfeContributorConfig.class).web(WebApplicationType.SERVLET)
// Payer Server
//			.sibling(DavinciPctConfig.class).web(WebApplicationType.SERVLET)
			.run(args);
	}

}