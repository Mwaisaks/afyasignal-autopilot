package com.afyasignal.autopilot.approval;

public class ApprovalNotFoundException extends RuntimeException {

	public ApprovalNotFoundException(String threadId) {
		super("No approval found for threadId " + threadId);
	}

}
