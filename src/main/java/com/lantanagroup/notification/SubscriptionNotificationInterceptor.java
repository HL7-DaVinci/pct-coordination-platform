package com.lantanagroup.notification;

import org.hl7.fhir.r4.model.Subscription;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.api.RestOperationTypeEnum;
import ca.uhn.fhir.interceptor.api.Pointcut;
import ca.uhn.fhir.interceptor.api.Hook;
import ca.uhn.fhir.interceptor.api.Interceptor;
import ca.uhn.fhir.jpa.topic.SubscriptionTopicDispatcher;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.Task;
import org.hl7.fhir.r4.model.Bundle;
import ca.uhn.fhir.jpa.topic.SubscriptionTopicDispatchRequest;
import ca.uhn.fhir.jpa.searchparam.matcher.SearchParamMatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.Collections;
import org.springframework.beans.factory.annotation.Autowired;
import org.hl7.fhir.r4.model.Bundle.BundleEntryComponent;
import org.hl7.fhir.r4.model.Bundle.BundleType;
import org.hl7.fhir.r4.model.DocumentReference;
import ca.uhn.fhir.rest.server.exceptions.UnprocessableEntityException;

@Interceptor
public class SubscriptionNotificationInterceptor {
    @Hook(Pointcut.STORAGE_PRECOMMIT_RESOURCE_CREATED)
    public void onResourceCreated(IBaseResource resource, RequestDetails requestDetails) {
        logger.info("STORAGE_PRECOMMIT_RESOURCE_CREATED for resource type={}", resource.getClass().getSimpleName());
        if (resource instanceof DocumentReference) {
            String[] docRefTopics = {
                "http://hl7.org/fhir/us/davinci-pct/SubscriptionTopic/davinci-pct-gfe-available-author-notification",
                "http://hl7.org/fhir/us/davinci-pct/SubscriptionTopic/davinci-pct-gfe-available-subject-notification"
            };
            for (String topicUrl : docRefTopics) {
                dispatchResourceNotification(resource, RestOperationTypeEnum.CREATE, false, topicUrl);
            }
        } else if (resource instanceof Task) {
            String topicUrl = "http://hl7.org/fhir/us/davinci-pct/SubscriptionTopic/davinci-pct-gfe-coordination-task-notification";
            dispatchResourceNotification(resource, RestOperationTypeEnum.CREATE, false, topicUrl);
        }
    }

    @Hook(Pointcut.STORAGE_PRECOMMIT_RESOURCE_UPDATED)
    public void onResourceUpdated(IBaseResource resource, RequestDetails requestDetails) {
        logger.info("STORAGE_PRECOMMIT_RESOURCE_UPDATED for resource type={}", resource.getClass().getSimpleName());
        if (resource instanceof DocumentReference) {
            String[] docRefTopics = {
                "http://hl7.org/fhir/us/davinci-pct/SubscriptionTopic/davinci-pct-gfe-available-author-notification",
                "http://hl7.org/fhir/us/davinci-pct/SubscriptionTopic/davinci-pct-gfe-available-subject-notification"
            };
            for (String topicUrl : docRefTopics) {
                dispatchResourceNotification(resource, RestOperationTypeEnum.UPDATE, false, topicUrl);
            }
        } else if (resource instanceof Task) {
            String topicUrl = "http://hl7.org/fhir/us/davinci-pct/SubscriptionTopic/davinci-pct-gfe-coordination-task-notification";
            dispatchResourceNotification(resource, RestOperationTypeEnum.UPDATE, false, topicUrl);
        }
    }

    @Hook(Pointcut.STORAGE_PRECOMMIT_RESOURCE_DELETED)
    public void onResourceDeleted(IBaseResource resource, RequestDetails requestDetails) {
        logger.info("STORAGE_PRECOMMIT_RESOURCE_DELETED for resource type={}", resource.getClass().getSimpleName());
        if (resource instanceof Task) {
            String topicUrl = "http://hl7.org/fhir/us/davinci-pct/SubscriptionTopic/davinci-pct-gfe-coordination-task-notification";
            dispatchResourceNotification(resource, RestOperationTypeEnum.DELETE, false, topicUrl);
        }
    }

    private static final Logger logger = LoggerFactory.getLogger(SubscriptionNotificationInterceptor.class);

    private final SubscriptionTopicDispatcher subscriptionTopicDispatcher;

    public SubscriptionNotificationInterceptor(SubscriptionTopicDispatcher subscriptionTopicDispatcher) {
        this.subscriptionTopicDispatcher = subscriptionTopicDispatcher;
    }

    @Autowired
    SearchParamMatcher searchParamMatcher;

    @Hook(Pointcut.SERVER_INCOMING_REQUEST_PRE_HANDLED)
    public void rejectUnsupportedHasFilter(RequestDetails requestDetails) {
        if (requestDetails == null) return;
        IBaseResource resource = requestDetails.getResource();
        RestOperationTypeEnum opType = requestDetails.getRestOperationType();
        if (resource instanceof Subscription && (opType == RestOperationTypeEnum.CREATE || opType == RestOperationTypeEnum.UPDATE)) {
            Subscription sub = (Subscription) resource;
            // Extract filter string from _criteria extension (if present)
            String filterString = null;
            if (sub.hasCriteriaElement() && sub.getCriteriaElement().hasExtension()) {
                    filterString = sub.getCriteriaElement().getExtension().stream()
                    .filter(ext -> ext.getUrl().contains("backport-filter-criteria"))
                    .map(ext -> ext.getValueAsPrimitive() != null ? ext.getValueAsPrimitive().getValueAsString() : null)
                    .findFirst().orElse(null);
            }
            if (filterString != null && filterString.contains("_has:Task:part-of:owner")) {
                logger.info("Rejecting Subscription. Unsupported filter _has:Task:part-of:owner filter");
                throw new UnprocessableEntityException("Not implemented: _has:Task:part-of:owner filter is not supported.");
            }
            logger.info("Subscription resource passed unsupported filter check");
        }
    }

    private void dispatchResourceNotification(IBaseResource resource, RestOperationTypeEnum opType, boolean isBundle, String topicUrl) {
        GenericResourceFilterMatcher matcher = new GenericResourceFilterMatcher(searchParamMatcher);
        SubscriptionTopicDispatchRequest request = new SubscriptionTopicDispatchRequest(
            topicUrl,
            Collections.singletonList(resource),
            matcher,
            opType,
            null,
            null,
            null
        );
        String id = resource.getIdElement() != null ? resource.getIdElement().getIdPart() : "unknown";
        logger.info("SubscriptionTopicDispatchRequest sent for {} with id: {}, operation: {}, isBundle: {}",
            resource.getClass().getSimpleName(), id, opType, isBundle);
        subscriptionTopicDispatcher.dispatch(request);
    }
}
