package com.afyasignal.autopilot.agent;

import com.alibaba.cloud.ai.graph.NodeOutput;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Bounds every call into the ReactAgent (a Qwen model call at minimum, often
 * several across a ReAct loop plus tool execution) with a wall-clock timeout and
 * one retry, so a stalled Qwen/AfyaSignal call fails fast with a clear error
 * instead of hanging the request indefinitely. Innovation-criterion error
 * handling, not just a nicety — see PLAN.md Phase 2.
 */
@Component
public class TriageAgentInvoker {

	private static final Logger log = LoggerFactory.getLogger(TriageAgentInvoker.class);

	private final ReactAgent triageAgent;

	private final Duration timeout;

	private final int maxAttempts;

	private final ExecutorService executor = Executors.newCachedThreadPool();

	public TriageAgentInvoker(ReactAgent triageAgent,
			@Value("${afyasignal.agent.model-call-timeout-seconds}") long timeoutSeconds,
			@Value("${afyasignal.agent.model-call-max-attempts}") int maxAttempts) {
		this.triageAgent = triageAgent;
		this.timeout = Duration.ofSeconds(timeoutSeconds);
		this.maxAttempts = maxAttempts;
	}

	public Optional<NodeOutput> submit(String message, RunnableConfig config) {
		return invoke(() -> triageAgent.invokeAndGetOutput(message, config));
	}

	public Optional<NodeOutput> resume(RunnableConfig resumeConfig) {
		return invoke(() -> triageAgent.invokeAndGetOutput("", resumeConfig));
	}

	private Optional<NodeOutput> invoke(Callable<Optional<NodeOutput>> call) {
		Exception lastFailure = null;
		for (int attempt = 1; attempt <= maxAttempts; attempt++) {
			Future<Optional<NodeOutput>> future = executor.submit(call);
			try {
				return future.get(timeout.toSeconds(), TimeUnit.SECONDS);
			}
			catch (Exception ex) {
				future.cancel(true);
				lastFailure = ex;
				log.warn("Triage agent call failed (attempt {}/{}): {}", attempt, maxAttempts, ex.toString());
			}
		}
		throw new AgentInvocationException(
				"Triage agent call failed after " + maxAttempts + " attempt(s), each bounded to " + timeout,
				lastFailure);
	}

}
