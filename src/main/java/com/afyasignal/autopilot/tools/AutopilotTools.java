package com.afyasignal.autopilot.tools;

import com.afyasignal.autopilot.client.AfyaSignalClient;
import com.afyasignal.autopilot.client.AssessmentDto;
import com.afyasignal.autopilot.client.FacilityDto;
import com.afyasignal.autopilot.client.NotificationBody;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * The three tools the triage agent may call (see PLAN.md's fixed scope table).
 * Each takes a childId rather than internal AfyaSignal ids/UUIDs the model never
 * sees; the multi-hop lookups (assessment -> facility, assessment -> chv) happen
 * here, not as extra tools, to keep the tool surface exactly as scoped.
 */
@Component
public class AutopilotTools {

	private final AfyaSignalClient client;

	public AutopilotTools(AfyaSignalClient client) {
		this.client = client;
	}

	public record PatientHistoryArgs(String childId) {
	}

	public record DraftReferralArgs(String childId, String reason) {
	}

	public record NotifyChvArgs(String childId, String message) {
	}

	public String getPatientTriageHistory(PatientHistoryArgs args) {
		List<AssessmentDto> history = client.getAssessmentsByChildId(args.childId());
		if (history.isEmpty()) {
			return "No triage history found for childId " + args.childId();
		}
		StringBuilder summary = new StringBuilder();
		for (AssessmentDto a : history) {
			summary.append("assessment ")
				.append(a.id())
				.append(": child=")
				.append(a.childName())
				.append(", ageMonths=")
				.append(a.ageMonths())
				.append(", village=")
				.append(a.village())
				.append(", triageCategory=")
				.append(a.triageCategory())
				.append(", triageExplanation=")
				.append(a.triageExplanation())
				.append(", referralStatus=")
				.append(a.referralStatus())
				.append(", chv=")
				.append(a.chvName())
				.append(", createdAt=")
				.append(a.createdAt())
				.append('\n');
		}
		return summary.toString();
	}

	public String draftReferral(DraftReferralArgs args) {
		Optional<AssessmentDto> assessment = latestAssessment(args.childId());
		if (assessment.isEmpty()) {
			return "Cannot draft referral: no assessment found for childId " + args.childId();
		}

		List<FacilityDto> facilities = client.getFacilitiesByVillage(assessment.get().village());
		if (facilities.isEmpty()) {
			return "Cannot draft referral: no facility found serving village " + assessment.get().village();
		}
		FacilityDto facility = facilities.get(0);

		AssessmentDto updated = client.draftReferral(assessment.get().id(), facility.id(), args.reason());
		return "Referral drafted (status=" + updated.referralStatus() + ") to " + facility.name() + " for child "
				+ args.childId() + ". Awaiting nurse approval before it is confirmed.";
	}

	public String notifyChv(NotifyChvArgs args) {
		Optional<AssessmentDto> assessment = latestAssessment(args.childId());
		if (assessment.isEmpty()) {
			return "Cannot notify CHV: no assessment found for childId " + args.childId();
		}
		AssessmentDto a = assessment.get();

		client.createNotification(new NotificationBody(a.chvId(), "CHV",
				"Follow-up instructions for " + a.childName(), args.message(), "REFERRAL_ACKNOWLEDGED", a.id(),
				"ASSESSMENT"));
		return "Notified CHV " + a.chvName() + " about child " + args.childId();
	}

	private Optional<AssessmentDto> latestAssessment(String childId) {
		return client.getAssessmentsByChildId(childId).stream().findFirst();
	}

}
