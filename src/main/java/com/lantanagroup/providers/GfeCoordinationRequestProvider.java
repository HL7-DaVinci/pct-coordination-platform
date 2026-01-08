package com.lantanagroup.providers;

import ca.uhn.fhir.rest.annotation.Operation;
import ca.uhn.fhir.rest.annotation.OperationParam;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.api.dao.IFhirSystemDao;
import ca.uhn.fhir.rest.server.exceptions.InvalidRequestException;
import org.hl7.fhir.r4.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class GfeCoordinationRequestProvider {

  private static final Logger logger = LoggerFactory.getLogger(GfeCoordinationRequestProvider.class);

  private final IFhirSystemDao<Bundle, ?> theSystemDao;

  public GfeCoordinationRequestProvider(DaoRegistry daoRegistry) {
    this.theSystemDao = daoRegistry.getSystemDao();
  }

  @Operation(name = "$gfe-coordination-request")
  public Bundle gfeCoordinationRequest(
    @OperationParam(name = "resource", min = 1, max = 1, type = Bundle.class) Bundle theResource,
    RequestDetails theRequestDetails
  ) {
    logger.info("Received $gfe-coordination-request operation with Bundle of type: {}", theResource.getType());
    // Validate that the bundle type is 'transaction'
    if (theResource.getType() != Bundle.BundleType.TRANSACTION) {
      logger.info("Invalid Bundle type: {}. Must be 'transaction'", theResource.getType());
      throw new InvalidRequestException("Bundle type must be 'transaction'");
    }
    int entryCount = (theResource.getEntry() != null) ? theResource.getEntry().size() : 0;
    logger.info("Processing transaction Bundle with {} entries", entryCount);
    return theSystemDao.transaction(theRequestDetails, theResource);
  }
}
