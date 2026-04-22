package nl.hinq;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.interceptor.api.Hook;
import ca.uhn.fhir.interceptor.api.Pointcut;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.client.api.IGenericClient;
import ca.uhn.fhir.rest.client.api.ServerValidationModeEnum;
import lombok.extern.slf4j.Slf4j;

import org.apache.jena.atlas.lib.InternalErrorException;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.dstu3.model.*;

import java.util.Optional;

@Slf4j
public class NviInterceptor {

	private static final String URA_SYSTEM = "http://fhir.nl/fhir/NamingSystem/ura";
	private static final String BSN_SYSTEM = "http://fhir.nl/fhir/NamingSystem/bsn";
	private static final String ORG_URA = "90000697";
	private static final String NVI_ENDPOINT = "http://dev-nuts-hackaton-source-nuts-knooppunt.dev-nuts-hackathon-source.svc.cluster.local:8081/nvi";
	private static final String MITZ_ENDPOINT = "http://dev-nuts-hackaton-source-nuts-knooppunt.dev-nuts-hackathon-source.svc.cluster.local:8081/mitz";
	private static final FhirContext fhirContextR4 = FhirContext.forR4();

	@Hook(Pointcut.STORAGE_PRECOMMIT_RESOURCE_CREATED)
	public void resourceCreated(RequestDetails requestDetails, IBaseResource newResource) {
		if (newResource instanceof Patient patient) {
			log.info("New patient created with ID: {}", newResource.getIdElement().getIdPart());
			Optional<String> optionalBsn = patient.getIdentifier().stream()
				.filter(id -> BSN_SYSTEM.equals(id.getSystem()))
				.findAny()
				.map(Identifier::getValue);

			if (optionalBsn.isEmpty()) {
				log.warn("Patient does not have a BSN identifier.");
				return;
			}

			String subscription = SUBSCRIPTION.formatted(optionalBsn.get(), ORG_URA);
			log.info("Subscription to post: {}", subscription);
			postSubscription(requestDetails, subscription);
			log.info("Posted Subscription to Mitz for patient with ID: {}", newResource.getIdElement().getIdPart());

			String list = LIST.formatted(ORG_URA, optionalBsn.get());
			log.info("List to post: {}", list);
			postList(requestDetails, list);
			log.info("Posted List to NVI for patient with ID: {}", newResource.getIdElement().getIdPart());

		}
	}

	private void postList(RequestDetails requestDetails, String listResource) {
		FhirContext context = requestDetails.getFhirContext();
		var factory = context.getRestfulClientFactory();
		factory.setServerValidationMode(ServerValidationModeEnum.NEVER);
		IGenericClient client = factory.newGenericClient(NVI_ENDPOINT);
		try {
			client.create()
				.resource(listResource)
				.withAdditionalHeader("X-Tenant-Id", URA_SYSTEM+"|"+ORG_URA)
				.withAdditionalHeader("Content-Type", "application/fhir+json")
				.execute();
		} catch (Exception e) {
			String message = "Error posting List to NVI: %s".formatted(e.getMessage());
			log.error(message);
			throw new InternalErrorException(message);
		}
	}

	private void postSubscription(RequestDetails requestDetails, String subscription) {
		FhirContext context = requestDetails.getFhirContext();
		var factory = context.getRestfulClientFactory();
		factory.setServerValidationMode(ServerValidationModeEnum.NEVER);
		IGenericClient client = factory.newGenericClient(MITZ_ENDPOINT);
		try {
			client.create()
				.resource(subscription)
				.withAdditionalHeader("Content-Type", "application/fhir+json")
				.execute();
		} catch (Exception e) {
			String message = "Error posting Subscription to Mitz: %s".formatted(e.getMessage());
			log.error(message);
			throw new InternalErrorException(message);
		}
	}

	private static String LIST = "{\n" +
		"  \"resourceType\": \"List\",\n" +
		"  \"extension\": [\n" +
		"    {\n" +
		"      \"valueReference\": {\n" +
		"        \"identifier\": {\n" +
		"          \"system\": \"http://fhir.nl/fhir/NamingSystem/ura\",\n" +
		"          \"value\": \"%s\"\n" +
		"        }\n" +
		"      },\n" +
		"      \"url\": \"http://minvws.github.io/generiekefuncties-docs/StructureDefinition/nl-gf-localization-custodian\"\n" +
		"    }\n" +
		"  ],\n" +
		"  \"subject\": {\n" +
		"    \"identifier\": {\n" +
		"      \"system\": \"http://fhir.nl/fhir/NamingSystem/bsn\",\n" +
		"      \"value\": \"%s\"\n" +
		"    }\n" +
		"  },\n" +
		"  \"source\": {\n" +
		"    \"identifier\": {\n" +
		"      \"system\": \"urn:ietf:rfc:3986\",\n" +
		"      \"value\": \"EHR-SYS-2024-998\"\n" +
		"    },\n" +
		"    \"type\": \"Device\"\n" +
		"  },\n" +
		"  \"status\": \"current\",\n" +
		"  \"mode\": \"working\",\n" +
		"  \"emptyReason\": {\n" +
		"    \"coding\": [\n" +
		"      {\n" +
		"        \"code\": \"withheld\",\n" +
		"        \"system\": \"http://terminology.hl7.org/CodeSystem/list-empty-reason\"\n" +
		"      }\n" +
		"    ]\n" +
		"  },\n" +
		"  \"code\": {\n" +
		"    \"coding\": [\n" +
		"      {\n" +
		"        \"code\": \"MedicationRequest\",\n" +
		"        \"system\": \"http://minvws.github.io/generiekefuncties-docs/CodeSystem/nl-gf-data-categories-cs\",\n" +
		"        \"display\": \"Medication Request\"\n" +
		"      }\n" +
		"    ]\n" +
		"  }\n" +
		"}";

	private static String SUBSCRIPTION = "{\n" + //
				"    \"resourceType\": \"Subscription\",\n" + //
				"    \"status\": \"requested\",\n" + //
				"    \"reason\": \"OTV\",\n" + //
				"    \"criteria\": \"Consent?_query=otv&patientid=%s&providerid=%s&providertype=Z3\",\n" + //
				"    \"channel\": {\n" + //
				"        \"type\": \"rest-hook\",\n" + //
				"        \"payload\": \"application/fhir+json\"\n" + //
				"    }\n" + //
				"}";
}
