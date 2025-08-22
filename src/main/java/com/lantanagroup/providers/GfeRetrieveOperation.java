package com.lantanagroup.providers;

import ca.uhn.fhir.rest.annotation.IdParam;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.nio.charset.StandardCharsets;

import ca.uhn.fhir.rest.annotation.Operation;
import ca.uhn.fhir.jpa.searchparam.SearchParameterMap;
import ca.uhn.fhir.rest.server.exceptions.InvalidRequestException;


// Need to add to code generation
import ca.uhn.fhir.jpa.api.dao.IFhirResourceDao;
import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import org.hl7.fhir.r4.model.*;
import org.hl7.fhir.instance.model.api.*;
import ca.uhn.fhir.rest.api.server.RequestDetails;
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

  private static final org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(GfeRetrieveOperation.class);
  private static final String PCT_GFE_SUMMARY_PROFILE = "http://hl7.org/fhir/us/davinci-pct/StructureDefinition/davinci-pct-gfe-summary";
  private static final String PCT_GFE_PACKET_PROFILE = "http://hl7.org/fhir/us/davinci-pct/StructureDefinition/davinci-pct-gfe-packet";
  private static final String PCT_GFE_MISSING_BUNDLE_PROFILE = "http://hl7.org/fhir/us/davinci-pct/StructureDefinition/davinci-pct-gfe-missing-bundle";
  private static final String PCT_GFE_BUNDLE_PROFILE = "http://hl7.org/fhir/us/davinci-pct/StructureDefinition/davinci-pct-gfe-bundle";
  private static final String PCT_GFE_COMPOSITION_PROFILE = "http://hl7.org/fhir/us/davinci-pct/StructureDefinition/davinci-pct-gfe-composition";
  private static final String PCT_GFE_DOCUMENT_REFERENCE_PROFILE = "http://hl7.org/fhir/us/davinci-pct/StructureDefinition/davinci-pct-gfe-documentreference";

  private FhirContext theFhirContext;
  //private DaoRegistry daoRegistry;
  private IFhirResourceDao<Task> theTaskDao;
  private IFhirResourceDao<Practitioner> thePractitionerDao;
  private IFhirResourceDao<PractitionerRole> thePractitionerRoleDao;
  private IFhirResourceDao<Organization> theOrganizationDao;
  private IFhirResourceDao<Patient> thePatientDao;
  private IFhirResourceDao<DocumentReference> theDocumentReferenceDao;
  private IFhirResourceDao<Bundle> theBundleDao;
  private IFhirResourceDao<Composition> theCompositionDao;


  public GfeRetrieveOperation(FhirContext ctx, DaoRegistry daoRegistry) {
    this.theFhirContext = ctx;
    theTaskDao = daoRegistry.getResourceDao(Task.class);
    thePractitionerDao = daoRegistry.getResourceDao(Practitioner.class);
    thePractitionerRoleDao = daoRegistry.getResourceDao(PractitionerRole.class);
    theOrganizationDao = daoRegistry.getResourceDao(Organization.class);
    thePatientDao = daoRegistry.getResourceDao(Patient.class);
    theDocumentReferenceDao = daoRegistry.getResourceDao(DocumentReference.class);
    theBundleDao = daoRegistry.getResourceDao(Bundle.class);
    theCompositionDao = daoRegistry.getResourceDao(Composition.class);

    //theDomainResourceDao = daoRegistry.getResourceDao(DomainResource.class);
  }


  @Operation(name = "$gfe-retrieve" , type = Task.class)
  public Bundle gfeRetrieve(
    @IdParam IIdType theId,
    RequestDetails theRequestDetails) throws Exception {

    // If the task is not retrieveable, the upstream code will catch this call and provide a 404 with an operation Outcome noting that the Task is not known.
    Task requestTask = theTaskDao.read(theId, theRequestDetails);

    // The Task needs to be a gfe-coordination-task, Check the Task Code and verify it is the correct type, and if not, reject
    if(requestTask.hasCode() && requestTask.getCode().hasCoding("http://hl7.org/fhir/us/davinci-pct/CodeSystem/PCTGFERequestTaskCSTemporaryTrialUse", "gfe-coordination-task"))
    {
      Bundle responseBundle = createPacketBundle(requestTask, theRequestDetails);

      logger.info("Save/Update GFE Packet "+responseBundle.getId());
      theBundleDao.update(responseBundle, theRequestDetails);

      // Create and save the DocumentReference resource
      DocumentReference docRef = createDocumentReference(responseBundle, requestTask, theRequestDetails);
      if (docRef != null) {
        logger.info("Save/Update GFE DocumentReference "+docRef.getId());
        theDocumentReferenceDao.update(docRef, theRequestDetails);
      }
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
  public Bundle createPacketBundle(Task coordinationTask, RequestDetails theRequestDetails) {
    Bundle packetBundle = new Bundle();
    packetBundle.setType(BundleType.DOCUMENT);

    // Setting the id for the GFE Packet Bundle using the Coordination Task's id to ensure the Bundle can be updated for this Task.
    packetBundle.setId("PCT-GFE-Packet-"+coordinationTask.getIdElement().getIdPart());

    Meta packet_bundle_meta = new Meta();
    packet_bundle_meta.addProfile(PCT_GFE_PACKET_PROFILE);
    packetBundle.setMeta(packet_bundle_meta);
    Identifier identifier = new Identifier();
    
    identifier.setSystem(theRequestDetails.getFhirServerBase() + "/resourceIdentifiers");
    String uuid = UUID.randomUUID().toString();
    identifier.setValue(uuid);
    packetBundle.setIdentifier(identifier);
    packetBundle.setTimestamp(new Date());
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
            // Extract Practitioner, Organization, and Patient resources from the GFE Information Bundle attached to the Coordination Task
            // and update or save them to the Coordination Platform server.
            if(entryResource.getResourceType() == ResourceType.Patient || entryResource.getResourceType() == ResourceType.Practitioner || entryResource.getResourceType() == ResourceType.Organization)
            {
              logger.info("Save/Update resource of type: {} with ID: {}", entryResource.getResourceType().name(), entryResource.getIdElement().getIdPart());
              saveResource(entryResource, theRequestDetails);
            }
            copyResouceList.add(entryResource);
          }
          if((entryResource.getResourceType() == ResourceType.Patient) ||
          (entryResource.getResourceType() == ResourceType.Coverage))
          {
            Bundle.BundleEntryComponent resourceEntry = new Bundle.BundleEntryComponent();
            resourceEntry.setResource(entryResource);
            packetBundle.addEntry(resourceEntry);
            
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
          packetBundle.addEntry(taskRequesterEntry);
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
        // If the contributor task is not marked as completed, a GFE Missing Bundle must be created, even if a GFE bundle is present.
        if(task.hasOutput() && task.hasStatus() && task.getStatus() == Task.TaskStatus.COMPLETED)
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
              //logger.info(gfeBundle.getIdElement().getIdPart());
              gfeBundle.setId("PCT-GFE-Bundle-"+task.getIdElement().getIdPart());
              /*logger.info("Save/Update GFE Bundle "+gfeBundle.getId());
              theBundleDao.update(gfeBundle);*/
              Bundle.BundleEntryComponent gfeBundleEntry = new Bundle.BundleEntryComponent();
              gfeBundleEntry.setResource(gfeBundle);
              packetBundle.addEntry(gfeBundleEntry);
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
          Meta missing_bundle_meta = new Meta();
          missing_bundle_meta.addProfile(PCT_GFE_MISSING_BUNDLE_PROFILE);
          missingBundle.setMeta(missing_bundle_meta);
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
          packetBundle.addEntry(missingBundleEntry);
        }
      }
    }
    );

    logger.info("Adding GFE Composition to GFE Packet Bundle");
    addGFECompositionToPacketBundle(packetBundle, coordinationTask, theRequestDetails);

    return packetBundle;
  }


  private void saveResource(Resource resource, RequestDetails theRequestDetails) {
    if (resource == null ||
            !(resource instanceof Practitioner ||
                    resource instanceof Organization ||
                    resource instanceof Patient)) return;
    logger.info("Full Resource ID: " + resource.getIdElement().getValue());
    logger.info("LogicalId: " + resource.getIdElement().getIdPart());

    resource.setId(resource.getIdElement().toVersionless());

    logger.info("Full Resource ID Post Versionless: " + resource.getIdElement().getValue());
    logger.info("LogicalId: Post Versionless: " + resource.getIdElement().getIdPart());

    String logicalId = resource.getIdElement().getIdPart();
    if (logicalId != null && logicalId.startsWith("urn:uuid:")) {
      logicalId = logicalId.substring("urn:uuid:".length());
      resource.setId(logicalId);
      logger.info("Removed urn:uuid:, new LogicalId: " + resource.getIdElement().getIdPart());
    }

    IFhirResourceDao dao = null;
    if (resource instanceof Practitioner) {
      dao = thePractitionerDao;
    } else if (resource instanceof Organization) {
      dao = theOrganizationDao;
    } else if (resource instanceof Patient) {
      dao = thePatientDao;
    }

    if (dao != null) {
      logger.info("Update resource of type: " + resource.getResourceType().name());
      dao.update(resource, theRequestDetails);
    }
  }

  private DocumentReference createDocumentReference(Bundle gfePacket, Task coordinationTask, RequestDetails theRequestDetails) {
    DocumentReference docRef = null;
    try {
      docRef = new DocumentReference();
      docRef.setId("PCT-GFE-DocumentReference-"+coordinationTask.getIdElement().getIdPart());

      // Set meta/profile
      Meta meta = new Meta();
      meta.addProfile(PCT_GFE_DOCUMENT_REFERENCE_PROFILE);
      docRef.setMeta(meta);

      docRef.setStatus(Enumerations.DocumentReferenceStatus.CURRENT);

      // Set docStatus to FINAL if businessStatus is 'closed', else PRELIMINARY
      if (coordinationTask.hasBusinessStatus() &&
              coordinationTask.getBusinessStatus().hasCoding() &&
              coordinationTask.getBusinessStatus().getCodingFirstRep().hasCode() &&
              "closed".equals(coordinationTask.getBusinessStatus().getCodingFirstRep().getCode())) {
        docRef.setDocStatus(DocumentReference.ReferredDocumentStatus.FINAL);
      } else {
        docRef.setDocStatus(DocumentReference.ReferredDocumentStatus.PRELIMINARY);
      }

      docRef.setType(new CodeableConcept().addCoding(
              new Coding("http://hl7.org/fhir/us/davinci-pct/CodeSystem/PCTDocumentTypeTemporaryTrialUse", "gfe-packet", null)
      ));
      docRef.addCategory(
              new CodeableConcept().addCoding(
                      new Coding("http://hl7.org/fhir/us/davinci-pct/CodeSystem/PCTDocumentCategoryTemporaryTrialUse", "estimate", null)
              )
      );

      // Add requestInitiationTime extension. Use the value from the Coordination Task's extension if present
      Extension taskRequestInitiationExt = coordinationTask.getExtensionByUrl("http://hl7.org/fhir/us/davinci-pct/StructureDefinition/requestInitiationTime");
      if (taskRequestInitiationExt != null && taskRequestInitiationExt.getValue() != null) {
        Extension requestInitiationTime = new Extension("http://hl7.org/fhir/us/davinci-pct/StructureDefinition/requestInitiationTime");
        requestInitiationTime.setValue(taskRequestInitiationExt.getValue());
        docRef.addExtension(requestInitiationTime);
      }

      Set<String> uniqueAuthorRefs = new HashSet<>();
      boolean isSubjectSet = false;

      for (Bundle.BundleEntryComponent packetEntry : gfePacket.getEntry()) {
        Resource resource = packetEntry.getResource();
        // Copy gfeServiceLinkingInfo extension from GFE Composition if present
        if (resource instanceof Composition) {
          Composition comp = (Composition) resource;
          Extension gfeServiceLinkingInfo = comp.getExtensionByUrl("http://hl7.org/fhir/us/davinci-pct/StructureDefinition/gfeServiceLinkingInfo");
          if (gfeServiceLinkingInfo != null) {
            docRef.addExtension(gfeServiceLinkingInfo.copy());
          }
        } else if (resource instanceof Patient && !isSubjectSet) {
          docRef.setSubject(new Reference("Patient/" + resource.getIdElement().getIdPart()));
          isSubjectSet = true;
        }
        else if (isGFEBundle(resource))  {
          Bundle bundle = (Bundle) resource;
          boolean providerResourceFound = false;
          String providerRef = "";
          for (Bundle.BundleEntryComponent entry : bundle.getEntry()) {
            Resource entryResource = entry.getResource();
            if( providerResourceFound && !providerRef.isEmpty()){
              break; // Stop processing this bundle if provider author is added and saved already
            }
            if (entryResource instanceof Claim && !isGFESummary((Claim) entryResource) && providerRef.isEmpty()) {
              Claim gfeClaim = (Claim) entryResource;
              if (gfeClaim.hasInsurer() && gfeClaim.getInsurer().hasReference()) {
                String insurerRef = gfeClaim.getInsurer().getReference();
                if (uniqueAuthorRefs.add(insurerRef)) { // Add author(payer) only if not already added
                  logger.info("DocRef Payer Author added: {}", insurerRef);
                  docRef.addAuthor(new Reference(insurerRef));
                }
              }
              if (gfeClaim.hasProvider() && gfeClaim.getProvider().hasReference()) {
                providerRef = gfeClaim.getProvider().getReference();
                if (uniqueAuthorRefs.add(providerRef)) { // Add author only if not already added
                  logger.info("DocRef Provider Author added: {}", providerRef);
                  docRef.addAuthor(new Reference(providerRef));
                }
              }
            } else if ((entryResource instanceof Practitioner || entryResource instanceof Organization) &&
                          entryResource.hasIdElement() &&
                          (("Practitioner/" + entryResource.getIdElement().getIdPart()).equals(providerRef) ||
                            ("Organization/" + entryResource.getIdElement().getIdPart()).equals(providerRef)
                          )) {
              providerResourceFound = true;
              // Saving the provider resource (Practitioner or Organization) that is the author of the GFE Bundle for Doc reference
              saveResource(entryResource, theRequestDetails);
            }
          }
        }
      }

      // Set date
      docRef.setDate(new Date());

      // Add content (Attachment with AEOB packet URL)
      DocumentReference.DocumentReferenceContentComponent content = new DocumentReference.DocumentReferenceContentComponent();
      Attachment attachment = new Attachment();
      attachment.setContentType("application/fhir+json");
      logger.info("DocumentReference Attachment Url " + theRequestDetails.getFhirServerBase() + "/Bundle/" + gfePacket.getIdElement().getIdPart());
      attachment.setUrl(theRequestDetails.getFhirServerBase() + "/Bundle/" + gfePacket.getIdElement().getIdPart());
      content.setAttachment(attachment);
      content.setFormat(new Coding()
              .setSystem("http://hl7.org/fhir/us/davinci-pct/CodeSystem/PCTDocumentTypeTemporaryTrialUse")
              .setCode("pct-gfe-packet"));
      docRef.addContent(content);

    } catch (Exception e) {
      logger.info("Error creating GFE DocumentReference: " + e.getMessage());
      e.printStackTrace();
    }
    return docRef;
  }

  /**
   * Create a single GFE Composition resource referencing all GFE Bundles in the packetBundle.
   */
  private void addGFECompositionToPacketBundle(Bundle gfePacket, Task coordinationTask,  RequestDetails theRequestDetails){
    Composition gfeComposition = new Composition();
    gfeComposition.setId("PCT-GFE-Composition-"+coordinationTask.getIdElement().getIdPart());
    gfeComposition.getMeta().addProfile(PCT_GFE_COMPOSITION_PROFILE);

    // Add extensions ( gfeServiceLinkingInfo and requestOriginationType)
    addGfeCompositionExtensions(gfeComposition, coordinationTask);
    // Add identifier
    gfeComposition.setIdentifier(new Identifier()
            .setSystem("http://www.example.org/identifiers/composition")
            .setValue("019283476"+coordinationTask.getIdElement().getIdPart())
    );
    gfeComposition.setStatus(Composition.CompositionStatus.FINAL);
    gfeComposition.setType(new CodeableConcept().addCoding(
            new Coding()
                    .setSystem("http://hl7.org/fhir/us/davinci-pct/CodeSystem/PCTDocumentTypeTemporaryTrialUse")
                    .setCode("gfe-packet")
                    .setDisplay("GFE Packet")
    ));
    gfeComposition.addCategory(new CodeableConcept().addCoding(
            new Coding()
                    .setSystem("http://hl7.org/fhir/us/davinci-pct/CodeSystem/PCTDocumentTypeTemporaryTrialUse")
                    .setCode("estimate")
    ));

    // Find all GFE Bundles in the gfePacket and reference them in the Composition
    Set<String> uniqueAuthors = new HashSet<>();
    boolean subjectSet = false;
    for (Bundle.BundleEntryComponent entry : gfePacket.getEntry()) {
      Resource resource = entry.getResource();
      if (isGFEBundle(resource)) {
        // Add a section for each GFE in the bundle
        Bundle gfeBundle = (Bundle) resource;
        Composition.SectionComponent gfeBundleSection = buildGfeSectionAndAuthor(gfeBundle, uniqueAuthors, gfeComposition);
        gfeComposition.addSection(gfeBundleSection);
      } else if (!subjectSet && resource instanceof Patient) {
        Patient patient = (Patient) resource;
        gfeComposition.setSubject(new Reference(patient.getIdElement().getResourceType() + "/" + patient.getIdElement().getIdPart()));
        subjectSet = true;
      }
    }
    gfeComposition.setDate(gfePacket.getTimestamp()); // Composition editing time ?

    // Add title
    gfeComposition.setTitle("GFE Composition for " + (gfeComposition.getSubject() != null ? gfeComposition.getSubject().getReference() : "Unknown Subject"));

    // Save/Update the GFE Composition
    //theCompositionDao.update(gfeComposition, theRequestDetails);

    // Add Composition as first entry in packetBundle
    Bundle.BundleEntryComponent compositionEntry = new Bundle.BundleEntryComponent();
    compositionEntry.setId(gfeComposition.getIdElement().getIdPart());
    compositionEntry.setFullUrl("http://example.org/fhir/Composition/" + gfeComposition.getIdElement().getIdPart());
    compositionEntry.setResource(gfeComposition);
    gfePacket.getEntry().add(0, compositionEntry);

  }

  private void addGfeCompositionExtensions(Composition gfeComposition, Task coordinationTask) {
    Extension linkingInfo = new Extension();
    linkingInfo.setUrl("http://hl7.org/fhir/us/davinci-pct/StructureDefinition/gfeServiceLinkingInfo");
    Extension linkingIdentifierExt = new Extension();
    linkingIdentifierExt.setUrl("linkingIdentifier");
    linkingIdentifierExt.setValue(new Identifier()
        .setSystem("http://example.org/Claim/identifiers")
        .setValue("223452-2342-2435-008002"));
    linkingInfo.addExtension(linkingIdentifierExt);

    // plannedPeriodOfService sub-extension from coordinationTask extension
    Extension taskPlannedPeriodExt = coordinationTask.getExtensionByUrl("http://hl7.org/fhir/us/davinci-pct/StructureDefinition/plannedServicePeriod");
    if (taskPlannedPeriodExt != null && taskPlannedPeriodExt.getValue() != null) {
      Extension plannedPeriodExt = new Extension();
      plannedPeriodExt.setUrl("plannedPeriodOfService");
      plannedPeriodExt.setValue(taskPlannedPeriodExt.getValue());
      linkingInfo.addExtension(plannedPeriodExt);
    }
    gfeComposition.addExtension(linkingInfo);

    Extension requestOrigType = new Extension()
            .setUrl("http://hl7.org/fhir/us/davinci-pct/StructureDefinition/requestOriginationType")
            .setValue(new CodeableConcept().addCoding(
                    new Coding()
                            .setSystem("http://hl7.org/fhir/us/davinci-pct/CodeSystem/PCTGFERequestTypeCSTemporaryTrialUse")
                            .setCode("nonscheduled-request")
            ));
    gfeComposition.addExtension(requestOrigType);
  }

  private Composition.SectionComponent buildGfeSectionAndAuthor(Bundle gfeBundle, Set <String> uniqueAuthors, Composition gfeComposition) {
    Composition.SectionComponent section = createSection("gfe-section", null, gfeBundle);

    // Add author(provider that submitted) from the GFE bundle to the section
    for (Bundle.BundleEntryComponent gfeBundleEntry : gfeBundle.getEntry()) {
      Resource gfeBundleEntryResource = gfeBundleEntry.getResource();
      if (gfeBundleEntryResource instanceof Claim && !isGFESummary((Claim)gfeBundleEntryResource)) { //Associated GFE author (GFE Contributor)
        Claim gfeClaim = (Claim) gfeBundleEntryResource;
        if (gfeClaim.hasProvider() && gfeClaim.getProvider().hasReference()) {
          String providerRef = gfeClaim.getProvider().getReference();
          section.addAuthor(new Reference(providerRef));
          if (uniqueAuthors.add(providerRef)) { // Add author only if not already added
            gfeComposition.addAuthor(new Reference(providerRef));
          }
          break;
        }
      }
    }
    return section;
  }

  private Composition.SectionComponent createSection(String code, String display, Resource resource) {
    Composition.SectionComponent section = new Composition.SectionComponent();
    section.setCode(new CodeableConcept(new Coding(
            "http://hl7.org/fhir/us/davinci-pct/CodeSystem/PCTDocumentSection",
            code, display)));
    section.addEntry(new Reference("Bundle/" + resource.getIdElement().getIdPart()));
    return section;
  }

  public boolean isGFESummary(Claim claim) {
    if (claim.hasMeta() && claim.getMeta().hasProfile() && claim.getMeta().getProfile().get(0).getValue().equals(PCT_GFE_SUMMARY_PROFILE)) {
      return true;
    }
    return false;
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

  /**
   * Returns true if the resource is a Bundle with the GFE Bundle profile and not the Missing Bundle profile.
   */
  private boolean isGFEBundle(Resource resource) {
    return resource instanceof Bundle
            && resource.hasMeta()
            && resource.getMeta().hasProfile()
            && resource.getMeta().hasProfile(PCT_GFE_BUNDLE_PROFILE)
            && !resource.getMeta().hasProfile(PCT_GFE_MISSING_BUNDLE_PROFILE);
  }
}
