package com.lantanagroup.notification;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.subscription.model.ResourceDeliveryMessage;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.api.RestOperationTypeEnum;
import ca.uhn.fhir.interceptor.api.Pointcut;
import ca.uhn.fhir.interceptor.api.Hook;
import ca.uhn.fhir.jpa.topic.SubscriptionTopicDispatcher;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.Meter;
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

    private static final AttributeKey<String> OUTCOME = AttributeKey.stringKey("outcome");

    private static final AttributeKey<String> FAILURE_REASON = AttributeKey.stringKey("failure_reason");

    private static final AttributeKey<String> TOPIC = AttributeKey.stringKey("topic");

    private final LongCounter deliveryAttempts;

    private final LongCounter dispatchFailures;

    private final FhirContext fhirContext;

    public SubscriptionNotificationInterceptor(SubscriptionTopicDispatcher subscriptionTopicDispatcher, FhirContext fhirContext, OpenTelemetry openTelemetry) {
        this.subscriptionTopicDispatcher = subscriptionTopicDispatcher;
        this.fhirContext = fhirContext;
        Meter meter = null;
        try {
            if (openTelemetry != null) {
                meter = openTelemetry.getMeter("com.lantanagroup.notification");
            }
        } catch (RuntimeException e) {
            logger.warn("subscription metrics initialization failed; metrics disabled", e);
        }
        this.deliveryAttempts = createCounter(meter, "pct.subscription.delivery.attempts");
        this.dispatchFailures = createCounter(meter, "pct.subscription.dispatch.failures");
    }

    private static final String[] DOCUMENT_REFERENCE_TOPICS = {
            TOPIC_GFE_AVAILABLE_AUTHOR,
            TOPIC_GFE_AVAILABLE_SUBJECT
    };

    @Hook(Pointcut.STORAGE_PRECOMMIT_RESOURCE_CREATED)
    public void onResourceCreated(IBaseResource resource) {
        if (resource == null) return;
        String resourceType = resource.getClass().getSimpleName();
        String resourceId = getResourceId(resource);
        logger.debug("STORAGE_PRECOMMIT_RESOURCE_CREATED: {} [ID: {}]", resourceType, resourceId);
        dispatchResourceNotification(resource, RestOperationTypeEnum.CREATE);
    }

    @Hook(Pointcut.STORAGE_PRECOMMIT_RESOURCE_UPDATED)
    public void onResourceUpdated(
            IBaseResource theOldResource,
            IBaseResource theNewResource,
            RequestDetails theRequestDetails) {
        if (theNewResource == null) return;

        String resourceType = theNewResource.getClass().getSimpleName();
        String resourceId = getResourceId(theNewResource);
        logger.debug("STORAGE_PRECOMMIT_RESOURCE_UPDATED: {} [ID: {}]", resourceType, resourceId);
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

    /** Fires for every channel type, including resthook, after a successful delivery attempt. */
    @Hook(Pointcut.SUBSCRIPTION_AFTER_DELIVERY)
    public void delivered(ResourceDeliveryMessage message) {
        String topic = deliveryTopic(message);
        recordMetric(deliveryAttempts, "success", null, topic);
        logger.info("delivery succeeded, subscription={} payload={} topic={}", subscriptionId(message), payloadId(message), topic);
    }

    /** Fires after every failed delivery attempt, including each retry. */
    @Hook(Pointcut.SUBSCRIPTION_AFTER_DELIVERY_FAILED)
    public void deliveryFailed(ResourceDeliveryMessage message, Exception exception) {
        String topic = deliveryTopic(message);
        String reason = failureReason(exception);
        recordMetric(deliveryAttempts, "failure", reason, topic);
        logger.warn("delivery failed, subscription={} payload={} topic={} reason={}", subscriptionId(message), payloadId(message), topic, reason);
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
        String id = getResourceId(resource);

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
            logger.debug("Dispatching {} [ID: {}] op={} topic={}", resource.getClass().getSimpleName(), id, opType, topicUrl);
            try {
                subscriptionTopicDispatcher.dispatch(request);
            } catch (RuntimeException e) {
                String reason = failureReason(e);
                logger.error("dispatch failed, topic={} resource={}/{} reason={}", topicUrl, resource.getClass().getSimpleName(), id, reason);
                recordMetric(dispatchFailures, "failure", reason, topicUrl);
            }
        }
    }

    private String getResourceId(IBaseResource resource) {
        return (resource != null && resource.getIdElement() != null) ? resource.getIdElement().getIdPart() : "unknown";
    }

    private static LongCounter createCounter(Meter meter, String name) {
        if (meter == null) return null;
        try {
            return meter.counterBuilder(name).build();
        } catch (RuntimeException e) {
            logger.warn("subscription metric initialization failed, metric={}; metric disabled", name, e);
            return null;
        }
    }

    private static String deliveryTopic(ResourceDeliveryMessage message) {
        if (message == null || message.getSubscription() == null
                || !message.getSubscription().isTopicSubscription()) {
            return "unknown";
        }
        String topic = message.getSubscription().getTopic();
        return topic != null ? topic : "unknown";
    }

    private static void recordMetric(LongCounter counter, String outcome, String failureReason, String topic) {
        if (counter == null) return;
        try {
            Attributes attributes = failureReason == null
                    ? Attributes.of(OUTCOME, outcome, TOPIC, topic)
                    : Attributes.of(OUTCOME, outcome, TOPIC, topic, FAILURE_REASON, failureReason);
            counter.add(1, attributes);
        } catch (RuntimeException e) {
            logger.warn("subscription metric recording failed; subscription processing will continue", e);
        }
    }

    private String subscriptionId(ResourceDeliveryMessage message) {
        try {
            return message == null ? "unknown" : String.valueOf(message.getSubscriptionId(fhirContext));
        } catch (RuntimeException e) {
            return "unknown";
        }
    }

    private static String payloadId(ResourceDeliveryMessage message) {
        try {
            return message == null ? "unknown" : String.valueOf(message.getPayloadId());
        } catch (RuntimeException e) {
            return "unknown";
        }
    }

    private static String failureReason(Throwable ex) {
        return ex == null ? "unknown" : ex.getClass().getSimpleName();
    }
}
