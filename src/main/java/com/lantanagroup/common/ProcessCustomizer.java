package com.lantanagroup.common;

import java.util.List;
import java.util.ArrayList;

import org.hl7.fhir.instance.model.api.IBaseOperationOutcome;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;
import ca.uhn.fhir.rest.api.RestOperationTypeEnum;
import jakarta.servlet.http.HttpServletRequest;

import ca.uhn.fhir.rest.server.exceptions.ForbiddenOperationException;
import ca.uhn.fhir.jpa.api.dao.IFhirResourceDao;
import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.rest.api.RequestTypeEnum;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.interceptor.api.Hook;
import ca.uhn.fhir.interceptor.api.Interceptor;
import ca.uhn.fhir.interceptor.api.Pointcut;
import ca.uhn.fhir.parser.IParser;
import org.hl7.fhir.r4.model.*;

import ca.uhn.fhir.jpa.searchparam.SearchParameterMap;
import ca.uhn.fhir.rest.param.*;
import ca.uhn.fhir.rest.api.server.IBundleProvider;

@Interceptor
public class ProcessCustomizer {

  private static final org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(ProcessCustomizer.class);


  protected FhirContext fhirContext;
  protected DaoRegistry theDaoRegistry;
  protected IFhirResourceDao<Task> theTaskDao;
  protected String key;
  private boolean dataLoaded;
  private IParser jparser;

  public ProcessCustomizer(FhirContext fhirContext, DaoRegistry theDaoRegistry, String key) {
    dataLoaded = false;
    this.fhirContext = fhirContext;
    this.theDaoRegistry = theDaoRegistry;
    this.key = key;

    theTaskDao = this.theDaoRegistry.getResourceDao(Task.class);

    jparser = fhirContext.newJsonParser();
    jparser.setPrettyPrint(true);
  }

  
  // If task is already completed or rejected The task may not be updated (Perhaps could be handled through specialized permissions?)
  @Hook(Pointcut.SERVER_INCOMING_REQUEST_PRE_HANDLER_SELECTED)
  public void customizeProcessIncomingRequest(RequestDetails theRequestDetails, HttpServletRequest theServletRequest) {
    if(theRequestDetails != null)
    {
        if (!dataLoaded) {
            dataLoaded = true;
            logger.info("First request made to Server");
            logger.info("Loading all data");

            for (String filename : getServerResources("ri_resources", "Organization-*.json")) {
                try {
                    System.out.println("Uploading resource " + filename);
                    theDaoRegistry.getResourceDao(Organization.class).update(
                            jparser.parseResource(Organization.class, util.loadResource(filename)), theRequestDetails);
                } catch (Exception e) {
                    System.out.println("Failure to update the Organization: " + e.getMessage());
                }
            }

            for (String filename : getServerResources("ri_resources", "Practitioner-*.json")) {
                try {
                    System.out.println("Uploading resource " + filename);
                    theDaoRegistry.getResourceDao(Practitioner.class).update(
                            jparser.parseResource(Practitioner.class, util.loadResource(filename)), theRequestDetails);
                } catch (Exception e) {
                    System.out.println("Failure to update the Practitioner: " + e.getMessage());
                }
            }
            for (String filename : getServerResources("ri_resources", "PractitionerRole-*.json")) {
                try {
                    System.out.println("Uploading resource " + filename);
                    theDaoRegistry.getResourceDao(PractitionerRole.class).update(
                            jparser.parseResource(PractitionerRole.class, util.loadResource(filename)),
                            theRequestDetails);
                } catch (Exception e) {
                    System.out.println("Failure to update the PractitionerRole: " + e.getMessage());
                }
            }
        }
        // if overriding requirement rules, just skip
        
        if(theServletRequest != null && theServletRequest.getHeader("X-Override") != null && theServletRequest.getHeader("X-Override").equalsIgnoreCase("true"))
        {
            return;
        }
        if(theRequestDetails.getRequestType() == RequestTypeEnum.POST || theRequestDetails.getRequestType() == RequestTypeEnum.PUT || theRequestDetails.getRequestType() == RequestTypeEnum.PATCH)
        {
            if(theRequestDetails.getResource() != null && theRequestDetails.getResourceName().equals("Task"))
            {
                Task existingTask = null;
                try{
                    // Get existing task to see if the status is rejected or completed
                    existingTask = theTaskDao.read(theRequestDetails.getId(), theRequestDetails);
                    
                    
                }
                catch(Exception e)
                {
                    // Unable to retrieve existing resource, allow to continue to process as normal
                    logger.error("Unable to retrieve existing Task resource or otherwise check it's status when preprocessing request, allow to continue to process as normal", e);
                    
                }
                if(existingTask != null)
                {
                    if(existingTask.hasStatus() && (existingTask.getStatus() == Task.TaskStatus.CANCELLED || 
                                                    existingTask.getStatus() == Task.TaskStatus.COMPLETED || 
                                                    existingTask.getStatus() == Task.TaskStatus.REJECTED ||
                                                    existingTask.getStatus() == Task.TaskStatus.ENTEREDINERROR))
                    {
                        throw new ForbiddenOperationException("Task may not be updated. Existing Tasks with the status '" + existingTask.getStatus().toCode() + "' cannot be modified.");
                    }
                }
            }
        }
    }
  }

