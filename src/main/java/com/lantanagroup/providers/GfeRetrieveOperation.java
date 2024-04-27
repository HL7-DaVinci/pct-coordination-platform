package com.lantanagroup.providers;

import jakarta.servlet.http.HttpServletResponse;
import java.util.Date;
//import java.util.Random;
import java.util.List;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.Base64;
import java.nio.charset.StandardCharsets;

import ca.uhn.fhir.rest.annotation.Operation;
import ca.uhn.fhir.rest.annotation.OperationParam;
import ca.uhn.fhir.jpa.searchparam.SearchParameterMap;
import ca.uhn.fhir.rest.server.exceptions.NotImplementedOperationException;
import ca.uhn.fhir.rest.server.exceptions.InvalidRequestException;
import ca.uhn.fhir.rest.server.exceptions.InternalErrorException;


// Need to add to code generation
import ca.uhn.fhir.jpa.api.dao.IFhirResourceDao;
import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import org.hl7.fhir.r4.model.*;
import org.hl7.fhir.instance.model.api.*;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.api.server.ResponseDetails;
import ca.uhn.fhir.rest.api.server.IBundleProvider;
import ca.uhn.fhir.rest.param.*;
import ca.uhn.fhir.parser.IParser;


import org.hl7.fhir.r4.model.Bundle.BundleType;


/*
 * TODO Code
 *  Extract bundle request information to put into GFE missing Bundle (safely)
 *  Review the requirements of the IG
 * 
 * Later
 *  Don't assume there is the anticipated input and output bundles. and test
 * Configure to require referential integrity (can't have references that do not resolve)
 * 
 * Sample data (how to seed?)
 *    Need phone book practitioner and organization resources
 * 
 * Interceptor for task write/update of status
 * Task Status When deemed appropriate, the GFE Coordination Requester SHALL close the Task by updating the status to completed or cancelled (the choice of which depends on the intent of the requester) When the status of the 
 *    GFE Coordination Task is updated, the Coordination Platform SHALL update the associated GFE Contributor Task statuses to match, except for those that have a status of cancelled, rejected, entered-in-error, failed, or completed`.
 * Need an interceptor for the SERVER_PROCESSING_COMPLETED_NORMALLY PointCut looking to see if it was a Task where the status was updated (possible?) Or just make sure all contributor tasks are updated appropriately (Catch any exceptions)
 * 
 * GFE Contributors SHALL only be able to set their assigned Task.status to received, accepted, rejected, or completed.
 */

/*
 * TODO Tickets
 *    add the fhir context and daoregistry to the code generator for all operation provider constructors (and of course to the related config class that instantiates it)?
 *    want to also have a lot of the search capabilities present (imports to the various param types, for example). (Looking at the imports above and add additional common ones) 
 *      perhaps just import ca.uhn.fhir.rest.param.*; and org.hl7.fhir.instance.model.api.*;
 *    Add theRequestDetails to the method parameters (See after @OperationParam below)
 */

public class GfeRetrieveOperation {
  private FhirContext theFhirContext;
  //private DaoRegistry daoRegistry;
  private IFhirResourceDao<Task> theTaskDao;
  private IFhirResourceDao<Practitioner> thePractitionerDao;
  private IFhirResourceDao<PractitionerRole> thePractitionerRoleDao;
  private IFhirResourceDao<Organization> theOrganizationDao;

  public GfeRetrieveOperation(FhirContext ctx, DaoRegistry daoRegistry) {
    this.theFhirContext = ctx;
    theTaskDao = daoRegistry.getResourceDao(Task.class);
    thePractitionerDao = daoRegistry.getResourceDao(Practitioner.class);
    thePractitionerRoleDao = daoRegistry.getResourceDao(PractitionerRole.class);
    theOrganizationDao = daoRegistry.getResourceDao(Organization.class);
    //theDomainResourceDao = daoRegistry.getResourceDao(DomainResource.class);
  }


