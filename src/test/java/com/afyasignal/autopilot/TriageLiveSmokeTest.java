package com.afyasignal.autopilot;

import com.afyasignal.autopilot.approval.ApprovalService;
import com.afyasignal.autopilot.approval.ApprovalService.TriageOutcome;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real end-to-end smoke test against the live Qwen API and a live local
 * AfyaSignal instance — no mocks. Skipped by default (mvn test never spends
 * real tokens or requires a live AfyaSignal); opt in explicitly:
 *
 * <pre>
 * RUN_LIVE_SMOKE_TEST=true DASHSCOPE_API_KEY=... AUTOPILOT_SERVICE_PASSWORD=... \
 *   AFYASIGNAL_API_BASE_URL=http://localhost:8082 DB_PASSWORD=... ./mvnw test -Dtest=TriageLiveSmokeTest
 * </pre>
 *
 * Requires AfyaSignal running locally with demo data seeded (childId
 * CHILD-DEMO-0001, see AfyaSignal's DataSeeder) and a real Postgres for the
 * checkpoint saver.
 */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "RUN_LIVE_SMOKE_TEST", matches = "true")
class TriageLiveSmokeTest {

	@Autowired
	ApprovalService approvalService;

	@Test
	void happyPathAgainstRealQwenAndAfyaSignal() {
		String threadId = "live-smoke-" + UUID.randomUUID();
		TriageOutcome submitted = approvalService.submit(threadId,
				"CHV report for childId CHILD-DEMO-0001: baby very hot, floppy, breathing fast since morning.");

		assertThat(submitted.status()).isEqualTo("PENDING_APPROVAL");
		assertThat(submitted.pendingApprovals()).isNotEmpty();

		TriageOutcome resolved = approvalService.resolve(threadId, "approved", null, null);
		assertThat(resolved.status()).isEqualTo("COMPLETED");
		assertThat(resolved.result()).isNotBlank();
	}

}
