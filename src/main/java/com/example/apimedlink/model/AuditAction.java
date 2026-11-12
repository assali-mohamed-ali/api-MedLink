package com.example.apimedlink.model;

/** What an audit entry records (MLink-59). Other tasks add their values (MLink-72: account operations). */
public enum AuditAction {
    ACCESS_DENIED,
    /** A patient booked a slot; the resource is the appointment (MLink-104). */
    APPOINTMENT_BOOKED,
    /** A practitioner read a patient's record (MLink-31): ids only, never medical content. */
    RECORD_VIEWED
}