  @Hook(Pointcut.SERVER_PROCESSING_COMPLETED_NORMALLY)
  public void customizeProcessCompletedNormally(RequestDetails theRequestDetails) {

    /* Task Status When deemed appropriate, the GFE Coordination Requester SHALL close the Task by updating the status to completed or cancelled (the choice of which depends on the intent of the requester) When the status of the 
 *    GFE Coordination Task is updated, the Coordination Platform SHALL update the associated GFE Contributor Task statuses to match, except for those that have a status of cancelled, rejected, entered-in-error, failed, or completed`.
 * Need an interceptor for the SERVER_PROCESSING_COMPLETED_NORMALLY PointCut looking to see if it was a Task where the status was updated (possible?) Or just make sure all contributor tasks are updated appropriately (Catch any exceptions)
 * */
    if(theRequestDetails != null)
    {
        if(theRequestDetails.getRequestType() == RequestTypeEnum.POST || theRequestDetails.getRequestType() == RequestTypeEnum.PUT || theRequestDetails.getRequestType() == RequestTypeEnum.PATCH)
        {
            if(theRequestDetails.getResourceName() != null && theRequestDetails.getResourceName().equals("Task"))
            {
                try{
                    // Check the Task type to see if it is a Coordination Task and if the status is cancelled, completed, or entered in error (any others?), change contributors tasks where status is not already completed or rejected (others?)
                    Task requestTask = (Task)theRequestDetails.getResource();
                    if( isCoordinationTask(requestTask))
                    {
                        // Not in 2.0.0 ballot version, but contributor tasks should only be updated if coordination task status is (cancelled, failed, completed, or entered in error)
                        if(requestTask.hasStatus() && (requestTask.getStatus() == Task.TaskStatus.CANCELLED || 
                                                        requestTask.getStatus() == Task.TaskStatus.FAILED || 
                                                        requestTask.getStatus() == Task.TaskStatus.COMPLETED ||
                                                        requestTask.getStatus() == Task.TaskStatus.ENTEREDINERROR))
                        {
                            
                            List<Task> contributorTasks = getContributorTasks(requestTask, theRequestDetails);
                            contributorTasks.forEach(task -> {
                                //not cancelled, rejected, entered-in-error, failed, or completed. i.e.,open contributor tasks
                                if(task.hasStatus() && (task.getStatus() != Task.TaskStatus.CANCELLED &&
                                                        task.getStatus() != Task.TaskStatus.REJECTED &&
                                                        task.getStatus() != Task.TaskStatus.ENTEREDINERROR &&
                                                        task.getStatus() != Task.TaskStatus.FAILED &&
                                                        task.getStatus() != Task.TaskStatus.COMPLETED))
                                {
                                    if( requestTask.getStatus() == Task.TaskStatus.FAILED || requestTask.getStatus() == Task.TaskStatus.COMPLETED){
                                        task.setStatusReason(requestTask.getStatusReason());
                                    }
                                    if( requestTask.getStatus() == Task.TaskStatus.COMPLETED ){
                                        task.setStatus(Task.TaskStatus.FAILED);
                                    }
                                    else{
                                        task.setStatus(requestTask.getStatus());
                                    }
                                    // Update the businessStatus of all associated Contributor Tasks to closed.
                                    task.setBusinessStatus(new CodeableConcept().addCoding(new Coding()
                                            .setSystem("http://hl7.org/fhir/us/davinci-pct/CodeSystem/PCTTaskBusinessStatusCSTemporaryTrialUse")
                                            .setCode("closed")
                                            .setDisplay("Closed")));
                                    // TODO Should this be update (Put) or patch ?
                                    theTaskDao.update(task, theRequestDetails);
                                }
                            });
                        }
                    } else if( isContributorTask(requestTask) ){
                        //when at least one of the associated Contributor Tasks changes to a status of accepted, update coordination Task to INPROGRESS.
                        if(requestTask.hasStatus() && requestTask.getStatus() == Task.TaskStatus.ACCEPTED) {
                            Task coordinationTask = getCoordinationTask(requestTask);
                            if( coordinationTask !=null && coordinationTask.getStatus() == Task.TaskStatus.READY ) {
                                coordinationTask.setStatus(Task.TaskStatus.INPROGRESS);
                                theTaskDao.update(coordinationTask, theRequestDetails);
                            }
                        }
                    }
                }
                catch(Exception e)
                {
                    // unable to make changes to contributors, log an continue
                    logger.error("Unable to process Task status (change contributor task status based on Coordination Task Status), allow to continue to process as normal", e);
                    
                }
                
            }
        }
    }



    
    /*
    String fileName = "CapabilityStatement-" + key + ".json";

    try {

      DefaultResourceLoader resourceLoader = new DefaultResourceLoader();
      Resource resource = resourceLoader.getResource(fileName);
      InputStream inputStream = resource.getInputStream();

      IBaseConformance capabilityStatement = (IBaseConformance) fhirContext.newJsonParser().parseResource(inputStream);

      return capabilityStatement;

    } catch (Exception e) {
      logger.error("Failed to load CapabilityStatment with filename " + fileName + ": " + e.getMessage(), e);
      return null;
    }
    */

  }

