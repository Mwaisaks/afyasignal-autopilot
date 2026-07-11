package com.afyasignal.autopilot.config;

import com.afyasignal.autopilot.tools.AutopilotTools;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.agent.hook.hip.HumanInTheLoopHook;
import com.alibaba.cloud.ai.graph.agent.hook.hip.ToolConfig;
import com.alibaba.cloud.ai.graph.checkpoint.savers.MemorySaver;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.Map;

/**
 * Wires the three real tools from PLAN.md's fixed scope table onto the ReactAgent
 * built in the Phase 1 spike. draftReferral and notifyCHV are gated behind nurse
 * approval via HumanInTheLoopHook; getPatientTriageHistory is read-only and ungated.
 */
@Configuration
public class AgentConfig {

	private static final String SYSTEM_PROMPT = """
			You are AfyaSignal Autopilot, a triage assistant for Community Health Volunteers (CHVs)
			in rural Kenya. A CHV has submitted a free-text report about a child that may be
			ambiguous or incomplete.

			Reason step by step:
			1. Call getPatientTriageHistory to see the child's prior assessments before deciding anything.
			2. Only draft a referral when the history and the CHV's report together indicate the child
			   needs facility-level care. Give a specific, clinically grounded reason.
			3. Only notify the CHV after a referral has been drafted, with clear, actionable follow-up
			   instructions they can act on immediately.
			4. If the report is too ambiguous to act on safely, say so plainly and ask what additional
			   information would resolve the ambiguity, instead of guessing.

			draftReferral and notifyCHV always require nurse approval before they take effect, regardless
			of how confident you are — this is enforced by the platform, not optional.
			""";

	@Bean
	public ReactAgent triageAgent(ChatModel chatModel, AutopilotTools tools) {
		ToolCallback getPatientTriageHistory = FunctionToolCallback
			.builder("getPatientTriageHistory", tools::getPatientTriageHistory)
			.description("Fetches a child's prior triage assessments by childId. Call this first.")
			.inputType(AutopilotTools.PatientHistoryArgs.class)
			.build();

		ToolCallback draftReferral = FunctionToolCallback.builder("draftReferral", tools::draftReferral)
			.description(
					"Drafts a facility referral for a child by childId, with a clinical reason. Requires nurse approval before it takes effect.")
			.inputType(AutopilotTools.DraftReferralArgs.class)
			.build();

		ToolCallback notifyCHV = FunctionToolCallback.builder("notifyCHV", tools::notifyChv)
			.description(
					"Sends follow-up instructions to the CHV who submitted the report for a child by childId. Requires nurse approval before it takes effect.")
			.inputType(AutopilotTools.NotifyChvArgs.class)
			.build();

		Map<String, ToolConfig> approvalOn = Map.of("draftReferral",
				ToolConfig.builder().description("Nurse must approve the referral before it is created").build(),
				"notifyCHV",
				ToolConfig.builder().description("Nurse must approve the CHV notification before it is sent").build());

		return ReactAgent.builder()
			.name("afya_autopilot")
			.model(chatModel)
			.instruction(SYSTEM_PROMPT)
			.saver(new MemorySaver())
			.tools(List.of(getPatientTriageHistory, draftReferral, notifyCHV))
			.hooks(HumanInTheLoopHook.builder().approvalOn(approvalOn).build())
			.outputKey("result")
			.build();
	}

}
