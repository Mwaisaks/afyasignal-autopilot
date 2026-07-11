package com.afyasignal.autopilot.client;

import java.util.UUID;

/**
 * recipientRole and type are sent as plain strings that must match AfyaSignal's
 * UserRole / NotificationType enum names exactly (e.g. "CHV", "REFERRAL_ACKNOWLEDGED") —
 * Jackson writes them identically to a real enum, so no duplicate enum is kept here.
 */
public record NotificationBody(UUID recipientId, String recipientRole, String title, String message, String type,
		UUID referenceId, String referenceType) {
}
