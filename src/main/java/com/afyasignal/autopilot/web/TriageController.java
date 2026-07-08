package com.afyasignal.autopilot.web;

import com.alibaba.cloud.ai.graph.NodeOutput;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.action.InterruptionMetadata;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;

import org.springframework.ai.chat.messages.Message;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Phase 1 spike surface: submit an ambiguous CHV report, see the agent pause for
 * nurse approval before the gated draftReferral tool runs, then resume it.
 */
@RestController
@RequestMapping("/api/triage")
public class TriageController {

	private final ReactAgent triageAgent;

	private final ConcurrentHashMap<String, InterruptionMetadata> pendingByThread = new ConcurrentHashMap<>();

	public TriageController(ReactAgent triageAgent) {
		this.triageAgent = triageAgent;
	}

	public record TriageRequest(String threadId, String message) {
	}

	public record DecisionRequest(String decision, String arguments, String reason) {
	}

	public record ToolFeedbackView(String id, String name, String arguments, String description) {
	}

	public record TriageResponse(String threadId, String status, List<ToolFeedbackView> pendingApprovals,
			String result) {
	}

	@PostMapping
	public ResponseEntity<TriageResponse> submit(@RequestBody TriageRequest request) throws Exception {
		RunnableConfig config = RunnableConfig.builder().threadId(request.threadId()).build();
		Optional<NodeOutput> output = triageAgent.invokeAndGetOutput(request.message(), config);
		return ResponseEntity.ok(toResponse(request.threadId(), output));
	}

	@PostMapping("/{threadId}/decision")
	public ResponseEntity<TriageResponse> decide(@PathVariable String threadId, @RequestBody DecisionRequest request)
			throws Exception {
		InterruptionMetadata pending = pendingByThread.remove(threadId);
		if (pending == null) {
			return ResponseEntity.notFound().build();
		}

		InterruptionMetadata.ToolFeedback.FeedbackResult result = InterruptionMetadata.ToolFeedback.FeedbackResult
			.valueOf(request.decision().toUpperCase());

		InterruptionMetadata.Builder feedbackBuilder = InterruptionMetadata.builder()
			.nodeId(pending.node())
			.state(pending.state());

		pending.toolFeedbacks().forEach(toolFeedback -> {
			InterruptionMetadata.ToolFeedback.Builder builder = InterruptionMetadata.ToolFeedback
				.builder(toolFeedback)
				.result(result);
			if (request.arguments() != null) {
				builder.arguments(request.arguments());
			}
			if (request.reason() != null) {
				builder.description(request.reason());
			}
			feedbackBuilder.addToolFeedback(builder.build());
		});

		RunnableConfig resumeConfig = RunnableConfig.builder()
			.threadId(threadId)
			.addHumanFeedback(feedbackBuilder.build())
			.build();

		Optional<NodeOutput> output = triageAgent.invokeAndGetOutput("", resumeConfig);
		return ResponseEntity.ok(toResponse(threadId, output));
	}

	private TriageResponse toResponse(String threadId, Optional<NodeOutput> output) {
		if (output.isEmpty()) {
			return new TriageResponse(threadId, "NO_OUTPUT", List.of(), null);
		}

		NodeOutput nodeOutput = output.get();
		if (nodeOutput instanceof InterruptionMetadata interruption) {
			pendingByThread.put(threadId, interruption);
			List<ToolFeedbackView> views = interruption.toolFeedbacks()
				.stream()
				.map(feedback -> new ToolFeedbackView(feedback.getId(), feedback.getName(), feedback.getArguments(),
						feedback.getDescription()))
				.toList();
			return new TriageResponse(threadId, "PENDING_APPROVAL", views, null);
		}

		Object result = nodeOutput.state().data().get("result");
		String resultText = result instanceof Message message ? message.getText() : String.valueOf(result);
		return new TriageResponse(threadId, "COMPLETED", List.of(), resultText);
	}

}
