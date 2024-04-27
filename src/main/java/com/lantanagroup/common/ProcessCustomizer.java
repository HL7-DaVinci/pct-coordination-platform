package com.lantanagroup.common;

import java.io.InputStream;
import java.util.List;
import java.util.ArrayList;

import org.hl7.fhir.instance.model.api.IBaseConformance;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.Resource;
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

  public ProcessCustomizer(FhirContext fhirContext, DaoRegistry theDaoRegistry, String key) {
    this.fhirContext = fhirContext;
    this.theDaoRegistry = theDaoRegistry;
    this.key = key;

    theTaskDao = this.theDaoRegistry.getResourceDao(Task.class);
  }

  
  // If task is already completed or rejected The task may not be updated (Perhaps could be handled through specialized permissions?)
  @Hook(Pointcut.SERVER_INCOMING_REQUEST_PRE_HANDLER_SELECTED)
  public void customizeProcessIncomingRequest(RequestDetails theRequestDetails, HttpServletRequest theServletRequest) {
    if(theRequestDetails != null)
    {
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
            if(theRequestDetails.getResourceName().equals("Task"))
            {
                try{
                    // Check the Task type to see if it is a Coordination Task and if the status is cancelled, completed, or entered in error (any others?), change contributors tasks where status is not already completed or rejected (others?)
                    Task requestTask = (Task)theRequestDetails.getResource();
                    if(requestTask.hasCode() && requestTask.getCode().hasCoding("http://hl7.org/fhir/us/davinci-pct/CodeSystem/PCTGFERequestTaskCSTemporaryTrialUse", "gfe-coordination-task"))
                    {
                        // Not in 2.0.0 ballot version, but contributor tasks should only be updated if coordination task status is (cancelled, on-hold, failed, completed, or entered in error)
                        if(requestTask.hasStatus() && (requestTask.getStatus() == Task.TaskStatus.CANCELLED || 
                                                        requestTask.getStatus() == Task.TaskStatus.FAILED || 
                                                        requestTask.getStatus() == Task.TaskStatus.COMPLETED ||
                                                        requestTask.getStatus() == Task.TaskStatus.ENTEREDINERROR))
                        {
                            
                            List<Task> contributorTasks = getContributorTasks(requestTask, theRequestDetails);
                            contributorTasks.forEach(task -> {
                                //cancelled, rejected, entered-in-error, failed, or completed`
                                if(task.hasStatus() && (task.getStatus() != Task.TaskStatus.CANCELLED &&
                                                        task.getStatus() != Task.TaskStatus.REJECTED &&
                                                        task.getStatus() != Task.TaskStatus.ENTEREDINERROR &&
                                                        task.getStatus() != Task.TaskStatus.FAILED &&
                                                        task.getStatus() != Task.TaskStatus.COMPLETED))
                                {
                                    task.setStatus(requestTask.getStatus());
                                    // TODO Should this be update (Put) or patch ?
                                    theTaskDao.update(task, theRequestDetails);
                                }
                            });
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

}