    @Hook(Pointcut.SERVER_OUTGOING_FAILURE_OPERATIONOUTCOME)
    public void handleServerFailures(RequestDetails theRequestDetails, IBaseOperationOutcome operationOutcome) {
        // update task status to failed in the event of a system or process issue
        String message = "Unknown Server Error";
        if( operationOutcome != null && "Task".equals(theRequestDetails.getResourceName())){
            OperationOutcome operation = (OperationOutcome)operationOutcome;
            if( operation.hasIssue() ){
                for(OperationOutcome.OperationOutcomeIssueComponent issue : operation.getIssue()){
                    if(issue.hasDiagnostics()){
                        message = issue.getDiagnostics().toString();
                    }
                }
                Task task = null;
                if( theRequestDetails.getId() != null ){
                    task = theTaskDao.read(theRequestDetails.getId());
                }
                if( task != null && (
                        (isCoordinationTask(task) && task.getStatus() == Task.TaskStatus.READY || task.getStatus() == Task.TaskStatus.INPROGRESS)
                        || (isContributorTask(task) && task.getStatus() == Task.TaskStatus.RECEIVED || task.getStatus() == Task.TaskStatus.ACCEPTED || task.getStatus() == Task.TaskStatus.REQUESTED))){
                    task.setStatus(Task.TaskStatus.FAILED);
                    task.setStatusReason(new CodeableConcept());
                    task.getStatusReason().setText(message);
                    task.setBusinessStatus(new CodeableConcept().addCoding(new Coding()
                            .setSystem("http://hl7.org/fhir/us/davinci-pct/CodeSystem/PCTTaskBusinessStatusCSTemporaryTrialUse")
                            .setCode("closed")
                            .setDisplay("Closed")));
                    try{
                        theTaskDao.update(task, theRequestDetails);
                        logger.info("Updated task status to 'failed for task " + task.getId()+" due to processing error");
                    }catch(Exception e){
                        logger.error("Failed to update task " + task.getId(), e.getMessage());
                    }
                }
            }
        }
    }

