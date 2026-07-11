package com.afyasignal.autopilot.approval;

import java.time.Instant;
import java.util.List;

/**
 * Durable record of one agent interruption. threadId is both the graph's own
 * thread identifier and this row's primary key — one thread has at most one
 * active interruption at a time, so there is no need for a separate id scheme.
 */
public record PendingApproval(String threadId, String chvReport, List<ToolCallView> toolCalls,
		ApprovalStatus status, Instant createdAt, Instant resolvedAt) {
}
