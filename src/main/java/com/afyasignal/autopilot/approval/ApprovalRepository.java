package com.afyasignal.autopilot.approval;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;

/**
 * Durable store for pending_approvals — survives an autopilot restart, unlike
 * the Phase 1 in-memory ConcurrentHashMap it replaces. Schema is created here
 * (not schema.sql) so table creation is idempotent regardless of how the
 * DataSource was provisioned (local dev, Testcontainers, deployed Postgres).
 */
@Repository
public class ApprovalRepository {

	private final JdbcTemplate jdbcTemplate;

	private final ObjectMapper objectMapper;

	public ApprovalRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
		this.jdbcTemplate = jdbcTemplate;
		this.objectMapper = objectMapper;
		jdbcTemplate.execute("""
				CREATE TABLE IF NOT EXISTS pending_approvals (
				    thread_id VARCHAR(255) PRIMARY KEY,
				    chv_report TEXT,
				    tool_calls TEXT NOT NULL,
				    status VARCHAR(20) NOT NULL,
				    created_at TIMESTAMP NOT NULL,
				    resolved_at TIMESTAMP
				)
				""");
	}

	public void save(PendingApproval approval) {
		jdbcTemplate.update("""
				INSERT INTO pending_approvals (thread_id, chv_report, tool_calls, status, created_at, resolved_at)
				VALUES (?, ?, ?, ?, ?, ?)
				ON CONFLICT (thread_id) DO UPDATE SET
				    chv_report = EXCLUDED.chv_report,
				    tool_calls = EXCLUDED.tool_calls,
				    status = EXCLUDED.status,
				    created_at = EXCLUDED.created_at,
				    resolved_at = EXCLUDED.resolved_at
				""", approval.threadId(), approval.chvReport(), writeToolCalls(approval.toolCalls()),
				approval.status().name(), Timestamp.from(approval.createdAt()),
				approval.resolvedAt() != null ? Timestamp.from(approval.resolvedAt()) : null);
	}

	public Optional<PendingApproval> findByThreadId(String threadId) {
		return jdbcTemplate.query("SELECT * FROM pending_approvals WHERE thread_id = ?", this::mapRow, threadId)
			.stream()
			.findFirst();
	}

	public List<PendingApproval> findAllPending() {
		return jdbcTemplate.query("SELECT * FROM pending_approvals WHERE status = ? ORDER BY created_at",
				this::mapRow, ApprovalStatus.PENDING.name());
	}

	private PendingApproval mapRow(ResultSet rs, int rowNum) throws SQLException {
		Timestamp resolvedAt = rs.getTimestamp("resolved_at");
		return new PendingApproval(rs.getString("thread_id"), rs.getString("chv_report"),
				readToolCalls(rs.getString("tool_calls")), ApprovalStatus.valueOf(rs.getString("status")),
				rs.getTimestamp("created_at").toInstant(), resolvedAt != null ? resolvedAt.toInstant() : null);
	}

	private String writeToolCalls(List<ToolCallView> toolCalls) {
		try {
			return objectMapper.writeValueAsString(toolCalls);
		}
		catch (Exception e) {
			throw new IllegalStateException("Failed to serialize tool calls for persistence", e);
		}
	}

	private List<ToolCallView> readToolCalls(String json) {
		try {
			return objectMapper.readValue(json, new TypeReference<List<ToolCallView>>() {
			});
		}
		catch (Exception e) {
			throw new IllegalStateException("Failed to deserialize persisted tool calls", e);
		}
	}

}
