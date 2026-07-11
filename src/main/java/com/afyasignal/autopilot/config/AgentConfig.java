package com.afyasignal.autopilot.config;

import com.afyasignal.autopilot.tools.AutopilotTools;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.agent.hook.hip.HumanInTheLoopHook;
import com.alibaba.cloud.ai.graph.agent.hook.hip.ToolConfig;
import com.alibaba.cloud.ai.graph.checkpoint.BaseCheckpointSaver;
import com.alibaba.cloud.ai.graph.checkpoint.savers.postgresql.PostgresSaver;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.sql.SQLException;
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

	/**
	 * Database-backed checkpoint saver (framework-provided, see PostgresSaver) so a
	 * pending interruption survives an autopilot restart — replaces Phase 1/2's
	 * in-memory MemorySaver. Exposed as its own bean, not just built inline inside
	 * triageAgent(), because ApprovalService also needs it directly: after a
	 * restart there is no in-memory InterruptionMetadata left to resume from, so
	 * it reconstructs one from the last persisted Checkpoint's nodeId + state.
	 *
	 * GOTCHA: PostgresSaver's createTables(true) is not idempotent — its
	 * initTable() issues a bare CREATE INDEX with no IF NOT EXISTS guard, so
	 * calling it again on a database that already has the schema (i.e. every
	 * restart after the first) throws and fails the whole bean. Detect whether
	 * the schema already exists first and only ask it to create tables when it
	 * doesn't — this is what actually makes "restart the autopilot" survivable
	 * rather than just "the checkpoint data outlives the process in theory."
	 */
	@Bean
	public BaseCheckpointSaver checkpointSaver(@Value("${afyasignal.checkpoint.db.host}") String host,
			@Value("${afyasignal.checkpoint.db.port}") int port,
			@Value("${afyasignal.checkpoint.db.user}") String user,
			@Value("${afyasignal.checkpoint.db.password}") String password,
			@Value("${afyasignal.checkpoint.db.database}") String database) throws SQLException {
		boolean schemaExists = checkpointSchemaExists(host, port, user, password, database);
		return PostgresSaver.builder()
			.host(host)
			.port(port)
			.user(user)
			.password(password)
			.database(database)
			.createTables(!schemaExists)
			.build();
	}

	private boolean checkpointSchemaExists(String host, int port, String user, String password, String database)
			throws SQLException {
		String url = "jdbc:postgresql://" + host + ":" + port + "/" + database;
		try (java.sql.Connection connection = java.sql.DriverManager.getConnection(url, user, password)) {
			return connection.getMetaData().getTables(null, null, "graphcheckpoint", null).next();
		}
	}

	@Bean
	public ReactAgent triageAgent(ChatModel chatModel, AutopilotTools tools, BaseCheckpointSaver checkpointSaver) {
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
			.saver(checkpointSaver)
			.tools(List.of(getPatientTriageHistory, draftReferral, notifyCHV))
			.hooks(HumanInTheLoopHook.builder().approvalOn(approvalOn).build())
			.outputKey("result")
			.build();
	}

}
