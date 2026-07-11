package com.afyasignal.autopilot.web;

import com.afyasignal.autopilot.agent.AgentInvocationException;
import com.afyasignal.autopilot.approval.ApprovalAlreadyResolvedException;
import com.afyasignal.autopilot.approval.ApprovalNotFoundException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Converts the approvals/triage failure modes into clean, typed JSON responses
 * instead of a raw 500 + stack trace — Innovation-criterion error handling.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

	private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

	public record ApiError(String error, String message) {
	}

	@ExceptionHandler(ApprovalNotFoundException.class)
	public ResponseEntity<ApiError> handleNotFound(ApprovalNotFoundException ex) {
		return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ApiError("APPROVAL_NOT_FOUND", ex.getMessage()));
	}

	@ExceptionHandler(ApprovalAlreadyResolvedException.class)
	public ResponseEntity<ApiError> handleAlreadyResolved(ApprovalAlreadyResolvedException ex) {
		return ResponseEntity.status(HttpStatus.CONFLICT).body(new ApiError("APPROVAL_ALREADY_RESOLVED", ex.getMessage()));
	}

	@ExceptionHandler(IllegalArgumentException.class)
	public ResponseEntity<ApiError> handleBadRequest(IllegalArgumentException ex) {
		return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ApiError("INVALID_REQUEST", ex.getMessage()));
	}

	@ExceptionHandler(AgentInvocationException.class)
	public ResponseEntity<ApiError> handleAgentFailure(AgentInvocationException ex) {
		log.error("Agent invocation failed", ex);
		return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
			.body(new ApiError("AGENT_UNAVAILABLE",
					"The triage agent (Qwen model or a downstream tool call) did not respond in time. Please retry."));
	}

	@ExceptionHandler(Exception.class)
	public ResponseEntity<ApiError> handleUnexpected(Exception ex) {
		log.error("Unhandled exception", ex);
		return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
			.body(new ApiError("INTERNAL_ERROR", "An unexpected error occurred."));
	}

}
