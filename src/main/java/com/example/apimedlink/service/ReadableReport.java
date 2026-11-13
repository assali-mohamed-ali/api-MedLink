package com.example.apimedlink.service;

import java.time.Instant;
import java.util.UUID;

/** A consultation report with its text opened (MLink-32): plain values, for the controller's mapper. */
public record ReadableReport(UUID id, Instant createdAt, String content) {}
