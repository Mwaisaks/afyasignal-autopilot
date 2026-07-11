package com.afyasignal.autopilot.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.UUID;

@JsonIgnoreProperties(ignoreUnknown = true)
public record AssessmentDto(UUID id, String childId, String childName, Integer ageMonths, String village,
		String triageCategory, String triageExplanation, String referralFacilityName, String referralReason,
		String referralStatus, UUID chvId, String chvName, String createdAt) {
}
