package com.afyasignal.autopilot.web;

import com.afyasignal.autopilot.approval.ApprovalService;
import com.afyasignal.autopilot.approval.ApprovalService.TriageOutcome;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Entry point for a new CHV report and its inline decision endpoint. All
 * persistence and resume logic lives in ApprovalService — this controller and
 * ApprovalController are two front doors onto the same service so a decision
 * can be made either right after the interrupt (here) or later from the
 * approvals queue (ApprovalController), without duplicating the resume logic.
 */
@RestController
@RequestMapping("/api/triage")
public class TriageController {

	private final ApprovalService approvalService;

	public TriageController(ApprovalService approvalService) {
		this.approvalService = approvalService;
	}

	public record TriageRequest(String threadId, String message) {
	}

	public record DecisionRequest(String decision, String arguments, String reason) {
	}

	@PostMapping
	public ResponseEntity<TriageOutcome> submit(@RequestBody TriageRequest request) {
		return ResponseEntity.ok(approvalService.submit(request.threadId(), request.message()));
	}

	@PostMapping("/{threadId}/decision")
	public ResponseEntity<TriageOutcome> decide(@PathVariable String threadId, @RequestBody DecisionRequest request) {
		return ResponseEntity
			.ok(approvalService.resolve(threadId, request.decision(), request.arguments(), request.reason()));
	}

}
