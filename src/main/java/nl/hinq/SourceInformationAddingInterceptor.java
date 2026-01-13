package nl.hinq;

import ca.uhn.fhir.interceptor.api.Hook;
import ca.uhn.fhir.interceptor.api.Pointcut;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.api.server.ResponseDetails;
import ca.uhn.fhir.util.FhirTerser;
import lombok.extern.slf4j.Slf4j;
import org.hl7.fhir.instance.model.api.IBaseBundle;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.Resource;

import java.util.Collection;
import java.util.Objects;

@Slf4j
public class SourceInformationAddingInterceptor {

	@Hook(Pointcut.SERVER_OUTGOING_RESPONSE)
	public void addSourceInformation(
		RequestDetails requestDetails, ResponseDetails responseDetails, IBaseResource response) {
		if (Objects.isNull(response)) {
			// For delete requests, response is always null.
			log.debug(
				"The response is null. Source information is not added.");
			return;
		}

		if (response instanceof IBaseBundle) {
			FhirTerser terser = requestDetails.getFhirContext().newTerser();
			Collection<IBaseResource> resources = terser.getAllEmbeddedResources(response, true);

			for (IBaseResource resource : resources) {
				Resource res = (Resource) resource;
				res.getMeta().setSource("Medicom Test Organization GF");
			}

			responseDetails.setResponseResource(response);
		} else {
			Resource res = (Resource) response;
			res.getMeta().setSource("Medicom Test Organization GF");
			responseDetails.setResponseResource(response);
		}
	}

}
