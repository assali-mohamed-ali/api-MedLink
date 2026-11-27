package com.example.apimedlink.model;

/** US-29: where a proposal to move an appointment to another practitioner stands. */
public enum ReassignmentStatus {
    /** Waiting for the patient and the new practitioner. */
    PENDING,
    /** Both accepted: the appointment was moved. */
    DONE,
    /** The patient or the new practitioner declined: nothing moved. */
    DECLINED,
    /** Both accepted but the move was no longer possible (slot taken, appointment cancelled, patient busy). */
    FAILED
}
