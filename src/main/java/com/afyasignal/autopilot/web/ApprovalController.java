package com.afyasignal.autopilot.web;

import com.afyasignal.autopilot.approval.ApprovalService;
import com.afyasignal.autopilot.approval.ApprovalService.TriageOutcome;
import com.afyasignal.autopilot.approval.PendingApproval;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * The nurse-facing approval queue: list what's pending (with the agent's
 * reasoning, proposed action payload, patient context and timestamps — all
 * read from Postgres via ApprovalService, so this works identically whether
 * the interrupt happened a second ago or survived a restart) and resolve one.
 */
@RestController
@RequestMapping("/api/approvals")
public class ApprovalController {

	private final ApprovalService approvalService;

	public ApprovalController(ApprovalService approvalService) {
		this.approvalService = approvalService;
	}

	public record DecisionRequest(String decision, String arguments, String reason) {
	}

	@GetMapping
	public ResponseEntity<List<PendingApproval>> listPending() {
		return ResponseEntity.ok(approvalService.listPending());
	}

	@GetMapping("/{id}")
	public ResponseEntity<PendingApproval> get(@PathVariable String id) {
		return approvalService.find(id).map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.notFound().build());
	}

	@PostMapping("/{id}")
	public ResponseEntity<TriageOutcome> resolve(@PathVariable String id, @RequestBody DecisionRequest request) {
		return ResponseEntity.ok(approvalService.resolve(id, request.decision(), request.arguments(), request.reason()));
	}

}
