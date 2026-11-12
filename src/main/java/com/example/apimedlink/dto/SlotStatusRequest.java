package com.example.apimedlink.dto;

/** The status a practitioner wants for their slot: {@code OPEN} or {@code CLOSED} (MLink-26). Read as text, checked by hand. */
public record SlotStatusRequest(String status) {}
