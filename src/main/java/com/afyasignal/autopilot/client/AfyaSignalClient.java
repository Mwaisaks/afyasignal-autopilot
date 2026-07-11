package com.afyasignal.autopilot.client;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

/**
 * Talks to the existing AfyaSignal (Boot 4) backend as the AUTOPILOT service
 * account over its normal JWT login flow — see AfyaSignal's ServiceAccountSeeder.
 * Token is cached and only refreshed on login or a 401 from a downstream call.
 */
@Component
public class AfyaSignalClient {

	private final RestClient restClient;

	private final String email;

	private final String password;

	private volatile String cachedToken;

	public AfyaSignalClient(@Value("${afyasignal.api.base-url}") String baseUrl,
			@Value("${afyasignal.api.service-account.email}") String email,
			@Value("${afyasignal.api.service-account.password}") String password) {
		this.restClient = RestClient.create(baseUrl);
		this.email = email;
		this.password = password;
	}

	public List<AssessmentDto> getAssessmentsByChildId(String childId) {
		return withAuth(token -> restClient.get()
			.uri("/api/assessments/by-child/{childId}", childId)
			.header("Authorization", "Bearer " + token)
			.retrieve()
			.body(new ParameterizedTypeReference<List<AssessmentDto>>() {
			}));
	}

	public List<FacilityDto> getFacilitiesByVillage(String village) {
		return withAuth(token -> restClient.get()
			.uri("/api/facilities/village/{village}", village)
			.header("Authorization", "Bearer " + token)
			.retrieve()
			.body(new ParameterizedTypeReference<List<FacilityDto>>() {
			}));
	}

	public AssessmentDto draftReferral(UUID assessmentId, UUID facilityId, String reason) {
		return withAuth(token -> restClient.patch()
			.uri("/api/assessments/{id}/referral", assessmentId)
			.header("Authorization", "Bearer " + token)
			.body(new ReferralDraftBody(facilityId, reason))
			.retrieve()
			.body(AssessmentDto.class));
	}

	public void createNotification(NotificationBody body) {
		withAuth(token -> {
			restClient.post()
				.uri("/api/notifications")
				.header("Authorization", "Bearer " + token)
				.body(body)
				.retrieve()
				.toBodilessEntity();
			return null;
		});
	}

	private <T> T withAuth(Function<String, T> call) {
		String token = currentToken();
		try {
			return call.apply(token);
		}
		catch (RestClientResponseException ex) {
			if (ex.getStatusCode() == HttpStatusCode.valueOf(401)) {
				return call.apply(login());
			}
			throw ex;
		}
	}

	private String currentToken() {
		String token = cachedToken;
		return token != null ? token : login();
	}

	private synchronized String login() {
		LoginResponse response = restClient.post()
			.uri("/api/auth/login")
			.body(Map.of("email", email, "password", password))
			.retrieve()
			.body(LoginResponse.class);
		cachedToken = response.token();
		return cachedToken;
	}

}
