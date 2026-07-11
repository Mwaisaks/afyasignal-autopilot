package com.afyasignal.autopilot.approval;

import com.afyasignal.autopilot.agent.TriageAgentInvoker;
import com.alibaba.cloud.ai.graph.NodeOutput;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.action.InterruptionMetadata;
import com.alibaba.cloud.ai.graph.checkpoint.BaseCheckpointSaver;
import com.alibaba.cloud.ai.graph.checkpoint.Checkpoint;

import org.springframework.ai.chat.messages.Message;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Orchestrates the submit/interrupt/resume cycle and is the single source of
 * truth for pending approvals. Deliberately has no in-memory cache: resolve()
 * always reconstructs the InterruptionMetadata needed to resume from the
 * persisted Checkpoint (nodeId + state) and the persisted tool call list, so
 * the exact same code path handles "approve right after the interrupt" and
 * "approve after the autopilot process restarted" — there is nothing else to
 * get out of sync between the two.
 */
@Service
public class ApprovalService {

	private final TriageAgentInvoker agentInvoker;

	private final ApprovalRepository approvalRepository;

	private final BaseCheckpointSaver checkpointSaver;

	public ApprovalService(TriageAgentInvoker agentInvoker, ApprovalRepository approvalRepository,
			BaseCheckpointSaver checkpointSaver) {
		this.agentInvoker = agentInvoker;
		this.approvalRepository = approvalRepository;
		this.checkpointSaver = checkpointSaver;
	}

	public record TriageOutcome(String threadId, String status, List<ToolCallView> pendingApprovals, String result) {
	}

	public TriageOutcome submit(String threadId, String chvReport) {
		RunnableConfig config = RunnableConfig.builder().threadId(threadId).build();
		Optional<NodeOutput> output = agentInvoker.submit(chvReport, config);
		return handleOutput(threadId, chvReport, output);
	}

	public TriageOutcome resolve(String threadId, String decision, String editedArguments, String reason) {
		PendingApproval pending = approvalRepository.findByThreadId(threadId)
			.orElseThrow(() -> new ApprovalNotFoundException(threadId));
		if (pending.status() != ApprovalStatus.PENDING) {
			throw new ApprovalAlreadyResolvedException(threadId, pending.status());
		}

		InterruptionMetadata.ToolFeedback.FeedbackResult result;
		try {
			result = InterruptionMetadata.ToolFeedback.FeedbackResult.valueOf(decision.toUpperCase());
		}
		catch (IllegalArgumentException ex) {
			throw new IllegalArgumentException(
					"decision must be APPROVED or REJECTED (case-insensitive), got: " + decision);
		}

		Checkpoint checkpoint = checkpointSaver.get(RunnableConfig.builder().threadId(threadId).build())
			.orElseThrow(() -> new ApprovalNotFoundException(threadId));

		InterruptionMetadata.Builder feedbackBuilder = InterruptionMetadata.builder()
			.nodeId(checkpoint.getNodeId())
			.state(new OverAllState(checkpoint.getState()));

		for (ToolCallView toolCall : pending.toolCalls()) {
			feedbackBuilder.addToolFeedback(InterruptionMetadata.ToolFeedback.builder()
				.id(toolCall.id())
				.name(toolCall.name())
				.arguments(editedArguments != null ? editedArguments : toolCall.arguments())
				.description(reason != null ? reason : toolCall.description())
				.result(result)
				.build());
		}

		RunnableConfig resumeConfig = RunnableConfig.builder()
			.threadId(threadId)
			.addHumanFeedback(feedbackBuilder.build())
			.build();

		Optional<NodeOutput> output = agentInvoker.resume(resumeConfig);

		ApprovalStatus resolvedStatus = result == InterruptionMetadata.ToolFeedback.FeedbackResult.APPROVED
				? ApprovalStatus.APPROVED : ApprovalStatus.REJECTED;
		approvalRepository.save(new PendingApproval(threadId, pending.chvReport(), pending.toolCalls(),
				resolvedStatus, pending.createdAt(), Instant.now()));

		return handleOutput(threadId, pending.chvReport(), output);
	}

	public List<PendingApproval> listPending() {
		return approvalRepository.findAllPending();
	}

	public Optional<PendingApproval> find(String threadId) {
		return approvalRepository.findByThreadId(threadId);
	}

	private TriageOutcome handleOutput(String threadId, String chvReport, Optional<NodeOutput> output) {
		if (output.isEmpty()) {
			return new TriageOutcome(threadId, "NO_OUTPUT", List.of(), null);
		}

		NodeOutput nodeOutput = output.get();
		if (nodeOutput instanceof InterruptionMetadata interruption) {
			List<ToolCallView> toolCalls = interruption.toolFeedbacks()
				.stream()
				.map(fb -> new ToolCallView(fb.getId(), fb.getName(), fb.getArguments(), fb.getDescription()))
				.toList();
			approvalRepository
				.save(new PendingApproval(threadId, chvReport, toolCalls, ApprovalStatus.PENDING, Instant.now(), null));
			return new TriageOutcome(threadId, "PENDING_APPROVAL", toolCalls, null);
		}

		Object result = nodeOutput.state().data().get("result");
		String resultText = result instanceof Message message ? message.getText() : String.valueOf(result);
		return new TriageOutcome(threadId, "COMPLETED", List.of(), resultText);
	}

}
