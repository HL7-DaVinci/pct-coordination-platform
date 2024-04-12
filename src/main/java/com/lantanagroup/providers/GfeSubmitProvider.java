package com.lantanagroup.providers;

import ca.uhn.fhir.rest.annotation.Operation;
import ca.uhn.fhir.rest.annotation.OperationParam;
import ca.uhn.fhir.rest.server.exceptions.NotImplementedOperationException;
import org.hl7.fhir.r4.model.*;

public class GfeSubmitProvider {

  @Operation(name = "$gfe-submit", type = Claim.class)
  public OperationOutcome gfeSubmit(
    @OperationParam(name = "resource", min = 1, max = 1, type = Bundle.class) Bundle theResource
  ) {
    // TODO: Implement operation $gfe-submit
    throw new NotImplementedOperationException("Operation $gfe-submit is not implemented");
    
    // OperationOutcome retVal = new OperationOutcome();
    // return retVal;
    
  }

  
}
