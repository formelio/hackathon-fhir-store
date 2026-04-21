package nl.hinq;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.interceptor.api.Hook;
import ca.uhn.fhir.interceptor.api.Pointcut;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.client.api.IGenericClient;
import ca.uhn.fhir.rest.client.api.ServerValidationModeEnum;
import lombok.extern.slf4j.Slf4j;

import org.apache.jena.atlas.lib.InternalErrorException;
import org.hl7.fhir.instance.model.api.IBaseBundle;
import org.hl7.fhir.dstu3.model.*;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
public class NviInterceptor {

	private static final String URA_SYSTEM = "http://fhir.nl/fhir/NamingSystem/ura";
	private static final String BSN_SYSTEM = "http://fhir.nl/fhir/NamingSystem/bsn";
	private static final String ORG_URA = "90000697";
	private static final String NVI_ENDPOINT = "http://dev-nuts-hackaton-source-nuts-knooppunt.dev-nuts-hackathon-source.svc.cluster.local:8081/nvi";
	private static final String MITZ_ENDPOINT = "http://dev-nuts-hackaton-source-nuts-knooppunt.dev-nuts-hackathon-source.svc.cluster.local:8081/mitz";
	private static final FhirContext fhirContextR4 = FhirContext.forR4();

	@Hook(Pointcut.STORAGE_TRANSACTION_PROCESSING)
	public void resourceCreated(RequestDetails requestDetails, IBaseBundle newBundle) {
		log.info("Resource created {}", requestDetails.getFhirContext().newJsonParser().encodeResourceToString(newBundle));
		if (newBundle instanceof Bundle bundle) {
			log.info("Processing nvi request for bundle");
			Optional<Patient> patient = bundle.getEntry().stream()
				.filter(entry -> entry.getResource() instanceof Patient)
				.map(entry -> (Patient) entry.getResource())
				.findFirst();

			Optional<String> optionalBsn = Optional.empty();
			if (patient.isPresent()) {
				optionalBsn = patient.get().getIdentifier().stream()
					.filter(identifier -> identifier.getSystem().equals(BSN_SYSTEM))
					.map(Identifier::getValue)
					.findFirst();
			}

			if (optionalBsn.isEmpty()) {
				log.warn("Patient does not have a BSN identifier.");
				return;
			}

//			Set<String> resources = bundle.getEntry().stream()
//				.map(Bundle.BundleEntryComponent::getResource)
//				.map(resource -> resource.getResourceType().name())
//				.collect(Collectors.toSet());
//			log.info("Resources in bundle: {}", resources);

			String subscription = SUBSCRIPTION.formatted(optionalBsn.get(), ORG_URA);
			log.info("Subscription to post: {}", subscription);
			postSubscription(requestDetails, subscription);
			log.info("Posted Subscription to Mitz for patient with ID: {}", patient.get().getIdElement().getIdPart());

			String list = createListResource(optionalBsn.get(), Set.of("MedicationRequest"));
			log.info("List to post: {}", list);
			postList(requestDetails, list);
			log.info("Posted List to NVI for patient with ID: {}", patient.get().getIdElement().getIdPart());
		}
	}

	private String createListResource(String s, Set<String> resources) {
		org.hl7.fhir.r4.model.ListResource list = new org.hl7.fhir.r4.model.ListResource();
		list.addExtension()
			.setUrl("http://minvws.github.io/generiekefuncties-docs/StructureDefinition/nl-gf-localization-custodian")
			.setValue(new org.hl7.fhir.r4.model.Reference().setIdentifier(new org.hl7.fhir.r4.model.Identifier().setSystem(URA_SYSTEM).setValue(NviInterceptor.ORG_URA)));
		list.setSubject(new org.hl7.fhir.r4.model.Reference().setIdentifier(new org.hl7.fhir.r4.model.Identifier().setSystem(BSN_SYSTEM).setValue(s)));
		list.setSource(new org.hl7.fhir.r4.model.Reference().setIdentifier(new org.hl7.fhir.r4.model.Identifier().setSystem("urn:ietf:rfc:3986").setValue("EHR-SYS-2024-998")));
		list.setSource(new org.hl7.fhir.r4.model.Reference().setIdentifier(new org.hl7.fhir.r4.model.Identifier().setSystem("urn:ietf:rfc:3986").setValue("EHR-SYS-2024-998")).setType("Device"));
		list.setStatus(org.hl7.fhir.r4.model.ListResource.ListStatus.CURRENT);
		list.setMode(org.hl7.fhir.r4.model.ListResource.ListMode.WORKING);
		list.setEmptyReason(new org.hl7.fhir.r4.model.CodeableConcept().addCoding(new org.hl7.fhir.r4.model.Coding().setSystem("http://terminology.hl7.org/CodeSystem/list-empty-reason").setCode("withheld")));
		List<org.hl7.fhir.r4.model.Coding> coding = list.getCode().getCoding();
		for (String resource : resources) {
			coding.add(new org.hl7.fhir.r4.model.Coding().setSystem("http://minvws.github.io/generiekefuncties-docs/CodeSystem/nl-gf-data-categories-cs").setCode(resource));
		}
		return fhirContextR4.newJsonParser().encodeResourceToString(list);
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
