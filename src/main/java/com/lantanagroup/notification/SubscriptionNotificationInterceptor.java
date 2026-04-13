package com.lantanagroup.notification;

import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.api.RestOperationTypeEnum;
import ca.uhn.fhir.interceptor.api.Pointcut;
import ca.uhn.fhir.interceptor.api.Hook;
import ca.uhn.fhir.jpa.topic.SubscriptionTopicDispatcher;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.Task;
import ca.uhn.fhir.jpa.topic.SubscriptionTopicDispatchRequest;
import ca.uhn.fhir.jpa.searchparam.matcher.SearchParamMatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.Collections;
import org.springframework.beans.factory.annotation.Autowired;
import org.hl7.fhir.r4.model.DocumentReference;

public class SubscriptionNotificationInterceptor {

    private static final Logger logger = LoggerFactory.getLogger(SubscriptionNotificationInterceptor.class);

    private static final String TOPIC_GFE_AVAILABLE_AUTHOR = "http://hl7.org/fhir/us/davinci-pct/SubscriptionTopic/davinci-pct-gfe-available-author-notification";

    private static final String TOPIC_GFE_AVAILABLE_SUBJECT = "http://hl7.org/fhir/us/davinci-pct/SubscriptionTopic/davinci-pct-gfe-available-subject-notification";

    private static final String TOPIC_GFE_COORDINATION_TASK = "http://hl7.org/fhir/us/davinci-pct/SubscriptionTopic/davinci-pct-gfe-coordination-task-notification";

    private final SubscriptionTopicDispatcher subscriptionTopicDispatcher;

    @Autowired
    SearchParamMatcher searchParamMatcher;

    @Autowired
    DaoRegistry daoRegistry;

    public SubscriptionNotificationInterceptor(SubscriptionTopicDispatcher subscriptionTopicDispatcher) {
        this.subscriptionTopicDispatcher = subscriptionTopicDispatcher;
    }

    private static final String[] DOCUMENT_REFERENCE_TOPICS = {
            TOPIC_GFE_AVAILABLE_AUTHOR,
            TOPIC_GFE_AVAILABLE_SUBJECT
    };

    @Hook(Pointcut.STORAGE_PRECOMMIT_RESOURCE_CREATED)
    public void onResourceCreated(IBaseResource resource) {
        if (resource == null) return;
        String resourceType = resource.getClass().getSimpleName();
        String resourceId = resource.getIdElement() != null ? resource.getIdElement().getIdPart() : "unknown";
        logger.info("STORAGE_PRECOMMIT_RESOURCE_CREATED: {} [ID: {}]", resourceType, resourceId);
        dispatchResourceNotification(resource, RestOperationTypeEnum.CREATE);
    }

    @Hook(Pointcut.STORAGE_PRECOMMIT_RESOURCE_UPDATED)
    public void onResourceUpdated(
            IBaseResource theOldResource,
            IBaseResource theNewResource,
            RequestDetails theRequestDetails) {
        if (theNewResource == null) return;

        String resourceType = theNewResource.getClass().getSimpleName();
        String resourceId = theNewResource.getIdElement() != null ? theNewResource.getIdElement().getIdPart() : "unknown";

        logger.info("STORAGE_PRECOMMIT_RESOURCE_UPDATED []: {} [ID: {}]", resourceType, resourceId);
        dispatchResourceNotification(theNewResource, RestOperationTypeEnum.UPDATE);
    }

    @Hook(Pointcut.STORAGE_PRECOMMIT_RESOURCE_DELETED)
    public void onResourceDeleted(IBaseResource resource, RequestDetails requestDetails) {
        if (resource == null) return;
        String resourceType = resource.getClass().getSimpleName();
        String resourceId = resource.getIdElement() != null ? resource.getIdElement().getIdPart() : "unknown";
        logger.info("STORAGE_PRECOMMIT_RESOURCE_DELETED: {} [ID: {}]", resourceType, resourceId);
        dispatchResourceNotification(resource, RestOperationTypeEnum.DELETE);
    }

    private void dispatchResourceNotification(IBaseResource resource, RestOperationTypeEnum opType) {
        String[] topics;
        if (resource instanceof DocumentReference && opType != RestOperationTypeEnum.DELETE) {
            topics = DOCUMENT_REFERENCE_TOPICS;
        } else if (resource instanceof Task) {
            topics = new String[]{ TOPIC_GFE_COORDINATION_TASK };
        } else {
            logger.debug("dispatchResourceNotification: no topic mapped for {} op={}", resource.getClass().getSimpleName(), opType);
            return;
        }

        GenericResourceFilterMatcher matcher = new GenericResourceFilterMatcher(daoRegistry, searchParamMatcher);
        String id = resource.getIdElement() != null ? resource.getIdElement().getIdPart() : "unknown";

        for (String topicUrl : topics) {
            SubscriptionTopicDispatchRequest request = new SubscriptionTopicDispatchRequest(
                topicUrl,
                Collections.singletonList(resource),
                matcher,
                opType,
                null,
                null,
                null
            );
            logger.info("Dispatching {} [ID: {}] op={} topic={}", resource.getClass().getSimpleName(), id, opType, topicUrl);
            subscriptionTopicDispatcher.dispatch(request);
        }
    }
}