  @Operation(name = "$gfe-retrieve")
  public Bundle gfeRetrieve(
    @OperationParam(name = "request", min = 1, max = 1, type = Reference.class) Reference theRequest,
    RequestDetails theRequestDetails,
    HttpServletResponse theServletResponse
  ) {
  
    
    // If the task is not retrieveable, the upstream code will catch this call and provide a 404 with an operation Outcome noting that the Task is not known.
    Task requestTask = theTaskDao.read(theRequest.getReferenceElement(), theRequestDetails);
    
    
    
    
    // The Task needs to be a gfe-coordination-task, Check the Task Code and verify it is the correct type, and if not, reject
    if(requestTask.hasCode() && requestTask.getCode().hasCoding("http://hl7.org/fhir/us/davinci-pct/CodeSystem/PCTGFERequestTaskCSTemporaryTrialUse", "gfe-coordination-task"))
    {
      Bundle responseBundle = createCollectionBundle(requestTask, theRequestDetails);
      //theServletResponse.setStatus(201);
      //IdType id = new IdType("Patient", theId);
      return responseBundle;
    }
    else
    {
      String codeFoundString = "No Task.code Found.";
      if(requestTask.hasCode() && requestTask.getCode().getCodingFirstRep().hasCode())
      {
        codeFoundString = "Found: '" + requestTask.getCode().getCodingFirstRep().getCode() + "'.";
      }
      // The task is not the proper type. Create and OperationOutcome with 
      //theServletResponse.setStatus(512);
      //theServletResponse.getWriter()
      //theServletResponse.getWriter().close();
      //return null;
      throw new InvalidRequestException("Invalid Task type. Task reference must be to a Task where Task.code = 'gfe-coordination-task'. " + codeFoundString);
      //theResponseDetails.setResponseCode(512);

    }
    
/*
    TokenOrListParam identifierParam = new TokenOrListParam();
		for (Identifier identifier : theCoverageToMatch.getIdentifier()) {
			identifierParam.add(identifier.getSystem(), identifier.getValue());
		}

		SearchParameterMap paramMap = new SearchParameterMap()
			.add("identifier", identifierParam);
		ca.uhn.fhir.rest.api.server.IBundleProvider retVal = myCoverageDao.search(paramMap, theRequestDetails);

		return retVal.getAllResources();
     */
    //throw new InternalErrorException("Unaddressed internal error on gfe-retrieve operation");
    
    // Bundle retVal = new Bundle();
    // return retVal;
    
  }


  
  /**
   * Create a new bundle with type Collection with contributed GFE Bundles and GFE Missing Bundles
   * used for queries
   * 
   * @return the new bundle
   */
  public Bundle createCollectionBundle(Task coordinationTask, RequestDetails theRequestDetails) {
    Bundle collectionBundle = new Bundle();
    collectionBundle.setType(BundleType.COLLECTION);
    Identifier identifier = new Identifier();
    
    identifier.setSystem(theRequestDetails.getFhirServerBase() + "/resourceIdentifiers");
    String uuid = UUID.randomUUID().toString();
    identifier.setValue(uuid);
    collectionBundle.setIdentifier(identifier);
    collectionBundle.setTimestamp(new Date());

    Patient patient = null;
    Coverage coverage = null;
    List<Resource> copyResouceList = new ArrayList();
    



    // get common elements from Coordination Task
    // TODO JIRA, will need a way to designate the types of input/outputs.
    
    if(coordinationTask.hasInput())
    {
      Bundle informationBundle = getAttachedInputBundle(coordinationTask.getInput());
      if(informationBundle != null)
      {
        informationBundle.getEntry().forEach(entry -> 
        {
          Resource entryResource = entry.getResource();
          if((entryResource.getResourceType() == ResourceType.Patient) ||
          (entryResource.getResourceType() == ResourceType.Coverage) ||
          (entryResource.getResourceType() == ResourceType.Practitioner) ||
          (entryResource.getResourceType() == ResourceType.PractitionerRole) ||
          (entryResource.getResourceType() == ResourceType.Organization) ||
          (entryResource.getResourceType() == ResourceType.ServiceRequest) ||
          (entryResource.getResourceType() == ResourceType.MedicationRequest) ||
          (entryResource.getResourceType() == ResourceType.DeviceRequest) ||
          (entryResource.getResourceType() == ResourceType.NutritionOrder) ||
          (entryResource.getResourceType() == ResourceType.VisionPrescription))
          {
            copyResouceList.add(entryResource);
          }
          if((entryResource.getResourceType() == ResourceType.Patient) ||
          (entryResource.getResourceType() == ResourceType.Coverage))
          {
            Bundle.BundleEntryComponent resourceEntry = new Bundle.BundleEntryComponent();
            resourceEntry.setResource(entryResource);
            collectionBundle.addEntry(resourceEntry);
            
          }

          /* Am alternative way to check for the payer organization and place in the bundle
          if(entryResource.getResourceType() == ResourceType.Organization)
          {
            Organization theOrganization = (Organization)entryResource;
            if(theOrganization.hasType())
            {
              // Check to see if there is an Organization.type with a code = pay
              if(theOrganization.getType().stream().filter(type -> type.getCoding().stream().filter(coding -> coding.getCode().equals("pay")).findFirst().isPresent()).findFirst().isPresent())
              {
                // Copy payer organization
                // TODO, this could be better, probably finding the Coverage.payer reference that matches
                Bundle.BundleEntryComponent resourceEntry = new Bundle.BundleEntryComponent();
                resourceEntry.setResource(theOrganization);
                collectionBundle.addEntry(resourceEntry);
              }
            
            } 
            Bundle.BundleEntryComponent resourceEntry = new Bundle.BundleEntryComponent();
            resourceEntry.setResource(entryResource);
            collectionBundle.addEntry(resourceEntry);
            
          }
           */
          
        });    
      }

      DomainResource taskRequester = null;
      if(coordinationTask.hasRequester())
      {
        taskRequester = getEntityResourceByReference(coordinationTask.getRequester(), theRequestDetails);
        if(taskRequester != null)
        {
          Bundle.BundleEntryComponent taskRequesterEntry = new Bundle.BundleEntryComponent();
          taskRequesterEntry.setResource(taskRequester);
          collectionBundle.addEntry(taskRequesterEntry);
        }
      }
      /*
      coordinationTask.getInput().forEach(input -> {
        if(input.hasValue())
        {
          Type value = input.getValue();
          if(value instanceof Attachment)
          {
            Attachment attachment = (Attachment)value;
            if(attachment.hasData())
            {
              String attachmentString = new String(attachment.getData(), StandardCharsets.UTF_8);

              // TODO, currently expectsa a bundle type only

              if(attachment.hasContentType() && attachment.getContentType().equals("application/fhir+json"))
              {
                // Instantiate a new parser
                IParser parser = this.theFhirContext.newJsonParser();

                // Parse it
                Bundle informationBundle = parser.parseResource(Bundle.class, attachmentString);
                // Get the resources that are meant to be carried over
                informationBundle.getEntry().forEach(entry -> 
                {
                  Resource entryResource = entry.getResource();
                  if((entryResource.getResourceType() == ResourceType.Patient) ||
                  (entryResource.getResourceType() == ResourceType.Coverage) ||
                  (entryResource.getResourceType() == ResourceType.Practitioner) ||
                  (entryResource.getResourceType() == ResourceType.PractitionerRole) ||
                  (entryResource.getResourceType() == ResourceType.Organization) ||
                  (entryResource.getResourceType() == ResourceType.ServiceRequest) ||
                  (entryResource.getResourceType() == ResourceType.MedicationRequest) ||
                  (entryResource.getResourceType() == ResourceType.DeviceRequest) ||
                  (entryResource.getResourceType() == ResourceType.NutritionOrder) ||
                  (entryResource.getResourceType() == ResourceType.VisionPrescription))
                  {
                    copyResouceList.add(entryResource);
                  }
                });                
              }
              else if(attachment.hasContentType() && attachment.getContentType() == "application/fhir+xml")
              {
                
              }
            }
          }
        }
      });

      
     */ 
    }
    //byte[] decoded = Base64.getDecoder().decode(encoded);
    //String decodedStr = new String(decoded, StandardCharsets.UTF_8);
 
    List<Task> contributorTasks = getContributorTasks(coordinationTask, theRequestDetails);
    contributorTasks.forEach(task -> {
      //boolean hasGFEBundle = false;
      // All of the FHIR Resources in .output.valueAttachment of the associated (that have a Task.partOf that references the GFE Coordination Task) 
      //    GFE Contributor Task where the status is not rejected or cancelled (NOTE: that each Contributor Task may have multiple output.valueAttachment iterations.
      if(task.getStatus() != Task.TaskStatus.REJECTED && task.getStatus() != Task.TaskStatus.CANCELLED)
      {
        AtomicBoolean hasGFEBundle = new AtomicBoolean();
        hasGFEBundle.set(false);
        
        //var hasBundleObject = new Object(){ boolean hasGFEBundle = false; };
        

        DomainResource taskOwner = null;
        if(task.hasOwner())
        {
          taskOwner = getEntityResourceByReference(task.getOwner(), theRequestDetails);
        }

        if(task.hasOutput())
        {
          task.getOutput().forEach(output -> {
            
            // TODO Make more robust to get a matching bundle (Same for all bundles)
            if(output.getType().hasCoding("http://hl7.org/fhir/us/davinci-pct/CodeSystem/PCTTaskOutputTypeCSTemporaryTrialUse", "gfe-bundle"))
            {
              
              hasGFEBundle.set(true);
            }
          });
        }
        if(hasGFEBundle.get())
        {
          
          if(task.hasOutput())
          {
            Bundle gfeBundle = getAttachedOutputBundle(task.getOutput());
            if(gfeBundle != null)
            {
              Bundle.BundleEntryComponent gfeBundleEntry = new Bundle.BundleEntryComponent();
              gfeBundleEntry.setResource(gfeBundle);
              collectionBundle.addEntry(gfeBundleEntry);
            }
            else
            {
              hasGFEBundle.set(false);
            }
          }
          
        }
        if(!hasGFEBundle.get())
        {
          // create a GFE Missing Bundle
          Bundle missingBundle = new Bundle();
          missingBundle.setType(BundleType.COLLECTION);
          Identifier missingIdentifier = new Identifier();
          identifier.setSystem(theRequestDetails.getFhirServerBase() + "/resourceIdentifiers");
          String missingUuid = UUID.randomUUID().toString();
          identifier.setValue(missingUuid);
          missingBundle.setIdentifier(missingIdentifier);
          missingBundle.setTimestamp(new Date());
          if(taskOwner != null)
          {
            Bundle.BundleEntryComponent taskOwnerEntry = new Bundle.BundleEntryComponent();
            taskOwnerEntry.setResource(taskOwner);
            missingBundle.addEntry(taskOwnerEntry);
          }
          copyResouceList.forEach(copyResource -> {
            Bundle.BundleEntryComponent copyEntry = new Bundle.BundleEntryComponent();
            copyEntry.setResource(copyResource);
            missingBundle.addEntry(copyEntry);
          });
          Bundle.BundleEntryComponent missingBundleEntry = new Bundle.BundleEntryComponent();
          missingBundleEntry.setResource(missingBundle);
          collectionBundle.addEntry(missingBundleEntry);
        }
      }
    }
    );
    return collectionBundle;
  }

