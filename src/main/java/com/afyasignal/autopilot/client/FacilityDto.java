package com.afyasignal.autopilot.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.UUID;

@JsonIgnoreProperties(ignoreUnknown = true)
public record FacilityDto(UUID id, String name, String village, Integer availableBeds) {
}
