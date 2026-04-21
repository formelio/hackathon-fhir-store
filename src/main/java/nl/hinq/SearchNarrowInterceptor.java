package nl.hinq;

import ca.uhn.fhir.interceptor.api.Hook;
import ca.uhn.fhir.interceptor.api.Pointcut;
import ca.uhn.fhir.rest.api.server.RequestDetails;

import java.util.Map;

public class SearchNarrowInterceptor {

	@Hook(Pointcut.SERVER_INCOMING_REQUEST_POST_PROCESSED)
	public void resourceCreated(RequestDetails requestDetails) {
		Map<String, String[]> parameters = requestDetails.getParameters();

		String resourceName = requestDetails.getResourceName();

		if (resourceName == null) {
			return;
		}

		if (resourceName.equals("Patient")) {
			if (!parameters.containsKey("identifier")) {
				throw new IllegalArgumentException("Search for Patient must contain an identifier parameter.");
			}
		} else if (resourceName.equals("Coverage")) {
			if (!parameters.containsKey("beneficiary") && !parameters.containsKey("patient") ) {
				throw new IllegalArgumentException("Search for Coverage must contain a beneficiary/patient parameter.");
			}
		}
		else {
			if (!parameters.containsKey("patient")) {
				throw new IllegalArgumentException("Search for " + resourceName + " must contain a patient parameter.");
			}
		}
	}
}