  public List<Task> getContributorTasks(Task coordinationTask, RequestDetails theRequestDetails)
  {
    List<Task> contributorTasks = new ArrayList<>();
    SearchParameterMap searchMap = new SearchParameterMap();
    //ReferenceParam taskReference = new ReferenceParam(coordinationTask.getId());
    searchMap.add(Task.PART_OF.getParamName(), new ReferenceParam(coordinationTask.getId()));
    IBundleProvider taskResults = theTaskDao.search(searchMap, theRequestDetails);
    
		taskResults.getResources(0, taskResults.size())
			.stream().map(Task.class::cast)
			.forEach(task -> contributorTasks.add(task));

    //theTaskDao.search(, theRequestDetails)
    return contributorTasks;
  }

  public DomainResource getEntityResourceByReference(Reference reference, RequestDetails theRequestDetails)
  {
    DomainResource retVal = null;

    try {
      switch (reference.getReferenceElement().getResourceType()) {
        case "Practitioner":
          retVal = thePractitionerDao.read(reference.getReferenceElement(), theRequestDetails);
          break;
        case "PractitionerRole":
        retVal = thePractitionerRoleDao.read(reference.getReferenceElement(), theRequestDetails);
          break;
        case "Organization":
        retVal = theOrganizationDao.read(reference.getReferenceElement(), theRequestDetails);
          break;
        default:
          break;
      }
    } catch (Exception e) {
      //myLogger.info("Error in submission");
      //myLogger.info(e.getMessage());
      e.printStackTrace();
    }

    return retVal;
  }
  