    @Hook(Pointcut.SERVER_OUTGOING_RESPONSE)
    public void customizeServerOutgoingResponse(RequestDetails theRequestDetails, IBaseResource response) {
        //update the Contributor Task status to received upon first retrieval, either through read or search
        if ( "Task".equals(theRequestDetails.getResourceName()) && theRequestDetails.getRequestType() == RequestTypeEnum.GET && ( theRequestDetails.getRestOperationType()== RestOperationTypeEnum.SEARCH_TYPE ||  theRequestDetails.getRestOperationType()== RestOperationTypeEnum.READ ) ) {
            Task task = getTask(theRequestDetails, response);
            if( task != null && isContributorTask(task) && task.getStatus() == Task.TaskStatus.REQUESTED ) {
                try{
                    task.setStatus(Task.TaskStatus.RECEIVED);
                    theTaskDao.update(task);
                }
                catch (Exception e){
                    logger.error("Failed to update status of Task to RECEIVED ", e);
                }
            }
        }
    }

    private Task getTask(RequestDetails theRequestDetails, IBaseResource response) {
        RestOperationTypeEnum operationType = theRequestDetails.getRestOperationType();
        Task task = null;
        // fetch task
        if( operationType == RestOperationTypeEnum.READ && response instanceof Task ){
            task = (Task) response;
        }else if (operationType == RestOperationTypeEnum.SEARCH_TYPE && response instanceof Bundle){
            Bundle bundle = (Bundle) response;
            if ( theRequestDetails.getParameters() != null && theRequestDetails.getParameters().containsKey("_id") && bundle.getEntry().size() == 1  && bundle.getEntryFirstRep().getResource() instanceof Task ) {
                task = (Task) bundle.getEntryFirstRep().getResource();
            }
        }
        return task;
    }

  public List<Task> getContributorTasks(Task coordinationTask, RequestDetails theRequestDetails)
  {
    List<Task> contributorTasks = new ArrayList<>();
    SearchParameterMap searchMap = new SearchParameterMap();
    searchMap.add(Task.PART_OF.getParamName(), new ReferenceParam(coordinationTask.getId()));
    IBundleProvider taskResults = theTaskDao.search(searchMap, theRequestDetails);
    
		taskResults.getResources(0, taskResults.size())
			.stream().map(Task.class::cast)
			.forEach(task -> contributorTasks.add(task));

    return contributorTasks;
  }

    public Task getCoordinationTask( Task contributorTask )
    {
        Task coordinationTask = null;
        if( contributorTask.getPartOf() == null || contributorTask.getPartOf().isEmpty() ){
            return null;
        }
        Reference reference = contributorTask.getPartOf().get(0);
        if( reference.getReference() != null && !reference.getReference().isEmpty() ) {
            coordinationTask = theTaskDao.read(new IdType(reference.getReference()));
        }
        return coordinationTask;
    }

    private boolean isCoordinationTask(Task task) {
        return (task !=null && task.hasCode() && task.getCode().hasCoding("http://hl7.org/fhir/us/davinci-pct/CodeSystem/PCTGFERequestTaskCSTemporaryTrialUse", "gfe-coordination-task"));
    }

    private boolean isContributorTask(Task task) {
        return (task !=null && task.hasCode() && task.getCode().hasCoding("http://hl7.org/fhir/us/davinci-pct/CodeSystem/PCTGFERequestTaskCSTemporaryTrialUse", "gfe-contributor-task"));
    }

  public List<String> getServerResources(String path, String pattern) {
        List<String> files = new ArrayList<>();

        String localPath = path;
        if (!localPath.substring(localPath.length() - 1, localPath.length() - 1).equals("/")) {
            localPath = localPath + "/";
        }

        try {

            ClassLoader cl = this.getClass().getClassLoader();
            ResourcePatternResolver resolver = new PathMatchingResourcePatternResolver(cl);

            org.springframework.core.io.Resource[] resources = resolver
                    .getResources("classpath*:" + localPath + pattern);

            for (org.springframework.core.io.Resource resource : resources) {
                files.add(localPath + resource.getFilename());
                logger.info(localPath + resource.getFilename());
            }
        } catch (Exception e) {
            logger.info("Error retrieving file names from " + localPath + pattern);
        }

        return files;
    }

}
