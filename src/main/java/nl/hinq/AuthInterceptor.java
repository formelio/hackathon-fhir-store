package nl.hinq;

import ca.uhn.fhir.interceptor.api.Hook;
import ca.uhn.fhir.interceptor.api.Pointcut;
import ca.uhn.fhir.rest.api.RestOperationTypeEnum;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.server.exceptions.AuthenticationException;
import lombok.extern.slf4j.Slf4j;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

@Slf4j
public class AuthInterceptor {

	private static final String NUTS_INTROSPECTION_URL =
		"http://hinq-interop-services-nuts-internal.acc-hinq-interop-network-services.svc.cluster.local/internal/auth/v2/accesstoken/introspect";

	private static final HttpClient httpClient = HttpClient.newHttpClient();

	@Hook(Pointcut.SERVER_INCOMING_REQUEST_POST_PROCESSED)
	public void authenticate(RequestDetails requestDetails) {
		if (Objects.equals(requestDetails.getRestOperationType(), RestOperationTypeEnum.METADATA)) {
			log.info("Metadata endpoint reached: {}", requestDetails.getRequestPath());
			return;
		}

		String authorizationHeader = requestDetails.getHeader("Authorization");

		if (authorizationHeader == null || !authorizationHeader.startsWith("Bearer ")) {
			throw new AuthenticationException("Missing or invalid Authorization header");
		}

		String token = authorizationHeader.substring("Bearer ".length()).trim();

		if (token.equals("hinq-admin-token")) {
			log.info("Admin token used for request [{}] - skipping token introspection", requestDetails.getRequestPath());
			requestDetails.setAttribute("isAdmin", true);
			return;
		}

		boolean isSearchOrRead = Objects.equals(requestDetails.getRestOperationType(), RestOperationTypeEnum.SEARCH_TYPE) ||
			Objects.equals(requestDetails.getRestOperationType(), RestOperationTypeEnum.READ);
		if (!isSearchOrRead) {
			log.error("Token authentication is only allowed for search and read operations. Request [{}] with operation type [{}] is not allowed.",
				requestDetails.getRequestPath(), requestDetails.getRestOperationType());
			throw new AuthenticationException("Only search and read operations are allowed with token authentication");
		}

		try {
			String formData = "token=" + URLEncoder.encode(token, StandardCharsets.UTF_8);

			HttpRequest introspectionRequest = HttpRequest.newBuilder()
				.uri(URI.create(NUTS_INTROSPECTION_URL))
				.header("Content-Type", "application/x-www-form-urlencoded")
				.POST(HttpRequest.BodyPublishers.ofString(formData))
				.build();

			HttpResponse<String> introspectionResponse =
				httpClient.send(introspectionRequest, HttpResponse.BodyHandlers.ofString());

			if (introspectionResponse.statusCode() != 200) {
				log.warn("Introspection endpoint returned HTTP {}", introspectionResponse.statusCode());
				throw new AuthenticationException("Token introspection failed");
			}

			String responseBody = introspectionResponse.body();
			log.debug("Introspection response for request [{}]: {}", requestDetails.getRequestPath(), responseBody);

			if (!isTokenActive(responseBody)) {
				throw new AuthenticationException("Token is not active");
			}

			log.debug("Token successfully validated for request: {}", requestDetails.getRequestPath());

		} catch (AuthenticationException e) {
			throw e;
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			log.error("Token introspection interrupted", e);
			throw new AuthenticationException("Token introspection was interrupted");
		} catch (Exception e) {
			log.error("Token introspection error", e);
			throw new AuthenticationException("Token introspection error: " + e.getMessage());
		}
	}

	private boolean isTokenActive(String responseBody) {
		// Standard OAuth2 token introspection response (RFC 7662): {"active": true, ...}
		return responseBody != null
			&& (responseBody.contains("\"active\":true") || responseBody.contains("\"active\": true"));
	}
}
