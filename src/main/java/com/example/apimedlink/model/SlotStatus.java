package com.example.apimedlink.model;

/**
 * Where a slot stands (MLink-97, MLink-26). FREE can be booked; BOOKED holds a patient's appointment; CLOSED
 * was closed by its practitioner (kept, never deleted) and is neither offered nor bookable until reopened.
 */
public enum SlotStatus {
    FREE,
    BOOKED,
    CLOSED
}
