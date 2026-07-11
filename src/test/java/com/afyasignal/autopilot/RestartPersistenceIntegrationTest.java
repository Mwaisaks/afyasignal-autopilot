package com.afyasignal.autopilot;

import com.afyasignal.autopilot.approval.ApprovalService;
import com.afyasignal.autopilot.approval.ApprovalService.TriageOutcome;
import com.afyasignal.autopilot.client.AfyaSignalClient;
import com.afyasignal.autopilot.client.AssessmentDto;
import com.afyasignal.autopilot.client.FacilityDto;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.testcontainers.containers.PostgreSQLContainer;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Proves the actual claim in PLAN.md Phase 2 step 2 — "kill the autopilot
 * service, restart, then approve — the resume must still work" — as an
 * automated test rather than only a manual curl session. Simulates a restart
 * by closing one Spring ApplicationContext and starting a completely fresh
 * one against the same Postgres, since that is what actually changes on a
 * real process restart (no shared JVM state, only shared DB state).
 *
 * The two contexts need *different* scripted ChatModel mocks: context A does
 * the initial submit (history lookup, then propose draftReferral), context B
 * only does the resume (one call to produce the final answer from the tool
 * result). A single shared mock's response queue can't span two independent
 * ApplicationContexts, so each gets its own @Configuration.
 */
class RestartPersistenceIntegrationTest {

	static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

	static {
		System.setProperty("testcontainers.reuse.enable", "false");
		postgres.start();
	}

	private static final UUID ASSESSMENT_ID = UUID.randomUUID();

	private static final UUID FACILITY_ID = UUID.randomUUID();

	private static final UUID CHV_ID = UUID.randomUUID();

	private static AfyaSignalClient stubbedAfyaSignalClient() {
		AfyaSignalClient mock = mock(AfyaSignalClient.class);
		AssessmentDto assessment = new AssessmentDto(ASSESSMENT_ID, "CHILD-TEST-1", "Test Child", 18, "Testville",
				"EMERGENCY", "Danger signs present", null, null, null, CHV_ID, "Test CHV", "2026-07-11T00:00:00");
		when(mock.getAssessmentsByChildId("CHILD-TEST-1")).thenReturn(List.of(assessment));
		when(mock.getFacilitiesByVillage("Testville"))
			.thenReturn(List.of(new FacilityDto(FACILITY_ID, "Testville Clinic", "Testville", 5)));
		AssessmentDto updated = new AssessmentDto(ASSESSMENT_ID, "CHILD-TEST-1", "Test Child", 18, "Testville",
				"EMERGENCY", "Danger signs present", "Testville Clinic", "test reason", "DRAFT", CHV_ID, "Test CHV",
				"2026-07-11T00:00:00");
		when(mock.draftReferral(eq(ASSESSMENT_ID), eq(FACILITY_ID), any())).thenReturn(updated);
		return mock;
	}

	@Configuration
	static class SubmitMocksConfig {

		@Bean
		@Primary
		ChatModel chatModel() {
			ChatModel mock = mock(ChatModel.class);
			AssistantMessage.ToolCall historyCall = new AssistantMessage.ToolCall("call-history", "function",
					"getPatientTriageHistory", "{\"childId\":\"CHILD-TEST-1\"}");
			AssistantMessage.ToolCall referralCall = new AssistantMessage.ToolCall("call-referral", "function",
					"draftReferral", "{\"childId\":\"CHILD-TEST-1\",\"reason\":\"test reason\"}");
			when(mock.call(any(Prompt.class)))
				.thenReturn(new ChatResponse(List.of(new Generation(
						AssistantMessage.builder().content("").toolCalls(List.of(historyCall)).build()))))
				.thenReturn(new ChatResponse(List.of(new Generation(
						AssistantMessage.builder().content("").toolCalls(List.of(referralCall)).build()))));
			return mock;
		}

		@Bean
		@Primary
		AfyaSignalClient afyaSignalClient() {
			return stubbedAfyaSignalClient();
		}

	}

	@Configuration
	static class ResumeMocksConfig {

		@Bean
		@Primary
		ChatModel chatModel() {
			ChatModel mock = mock(ChatModel.class);
			when(mock.call(any(Prompt.class))).thenReturn(new ChatResponse(
					List.of(new Generation(AssistantMessage.builder().content("Referral drafted.").build()))));
			return mock;
		}

		@Bean
		@Primary
		AfyaSignalClient afyaSignalClient() {
			return stubbedAfyaSignalClient();
		}

	}

	@AfterAll
	static void stopPostgres() {
		postgres.stop();
	}

	/**
	 * application.yml's own properties (e.g. afyasignal.checkpoint.db.password:
	 * ${DB_PASSWORD}) resolve at higher precedence than SpringApplicationBuilder's
	 * properties(Map), so overriding the derived property names directly is a
	 * no-op — the nested ${DB_PASSWORD} placeholder still wins and fails to
	 * resolve. Set the underlying env var names as System properties instead, the
	 * same as a real restart would receive them from the shell environment.
	 */
	private ConfigurableApplicationContext startContext(Class<?> mocksConfig) {
		System.setProperty("DB_HOST", postgres.getHost());
		System.setProperty("DB_PORT", String.valueOf(postgres.getMappedPort(5432)));
		System.setProperty("DB_USER", postgres.getUsername());
		System.setProperty("DB_PASSWORD", postgres.getPassword());
		System.setProperty("DB_NAME", postgres.getDatabaseName());
		System.setProperty("DASHSCOPE_API_KEY", "test-key");
		System.setProperty("AUTOPILOT_SERVICE_PASSWORD", "test-password");
		return new SpringApplicationBuilder(AfyasignalAutopilotApplication.class, mocksConfig)
			.properties(Map.of("spring.main.web-application-type", "none"))
			.run();
	}

	@Test
	void pendingApprovalSurvivesProcessRestart() {
		String threadId = "it-restart-" + UUID.randomUUID();

		ConfigurableApplicationContext contextA = startContext(SubmitMocksConfig.class);
		try {
			ApprovalService approvalServiceA = contextA.getBean(ApprovalService.class);
			TriageOutcome submitted = approvalServiceA.submit(threadId,
					"CHV report for CHILD-TEST-1: emergency signs.");
			assertThat(submitted.status()).isEqualTo("PENDING_APPROVAL");
		}
		finally {
			contextA.close(); // simulates the autopilot process dying
		}

		ConfigurableApplicationContext contextB = startContext(ResumeMocksConfig.class); // fresh restart, no shared
																							// JVM state
		try {
			ApprovalService approvalServiceB = contextB.getBean(ApprovalService.class);
			assertThat(approvalServiceB.listPending()).anyMatch(a -> a.threadId().equals(threadId));

			TriageOutcome resolved = approvalServiceB.resolve(threadId, "approved", null, null);
			assertThat(resolved.status()).isEqualTo("COMPLETED");

			AfyaSignalClient client = contextB.getBean(AfyaSignalClient.class);
			verify(client).draftReferral(eq(ASSESSMENT_ID), eq(FACILITY_ID), any());
		}
		finally {
			contextB.close();
		}
	}

}
