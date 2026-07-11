package com.afyasignal.autopilot;

import com.afyasignal.autopilot.approval.ApprovalService;
import com.afyasignal.autopilot.approval.ApprovalService.TriageOutcome;
import com.afyasignal.autopilot.client.AfyaSignalClient;
import com.afyasignal.autopilot.client.AssessmentDto;
import com.afyasignal.autopilot.client.FacilityDto;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Drives the real ReactAgent + HumanInTheLoopHook + PostgresSaver stack end to
 * end (only the Qwen model call and the AfyaSignal HTTP boundary are mocked —
 * "mock the model layer... deterministic" per PLAN.md Phase 2) to prove the
 * happy and rejection paths without spending real Qwen tokens or needing a
 * live AfyaSignal instance. See TriageLiveSmokeTest for the real-Qwen
 * counterpart, run manually.
 */
@SpringBootTest
@Testcontainers
class TriageFlowIntegrationTest {

	@Container
	static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

	@DynamicPropertySource
	static void registerProperties(DynamicPropertyRegistry registry) {
		registry.add("spring.datasource.url", postgres::getJdbcUrl);
		registry.add("spring.datasource.username", postgres::getUsername);
		registry.add("spring.datasource.password", postgres::getPassword);
		registry.add("afyasignal.checkpoint.db.host", postgres::getHost);
		registry.add("afyasignal.checkpoint.db.port", () -> postgres.getMappedPort(5432));
		registry.add("afyasignal.checkpoint.db.user", postgres::getUsername);
		registry.add("afyasignal.checkpoint.db.password", postgres::getPassword);
		registry.add("afyasignal.checkpoint.db.database", postgres::getDatabaseName);
		registry.add("DASHSCOPE_API_KEY", () -> "test-key");
		registry.add("AUTOPILOT_SERVICE_PASSWORD", () -> "test-password");
	}

	@MockitoBean
	ChatModel chatModel;

	@MockitoBean
	AfyaSignalClient afyaSignalClient;

	@Autowired
	ApprovalService approvalService;

	private static final UUID ASSESSMENT_ID = UUID.randomUUID();

	private static final UUID FACILITY_ID = UUID.randomUUID();

	private static final UUID CHV_ID = UUID.randomUUID();

	@BeforeEach
	void stubAfyaSignal() {
		AssessmentDto assessment = new AssessmentDto(ASSESSMENT_ID, "CHILD-TEST-1", "Test Child", 18, "Testville",
				"EMERGENCY", "Danger signs present", null, null, null, CHV_ID, "Test CHV", "2026-07-11T00:00:00");
		when(afyaSignalClient.getAssessmentsByChildId("CHILD-TEST-1")).thenReturn(List.of(assessment));
		when(afyaSignalClient.getFacilitiesByVillage("Testville"))
			.thenReturn(List.of(new FacilityDto(FACILITY_ID, "Testville Clinic", "Testville", 5)));
		AssessmentDto updated = new AssessmentDto(ASSESSMENT_ID, "CHILD-TEST-1", "Test Child", 18, "Testville",
				"EMERGENCY", "Danger signs present", "Testville Clinic", "test reason", "DRAFT", CHV_ID, "Test CHV",
				"2026-07-11T00:00:00");
		when(afyaSignalClient.draftReferral(eq(ASSESSMENT_ID), eq(FACILITY_ID), any())).thenReturn(updated);
	}

	private ChatResponse toolCallResponse(String toolCallId, String toolName, String arguments) {
		AssistantMessage.ToolCall toolCall = new AssistantMessage.ToolCall(toolCallId, "function", toolName,
				arguments);
		AssistantMessage message = AssistantMessage.builder().content("").toolCalls(List.of(toolCall)).build();
		return new ChatResponse(List.of(new Generation(message)));
	}

	private ChatResponse toolCallResponse(List<AssistantMessage.ToolCall> toolCalls) {
		AssistantMessage message = AssistantMessage.builder().content("").toolCalls(toolCalls).build();
		return new ChatResponse(List.of(new Generation(message)));
	}

	private ChatResponse finalResponse(String text) {
		AssistantMessage message = AssistantMessage.builder().content(text).build();
		return new ChatResponse(List.of(new Generation(message)));
	}

	@Test
	void happyPath_approvalDraftsReferralAndNotifiesChv() {
		when(chatModel.call(any(org.springframework.ai.chat.prompt.Prompt.class)))
			.thenReturn(toolCallResponse("call-history", "getPatientTriageHistory", "{\"childId\":\"CHILD-TEST-1\"}"))
			.thenReturn(toolCallResponse(List.of(
					new AssistantMessage.ToolCall("call-referral", "function", "draftReferral",
							"{\"childId\":\"CHILD-TEST-1\",\"reason\":\"test reason\"}"),
					new AssistantMessage.ToolCall("call-notify", "function", "notifyCHV",
							"{\"childId\":\"CHILD-TEST-1\",\"message\":\"test message\"}"))))
			.thenReturn(finalResponse("Referral drafted and CHV notified."));

		String threadId = "it-happy-" + UUID.randomUUID();
		TriageOutcome submitted = approvalService.submit(threadId, "CHV report for CHILD-TEST-1: emergency signs.");
		assertThat(submitted.status()).isEqualTo("PENDING_APPROVAL");
		assertThat(submitted.pendingApprovals()).hasSize(2);

		TriageOutcome resolved = approvalService.resolve(threadId, "approved", null, null);
		assertThat(resolved.status()).isEqualTo("COMPLETED");
		assertThat(resolved.result()).contains("notified");

		verify(afyaSignalClient).draftReferral(eq(ASSESSMENT_ID), eq(FACILITY_ID), any());
		verify(afyaSignalClient).createNotification(any());
		assertThat(approvalService.find(threadId)).isPresent();
		assertThat(approvalService.find(threadId).get().status().name()).isEqualTo("APPROVED");
	}

	@Test
	void rejectionPath_noSideEffectsAndAgentAdapts() {
		when(chatModel.call(any(org.springframework.ai.chat.prompt.Prompt.class)))
			.thenReturn(toolCallResponse("call-history", "getPatientTriageHistory", "{\"childId\":\"CHILD-TEST-1\"}"))
			.thenReturn(toolCallResponse(List.of(new AssistantMessage.ToolCall("call-referral", "function",
					"draftReferral", "{\"childId\":\"CHILD-TEST-1\",\"reason\":\"test reason\"}"))))
			.thenReturn(finalResponse("Understood, no referral will be drafted."));

		String threadId = "it-reject-" + UUID.randomUUID();
		TriageOutcome submitted = approvalService.submit(threadId, "CHV report for CHILD-TEST-1: emergency signs.");
		assertThat(submitted.status()).isEqualTo("PENDING_APPROVAL");

		TriageOutcome resolved = approvalService.resolve(threadId, "rejected", null, "Already treated on-site");
		assertThat(resolved.status()).isEqualTo("COMPLETED");

		verify(afyaSignalClient, never()).draftReferral(any(), any(), any());
		verify(afyaSignalClient, never()).createNotification(any());
		assertThat(approvalService.find(threadId).get().status().name()).isEqualTo("REJECTED");
	}

}
