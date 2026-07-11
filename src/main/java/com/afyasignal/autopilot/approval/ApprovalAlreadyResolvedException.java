package com.afyasignal.autopilot.approval;

public class ApprovalAlreadyResolvedException extends RuntimeException {

	public ApprovalAlreadyResolvedException(String threadId, ApprovalStatus status) {
		super("Approval for threadId " + threadId + " was already resolved (" + status + ")");
	}

}
