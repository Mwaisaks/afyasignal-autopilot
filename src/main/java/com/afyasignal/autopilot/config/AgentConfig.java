package com.afyasignal.autopilot.config;

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
 * Phase 1 spike: proves the interrupt -> approve -> resume cycle with a single
 * dummy gated tool before real AfyaSignal API calls are wired in (Phase 2).
 */
@Configuration
public class AgentConfig {

	public record DraftReferralArgs(String patientSummary, String reason) {
	}

	@Bean
	public ReactAgent triageAgent(ChatModel chatModel) {
		ToolCallback draftReferral = FunctionToolCallback.builder("draftReferral",
				(DraftReferralArgs args) -> "Referral drafted (DRAFT state) for [" + args.patientSummary()
						+ "] because: " + args.reason())
			.description("Drafts a patient referral for nurse review. Requires nurse approval before it takes effect.")
			.inputType(DraftReferralArgs.class)
			.build();

		Map<String, ToolConfig> approvalOn = Map.of(
				"draftReferral", ToolConfig.builder().description("Nurse must approve the referral before it is created").build());

		return ReactAgent.builder()
			.name("afya_autopilot")
			.model(chatModel)
			.saver(new MemorySaver())
			.tools(List.of(draftReferral))
			.hooks(HumanInTheLoopHook.builder().approvalOn(approvalOn).build())
			.outputKey("result")
			.build();
	}

}