  public Bundle getAttachedInputBundle(List<Task.ParameterComponent> parameters)
  {
    // TODO need have better identification of inputs/outputs of interest. Need to make sure they are bundles and bundles that are needed. Probably could add a parameter in function detailing what is being requested.
    List<Bundle> AttachedBundle = new ArrayList<>();
    parameters.forEach(parameter -> {
      if(parameter.hasValue())
      {
        Type value = parameter.getValue();
        if(value instanceof Attachment)
        {
          AttachedBundle.add(getAttachmentBundle((Attachment)value));
        }
      }
    });
    return AttachedBundle.get(0);
  }

  public Bundle getAttachedOutputBundle(List<Task.TaskOutputComponent> parameters)
  {
    // TODO need have better identification of inputs/outputs of interest. Need to make sure they are bundles and bundles that are needed. Probably could add a parameter in function detailing what is being requested.
    List<Bundle> AttachedBundle = new ArrayList<>();
    parameters.forEach(parameter -> {
      if(parameter.hasValue())
      {
        Type value = parameter.getValue();
        if(value instanceof Attachment)
        {
          AttachedBundle.add(getAttachmentBundle((Attachment)value));
        }
      }
    });
    return AttachedBundle.get(0);
  }

  public Bundle getAttachmentBundle(Attachment theAttachment)
  {
    Bundle attachmentBundle = null;

    if(theAttachment.hasData())
    {
      String attachmentString = new String(theAttachment.getData(), StandardCharsets.UTF_8);

      // TODO, currently expects as a bundle type only

      if(theAttachment.hasContentType() && theAttachment.getContentType().equals("application/fhir+json"))
      {
        // Instantiate a new parser
        IParser parser = this.theFhirContext.newJsonParser();

        // Parse it
        attachmentBundle = parser.parseResource(Bundle.class, attachmentString);
      }
      else if(theAttachment.hasContentType() && theAttachment.getContentType() == "application/fhir+xml")
      {
        // Instantiate a new parser
        IParser parser = this.theFhirContext.newXmlParser();
        // Parse it
        attachmentBundle = parser.parseResource(Bundle.class, attachmentString);
      }
    }


    return attachmentBundle;
  }

  
}




