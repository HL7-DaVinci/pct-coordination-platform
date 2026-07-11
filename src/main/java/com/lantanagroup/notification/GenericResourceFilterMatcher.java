package com.lantanagroup.notification;

import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.api.dao.IFhirResourceDao;
import ca.uhn.fhir.jpa.searchparam.SearchParameterMap;
import ca.uhn.fhir.jpa.topic.filter.ISubscriptionTopicFilterMatcher;
import ca.uhn.fhir.jpa.subscription.model.CanonicalTopicSubscriptionFilter;
import ca.uhn.fhir.jpa.searchparam.matcher.InMemoryMatchResult;
import ca.uhn.fhir.jpa.searchparam.matcher.SearchParamMatcher;
import ca.uhn.fhir.rest.api.SummaryEnum;
import ca.uhn.fhir.rest.api.server.IBundleProvider;
import ca.uhn.fhir.rest.param.ReferenceParam;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.Task;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class GenericResourceFilterMatcher implements ISubscriptionTopicFilterMatcher {

    private static final Logger logger = LoggerFactory.getLogger(GenericResourceFilterMatcher.class);

    private static final String FILTER_HAS_TASK_PART_OF_OWNER = "_has:Task:part-of:owner";
    private static final String PARAM_REQUESTER = "requester";
    private static final String PARAM_OWNER = "owner";
    private static final String PARAM_STATUS = "status";

    private final DaoRegistry daoRegistry;

    private final SearchParamMatcher mySearchParamMatcher;

    public GenericResourceFilterMatcher(DaoRegistry daoRegistry, SearchParamMatcher searchParamMatcher) {
        this.daoRegistry = daoRegistry;
        this.mySearchParamMatcher = searchParamMatcher;
    }

    @Override
    public InMemoryMatchResult match(CanonicalTopicSubscriptionFilter theCanonicalTopicSubscriptionFilter, IBaseResource theIBaseResource) {
        String paramName = theCanonicalTopicSubscriptionFilter.getFilterParameter();
        String paramValue = theCanonicalTopicSubscriptionFilter.getValue();

        if (paramName == null || paramValue == null) {
            logger.warn("GenericResourceFilterMatcher received null filter parameter or value; returning no-match");
            return InMemoryMatchResult.noMatch();
        }

        String resourceType = theIBaseResource.fhirType();
        String resourceId = theIBaseResource.getIdElement() != null ? theIBaseResource.getIdElement().getIdPart() : "unknown";
        logger.info("GenericResourceFilterMatcher.match invoked with: resourceType={}, resourceId={}, filter={}={}", resourceType, resourceId, paramName, paramValue);

        // Custom handler for `_has:Task:part-of:owner`
        if (FILTER_HAS_TASK_PART_OF_OWNER.equals(paramName)) {
            if (!(theIBaseResource instanceof Task)) {
                logger.info("Custom part-of/owner matcher skipped for non-Task resourceType={}", resourceType);
                InMemoryMatchResult result = InMemoryMatchResult.noMatch();
                logMatchResult(theCanonicalTopicSubscriptionFilter, theIBaseResource, result);
                return result;
            }

            String currentTaskId = theIBaseResource.getIdElement().getIdPart();
            boolean isMatch = hasChildTaskOwnedBy(currentTaskId, paramValue);
            InMemoryMatchResult result = InMemoryMatchResult.fromBoolean(isMatch);
            logger.info("Custom matcher evaluated {} for criteria {}={}", isMatch ? "MATCH" : "NO MATCH", paramName, paramValue);
            logMatchResult(theCanonicalTopicSubscriptionFilter, theIBaseResource, result);
            return result;
        }

        // Custom handler for `requester` reference parameter (added for multiple combined criteria issue)
        if (PARAM_REQUESTER.equals(paramName) && theIBaseResource instanceof Task) {
            Task task = (Task) theIBaseResource;
            boolean isMatch = matchTaskReference(task.getRequester(), paramValue);
            InMemoryMatchResult result = InMemoryMatchResult.fromBoolean(isMatch);
            logger.info("Custom requester matcher evaluated {} for criteria {}={}", isMatch ? "MATCH" : "NO MATCH", paramName, paramValue);
            logMatchResult(theCanonicalTopicSubscriptionFilter, theIBaseResource, result);
            return result;
        }

        // Custom handler for `owner` reference parameter (added for  multiple combined criteria issue)
        if (PARAM_OWNER.equals(paramName) && theIBaseResource instanceof Task) {
            Task task = (Task) theIBaseResource;
            boolean isMatch = matchTaskReference(task.getOwner(), paramValue);
            InMemoryMatchResult result = InMemoryMatchResult.fromBoolean(isMatch);
            logger.info("Custom owner matcher evaluated {} for criteria {}={}", isMatch ? "MATCH" : "NO MATCH", paramName, paramValue);
            logMatchResult(theCanonicalTopicSubscriptionFilter, theIBaseResource, result);
            return result;
        }

        // Delegate to HAPI's built-in in-memory matcher for all other filters
        String criteria = paramName + "=" + paramValue;
        logger.info("Delegating filter to SearchParamMatcher: {}", criteria);
        InMemoryMatchResult result = mySearchParamMatcher.match(criteria, theIBaseResource, null);
        logMatchResult(theCanonicalTopicSubscriptionFilter, theIBaseResource, result);
        return result;
    }

    private boolean hasChildTaskOwnedBy(String parentId, String ownerId) {
        IFhirResourceDao<Task> taskDao = daoRegistry.getResourceDao(Task.class);

        SearchParameterMap map = new SearchParameterMap()
                .add(Task.SP_PART_OF, new ReferenceParam("Task/" + parentId))
                .add(Task.SP_OWNER, new ReferenceParam(ownerId));
        map.setCount(1);
        map.setSummaryMode(SummaryEnum.COUNT);

        logger.info("Executing Task search: part-of=Task/{} AND owner={}", parentId, ownerId);
        IBundleProvider results = taskDao.search(map);
        if (results == null) {
            logger.info("Custom matcher search returned null results");
            return false;
        }
        Integer matchCount = results.size();
        logger.info("Custom matcher search count={}", matchCount);
        return matchCount != null && matchCount > 0;
    }

    /**
     * Matches a task reference against a filter value.
     * Handles various reference formats like:
     * - Organization/Submitter-Org-1
     * - Organization/123
     * - Practitioner/abc
     * - https://example.com/Organization/123
     */
    private boolean matchTaskReference(org.hl7.fhir.r4.model.Reference taskRef, String filterValue) {
        if (taskRef == null || !taskRef.hasReference()) {
            logger.info("Task reference is null or empty, no match against filterValue={}", filterValue);
            return false;
        }

        String taskRefValue = taskRef.getReference();
        logger.info("Matching task reference '{}' against filterValue '{}'", taskRefValue, filterValue);

        // Normalize both values for comparison
        String normalizedTaskRef = normalizeReference(taskRefValue);
        String normalizedFilterValue = normalizeReference(filterValue);

        boolean isMatch = normalizedTaskRef.equals(normalizedFilterValue);
        logger.info("Reference comparison: '{}' vs '{}' = {}", normalizedTaskRef, normalizedFilterValue, isMatch);
        return isMatch;
    }

    /**
     * Normalize references to a common format for comparison.
     * Strips URLs and focuses on the resource type and ID.
     * Examples:
     * - "Organization/123" -> "Organization/123"
     * - "https://example.com/Organization/123" -> "Organization/123"
     * - "Organization/Submitter-Org-1" -> "Organization/Submitter-Org-1"
     */
    private String normalizeReference(String reference) {
        if (reference == null) {
            return "";
        }

        // Remove trailing slashes
        reference = reference.replaceAll("/$", "");

        // Extract just the resource type and ID if it's a full URL
        if (reference.contains("/")) {
            String[] parts = reference.split("/");
            if (parts.length >= 2) {
                // Get the last two parts (resource type and ID)
                return parts[parts.length - 2] + "/" + parts[parts.length - 1];
            }
        }

        return reference;
    }


    private void logMatchResult(CanonicalTopicSubscriptionFilter filter, IBaseResource resource, InMemoryMatchResult result) {
        String resourceId = (resource.getIdElement() != null)
                ? resource.getIdElement().toUnqualifiedVersionless().getValue()
                : "unknown";

        boolean isMatch = result.matched();
        String unsupportedReason = result.getUnsupportedReason();
        boolean isSupported = (unsupportedReason == null);

        String op = (filter.getComparator() != null) ? (" " + filter.getComparator().toCode() + " ") : "=";
        String filterDescription = filter.getFilterParameter() + op + filter.getValue();

        String taskDetails = "";
        if (resource instanceof Task) {
            Task task = (Task) resource;
            StringBuilder details = new StringBuilder();

            if (task.getStatus() != null) {
                details.append("[Status: ").append(task.getStatus().toCode()).append("]");
            }

            if (task.hasRequester() && task.getRequester().hasReference()) {
                details.append("[Requester: ").append(task.getRequester().getReference()).append("]");
            }

            if (task.hasOwner() && task.getOwner().hasReference()) {
                details.append("[Owner: ").append(task.getOwner().getReference()).append("]");
            }

            if (task.hasBusinessStatus() && task.getBusinessStatus().hasCoding()
                    && task.getBusinessStatus().getCodingFirstRep().hasCode()) {
                String code = task.getBusinessStatus().getCodingFirstRep().getCode();
                String display = task.getBusinessStatus().getCodingFirstRep().getDisplay();
                details.append("[BusinessStatus: ").append(code);
                if (display != null && !display.isEmpty()) {
                    details.append(" (").append(display).append(")");
                }
                details.append("]");
            }

            taskDetails = details.toString();
        }

        if (isSupported) {
            logger.info("PCT Matcher: [ID: {}] [Filter: {}] [Matched: {}] {}",
                    resourceId, filterDescription, isMatch, taskDetails);
        } else {
            logger.warn("PCT Matcher: [ID: {}] [Filter: {}] [Matched: {}] [Unsupported: {}] {}",
                    resourceId, filterDescription, isMatch, unsupportedReason, taskDetails);
        }
    }
}
