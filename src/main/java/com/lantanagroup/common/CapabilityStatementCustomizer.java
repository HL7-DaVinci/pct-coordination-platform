package com.lantanagroup.common;

import java.util.List;
import java.util.Map;
import org.hl7.fhir.instance.model.api.IBaseConformance;
import org.hl7.fhir.r4.model.CapabilityStatement;
import org.hl7.fhir.r4.model.CapabilityStatement.CapabilityStatementRestComponent;
import org.hl7.fhir.r4.model.CapabilityStatement.CapabilityStatementRestResourceComponent;
import org.hl7.fhir.r4.model.CapabilityStatement.CapabilityStatementRestResourceOperationComponent;

import ca.uhn.fhir.interceptor.api.Hook;
import ca.uhn.fhir.interceptor.api.Interceptor;
import ca.uhn.fhir.interceptor.api.Pointcut;

@Interceptor
public class CapabilityStatementCustomizer {

  private final String implementationGuideVersion;

  public CapabilityStatementCustomizer(String implementationGuideVersion) {
    org.springframework.util.Assert.hasText(implementationGuideVersion, "PCT implementation guide version is required");
    this.implementationGuideVersion = implementationGuideVersion;
  }

  private static final String PCT_BASE = "http://hl7.org/fhir/us/davinci-pct/";
  private static final Map<String, List<String>> SUPPORTED_PROFILES = Map.of(
      "Task", List.of(
          PCT_BASE + "StructureDefinition/davinci-pct-gfe-contributor-task",
          PCT_BASE + "StructureDefinition/davinci-pct-gfe-coordination-task"),
      "Bundle", List.of(
          PCT_BASE + "StructureDefinition/davinci-pct-gfe-coordination-bundle",
          PCT_BASE + "StructureDefinition/davinci-pct-gfe-packet",
          PCT_BASE + "StructureDefinition/davinci-pct-gfe-information-bundle",
          PCT_BASE + "StructureDefinition/davinci-pct-gfe-bundle",
          PCT_BASE + "StructureDefinition/davinci-pct-gfe-missing-bundle"),
      "Organization", List.of(PCT_BASE + "StructureDefinition/davinci-pct-organization"),
      "Practitioner", List.of(PCT_BASE + "StructureDefinition/davinci-pct-practitioner"),
      "PractitionerRole", List.of(
          "http://hl7.org/fhir/us/core/StructureDefinition/us-core-practitionerrole|7.0.0"));

  @Hook(Pointcut.SERVER_CAPABILITY_STATEMENT_GENERATED)
  public IBaseConformance customize(IBaseConformance theCapabilityStatement) {

    if (theCapabilityStatement instanceof CapabilityStatement capabilityStatement) {
      //capabilityStatement.setId("davinci-pct-coordination-platform");
      capabilityStatement.setName("CoordinationPlatformPatientCostTransparencyCapabilityStatement");
      capabilityStatement.setTitle(
          "Patient Cost Transparency Implementation Guide Coordination Platform Capability Statement");
      capabilityStatement.setDescription(
          "Coordination Platform Capability Statement for the Da Vinci Patient Cost Transparency Implementation Guide");
      capabilityStatement.setLanguage("en");
      capabilityStatement.setPublisher("HL7 Da Vinci PCT Reference Implementation");
      capabilityStatement.setVersion(implementationGuideVersion);
      String platformCapability =
              PCT_BASE + "CapabilityStatement/davinci-pct-coordination-platform|" + implementationGuideVersion;

      if (capabilityStatement.getInstantiates().stream()
              .noneMatch(value -> platformCapability.equals(value.getValue()))) {
        capabilityStatement.addInstantiates(platformCapability);
      }
      for (CapabilityStatementRestComponent rest : capabilityStatement.getRest()) {
        if (rest.getMode() != CapabilityStatement.RestfulCapabilityMode.SERVER) {
          continue;
        }
        setOperationDefinition(rest.getOperation(), "gfe-coordination-request",
            PCT_BASE + "OperationDefinition/GFE-coordination-request");
        // Add missing PCT profiles to matching resources without replacing HAPI-generated capabilities.
        for (CapabilityStatementRestResourceComponent resource : rest.getResource()) {
          if ("Task".equals(resource.getType())) {
            setOperationDefinition(resource.getOperation(), "gfe-retrieve",
                PCT_BASE + "OperationDefinition/GFE-retrieve");
          }
          for (String profile : SUPPORTED_PROFILES.getOrDefault(resource.getType(), List.of())) {
            if (resource.getSupportedProfile().stream().noneMatch(value -> profile.equals(value.getValue()))) {
              resource.addSupportedProfile(profile);
            }
          }
        }
      }
    }
    return theCapabilityStatement;
  }

  private void setOperationDefinition(
      List<CapabilityStatementRestResourceOperationComponent> operations, String name, String definition) {
    for (CapabilityStatementRestResourceOperationComponent operation : operations) {
      if (name.equals(operation.getName()) || ("$" + name).equals(operation.getName())) {
        operation.setDefinition(definition);
      }
    }
  }
}
