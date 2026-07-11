package com.afyasignal.autopilot.approval;

/** The nurse-facing shape of one gated tool call awaiting a decision. */
public record ToolCallView(String id, String name, String arguments, String description) {
}
