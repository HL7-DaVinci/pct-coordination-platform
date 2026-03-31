package com.lantanagroup.notification;

import ca.uhn.fhir.jpa.topic.filter.ISubscriptionTopicFilterMatcher;
import ca.uhn.fhir.jpa.subscription.model.CanonicalTopicSubscriptionFilter;
import ca.uhn.fhir.jpa.searchparam.matcher.InMemoryMatchResult;
import ca.uhn.fhir.jpa.topic.filter.InMemoryTopicFilterMatcher;
import ca.uhn.fhir.jpa.searchparam.matcher.SearchParamMatcher;
import org.hl7.fhir.instance.model.api.IBaseResource;

/**
 * Generic matcher for any resource type, delegates to HAPI's InMemoryTopicFilterMatcher.
 */
public class GenericResourceFilterMatcher implements ISubscriptionTopicFilterMatcher {
    private final InMemoryTopicFilterMatcher delegate;

    public GenericResourceFilterMatcher(SearchParamMatcher searchParamMatcher) {
        this.delegate = new InMemoryTopicFilterMatcher(searchParamMatcher);
    }

    @Override
    public InMemoryMatchResult match(CanonicalTopicSubscriptionFilter filter, IBaseResource resource) {
        // Delegate to HAPI's built-in matcher for any resource type
        return delegate.match(filter, resource);
    }
}
